#!/usr/bin/env bash

base_path=../../app/src/main/assets

cp -f "cmake-build-debug-aarch64/fmbin" "${base_path}/fmbin-aarch64"
cp -f "cmake-build-debug-armv7a/fmbin" "${base_path}/fmbin-armv7a"
