#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

version=${1:?Expected a release version}
binary=$(mktemp)
trap 'rm -f "$binary"' EXIT

verify_apk() {
    local flavor=$1 abi=$2 fmbin=$3 elf_class=$4 machine=$5
    local directory="app/build/outputs/apk/$flavor/release"
    local name apk entries entry header

    name=$(jq -er --arg v "$version" --arg abi "$abi" '
        (.applicationId + "-" + $v + "-" + $abi + ".apk") as $name
        | .elements | select(length == 1) | .[0]
        | select(.versionName == $v and .outputFile == $name) | .outputFile
    ' "$directory/output-metadata.json") || {
        echo "$flavor: expected a $version release APK for $abi" >&2
        return 1
    }
    apk="$directory/$name"
    entries=$(unzip -Z1 "$apk")
    while IFS= read -r entry; do
        case "$entry" in
            "lib/$abi/"*|"assets/$fmbin") ;;
            lib/*|assets/fmbin-*) echo "$name: unexpected $entry" >&2; return 1 ;;
        esac
    done <<< "$entries"

    for entry in "lib/$abi/libmp3encoder.so" "lib/$abi/libopusencoder.so" "assets/$fmbin"; do
        unzip -p "$apk" "$entry" > "$binary"
        header=$(readelf -h "$binary")
        if ! grep -Eq "Class:[[:space:]]+$elf_class$" <<< "$header" ||
           ! grep -Eq "Machine:[[:space:]]+$machine$" <<< "$header" ||
           ! grep -Eq 'Data:.*little endian$' <<< "$header"; then
            echo "$name: $entry is not a $abi ELF binary" >&2
            return 1
        fi
    done
    echo "Verified $apk"
}

verify_apk arm64 arm64-v8a fmbin-aarch64 ELF64 AArch64
verify_apk armv7 armeabi-v7a fmbin-armv7a ELF32 ARM
