#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
BUILD_ROOT="${BUILD_ROOT:-/tmp/rfm-radio-native}"
ASSETS_DIR="${ROOT_DIR}/app/src/main/assets"
REQUIRED_NDK_VERSION="29.0.14033849"
REQUIRED_CMAKE_VERSION="3.22.1"

resolve_toolchain() {
    local sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"

    if [[ -z "${sdk_root}" && -n "${ANDROID_NDK_HOME:-}" ]]; then
        sdk_root="$(cd "${ANDROID_NDK_HOME}/../.." && pwd)"
    fi

    if [[ -z "${sdk_root}" ]]; then
        echo "ANDROID_SDK_ROOT or ANDROID_HOME must point to the Android SDK" >&2
        exit 1
    fi

    export ANDROID_NDK_HOME="${sdk_root}/ndk/${REQUIRED_NDK_VERSION}"
    CMAKE="${sdk_root}/cmake/${REQUIRED_CMAKE_VERSION}/bin/cmake"

    if [[ ! -f "${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake" ]]; then
        echo "Android NDK ${REQUIRED_NDK_VERSION} is not installed at ${ANDROID_NDK_HOME}" >&2
        exit 1
    fi

    if [[ ! -x "${CMAKE}" ]]; then
        echo "CMake ${REQUIRED_CMAKE_VERSION} is not installed at ${CMAKE}" >&2
        exit 1
    fi
}

build_android_abi() {
    local source_dir="$1"
    local build_dir="$2"
    local abi="$3"

    rm -rf "${build_dir}"
    mkdir -p "${build_dir}"
    "${CMAKE}" \
        -S "${source_dir}" \
        -B "${build_dir}" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="${abi}" \
        -DANDROID_PLATFORM=android-21 \
        "${source_dir}"
    "${CMAKE}" --build "${build_dir}" --clean-first
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

main() {
    resolve_toolchain

    echo "Building unified Qualcomm native assets"

    local armv7_dir="${BUILD_ROOT}/qualcomm/cmake-build-armv7a"
    local aarch64_dir="${BUILD_ROOT}/qualcomm/cmake-build-aarch64"

    build_android_abi "${SCRIPT_DIR}" "${armv7_dir}" "armeabi-v7a"
    build_android_abi "${SCRIPT_DIR}" "${aarch64_dir}" "arm64-v8a"
    copy_asset "${armv7_dir}/fmbin" "fmbin-armv7a"
    copy_asset "${aarch64_dir}/fmbin" "fmbin-aarch64"
}

main "$@"
