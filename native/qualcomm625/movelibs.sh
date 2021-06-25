#!/usr/bin/env bash

base_path=../../app/src/main/assets
arch_list=("aarch64" "armv7a")

type=$1

if [[ ! "$type" == "release" ]]; then
    type="debug"
fi

for arch in "$arch_list"; do
    source_file="cmake-build-${type}-android-${arch}/fmbin"

    if [[ -f "$source_file" ]]; then
        dest_file="${base_path}/fmbin-${arch}"

        cp -f "$source_file" "$dest_file"
        echo "File $source_file moved to $dest_file"
    fi
done
