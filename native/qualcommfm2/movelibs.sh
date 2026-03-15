#!/usr/bin/env bash

base_path=../../app/src/main/assets
arch_list="aarch64 armv7a"

for arch in $arch_list; do
    if [[ "$arch" == "aarch64" ]]; then
        source_file="${BUILD_AARCH64_DIR:-cmake-build-aarch64}/fmbin_fm2"
    else
        source_file="${BUILD_ARMV7A_DIR:-cmake-build-armv7a}/fmbin_fm2"
    fi
    if [[ -f "$source_file" ]]; then
        dest_file="${base_path}/fmbin-fm2-${arch}"
        cp -f "$source_file" "$dest_file"
        echo "File $source_file moved to $dest_file"
    else
        echo "File $source_file not found"
    fi
done

ls -lah ../../app/src/main/assets/fmbin-fm2-*
