#!/bin/bash

set -e

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    if [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}/ndk/29.0.14033849" ]]; then
        export ANDROID_NDK_HOME="${ANDROID_HOME}/ndk/29.0.14033849"
    elif [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}/ndk/27.3.13750724" ]]; then
        export ANDROID_NDK_HOME="${ANDROID_HOME}/ndk/27.3.13750724"
    else
        echo "ANDROID_NDK_HOME is not set and no known NDK was found under ANDROID_HOME" >&2
        exit 1
    fi
fi

if [[ ! -f "${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake" ]]; then
    echo "Invalid ANDROID_NDK_HOME: ${ANDROID_NDK_HOME}" >&2
    exit 1
fi

function build_abi {
    abi="$1"
    target_directory="$2"

    mkdir -p "$target_directory"
    cmake \
        -S . \
        -B "$target_directory" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM=android-21 \
        .
    cmake --build "$target_directory" --clean-first
}

BUILD_ROOT="${BUILD_ROOT:-/tmp/rfm-radio-qualcommfm2}"
mkdir -p "$BUILD_ROOT"

export BUILD_ARMV7A_DIR="${BUILD_ROOT}/cmake-build-armv7a"
export BUILD_AARCH64_DIR="${BUILD_ROOT}/cmake-build-aarch64"

build_abi "armeabi-v7a" "$BUILD_ARMV7A_DIR"
build_abi "arm64-v8a" "$BUILD_AARCH64_DIR"

source movelibs.sh
