#!/usr/bin/env bash

base_path=../../app/src/main/assets
arch_list="aarch64 armv7a"

for arch in $arch_list; do
    source_file="cmake-build-${arch}/fmbin"

    if [[ -f "$source_file" ]]; then
        dest_file="${base_path}/fmbin-${arch}"

        cp -f "$source_file" "$dest_file"
        echo "File $source_file moved to $dest_file"
    else
        echo "File $source_file not found"
    fi
done

ls -lah ../../app/src/main/assets/fmbin-*
