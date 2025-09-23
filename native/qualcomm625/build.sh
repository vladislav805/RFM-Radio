#!/bin/bash

export ANDROID_NDK=$ANDROID_HOME/ndk/29.0.14033849/

function build_abi {
    abi="$1"
    target_directory="$2"

    rm -rf "$target_directory"

    mkdir -p "$target_directory"
    pushd "$target_directory"
    cmake \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
        -DANDROID_ABI=$abi \
        -DANDROID_PLATFORM=android-21 \
        ..
    cmake --build .
    popd
}

build_abi "armeabi-v7a" "cmake-build-armv7a" && build_abi "arm64-v8a" "cmake-build-aarch64"

source movelibs.sh
