#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
HAL_DIR="${SCRIPT_DIR}/qualcommfm2"
LEGACY_DIR="${SCRIPT_DIR}/qualcomm625"
BUILD_ROOT="${BUILD_ROOT:-/tmp/rfm-radio-native}"
ASSETS_DIR="${ROOT_DIR}/app/src/main/assets"

resolve_ndk() {
    if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
        :
    elif [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}/ndk/29.0.14033849" ]]; then
        export ANDROID_NDK_HOME="${ANDROID_HOME}/ndk/29.0.14033849"
    elif [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}/ndk/27.3.13750724" ]]; then
        export ANDROID_NDK_HOME="${ANDROID_HOME}/ndk/27.3.13750724"
    elif [[ -n "${ANDROID_SDK_ROOT:-}" && -d "${ANDROID_SDK_ROOT}/ndk/29.0.14033849" ]]; then
        export ANDROID_NDK_HOME="${ANDROID_SDK_ROOT}/ndk/29.0.14033849"
    elif [[ -n "${ANDROID_SDK_ROOT:-}" && -d "${ANDROID_SDK_ROOT}/ndk/27.3.13750724" ]]; then
        export ANDROID_NDK_HOME="${ANDROID_SDK_ROOT}/ndk/27.3.13750724"
    else
        echo "ANDROID_NDK_HOME is not set and no known NDK was found under ANDROID_HOME/ANDROID_SDK_ROOT" >&2
        exit 1
    fi

    if [[ ! -f "${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake" ]]; then
        echo "Invalid ANDROID_NDK_HOME: ${ANDROID_NDK_HOME}" >&2
        exit 1
    fi
}

build_android_abi() {
    local source_dir="$1"
    local build_dir="$2"
    local abi="$3"

    mkdir -p "${build_dir}"
    cmake \
        -S "${source_dir}" \
        -B "${build_dir}" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="${abi}" \
        -DANDROID_PLATFORM=android-21 \
        "${source_dir}"
    cmake --build "${build_dir}" --clean-first
}

copy_asset() {
    local source_file="$1"
    local dest_file="$2"

    if [[ ! -f "${source_file}" ]]; then
        echo "Built file not found: ${source_file}" >&2
        exit 1
    fi

    mkdir -p "${ASSETS_DIR}"
    cp -f "${source_file}" "${ASSETS_DIR}/${dest_file}"
    echo "File ${source_file} moved to ${ASSETS_DIR}/${dest_file}"
}

build_hal() {
    echo "Building Qualcomm HAL native assets"

    export BUILD_ARMV7A_DIR="${BUILD_ROOT}/qualcommfm2/cmake-build-armv7a"
    export BUILD_AARCH64_DIR="${BUILD_ROOT}/qualcommfm2/cmake-build-aarch64"

    build_android_abi "${HAL_DIR}" "${BUILD_ARMV7A_DIR}" "armeabi-v7a"
    build_android_abi "${HAL_DIR}" "${BUILD_AARCH64_DIR}" "arm64-v8a"
    copy_asset "${BUILD_ARMV7A_DIR}/fmbin_fm2" "fmbin-fm2-armv7a"
    copy_asset "${BUILD_AARCH64_DIR}/fmbin_fm2" "fmbin-fm2-aarch64"
}

build_legacy() {
    echo "Building Qualcomm legacy native assets"

    local armv7_dir="${LEGACY_DIR}/cmake-build-release-android-armv7a"
    local aarch64_dir="${LEGACY_DIR}/cmake-build-release-android-aarch64"

    build_android_abi "${LEGACY_DIR}" "${armv7_dir}" "armeabi-v7a"
    build_android_abi "${LEGACY_DIR}" "${aarch64_dir}" "arm64-v8a"
    copy_asset "${armv7_dir}/fmbin" "fmbin-armv7a"
    copy_asset "${aarch64_dir}/fmbin" "fmbin-aarch64"
}

show_usage() {
    cat <<'EOF'
Usage:
  bash ./native/build.sh            # build all native assets
  bash ./native/build.sh all        # build all native assets
  bash ./native/build.sh hal        # build only Qualcomm FM2/HAL assets
  bash ./native/build.sh legacy     # build only Qualcomm legacy/V4L assets
EOF
}

main() {
    resolve_ndk

    if [[ $# -eq 0 ]]; then
        set -- all
    fi

    local build_hal_requested=false
    local build_legacy_requested=false

    for target in "$@"; do
        case "${target}" in
            all)
                build_hal_requested=true
                build_legacy_requested=true
                ;;
            hal|qualcommfm2)
                build_hal_requested=true
                ;;
            legacy|qualcomm625)
                build_legacy_requested=true
                ;;
            -h|--help|help)
                show_usage
                exit 0
                ;;
            *)
                echo "Unknown target: ${target}" >&2
                show_usage >&2
                exit 1
                ;;
        esac
    done

    if [[ "${build_hal_requested}" == true ]]; then
        build_hal
    fi

    if [[ "${build_legacy_requested}" == true ]]; then
        build_legacy
    fi
}

main "$@"
