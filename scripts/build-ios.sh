#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build="${SPEKTRACAM_IOS_BUILD_DIR:-$root/build/ios}"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
glslc="${SPEKTRACAM_GLSLC:-$sdk/ndk/26.1.10909125/shader-tools/darwin-x86_64/glslc}"
moltenvk="${MOLTENVK_ROOT:-$root/build/deps/MoltenVK/MoltenVK}"
version=1.4.2
checksum=b5d947b1660e6e9fed40b9cd2387e160aaab9e80b775c0cef7e14059405178c1

if [[ ! -x "$glslc" ]]; then
    echo "Set SPEKTRACAM_GLSLC to an executable glslc." >&2
    exit 1
fi
if [[ ! -f "$moltenvk/include/vulkan/vulkan.h" && -z "${MOLTENVK_ROOT:-}" ]]; then
    deps="$root/build/deps"
    archive="$deps/MoltenVK-ios-$version.tar"
    mkdir -p "$deps"
    if [[ ! -f "$archive" ]]; then
        curl --fail --location --retry 3 \
            "https://github.com/KhronosGroup/MoltenVK/releases/download/v$version/MoltenVK-ios.tar" \
            --output "$archive.part"
        mv "$archive.part" "$archive"
    fi
    printf '%s  %s\n' "$checksum" "$archive" | shasum -a 256 --check
    tar -xf "$archive" -C "$deps"
fi
library="$moltenvk/static/MoltenVK.xcframework/ios-arm64/libMoltenVK.a"
if [[ ! -f "$library" || ! -f "$moltenvk/include/vulkan/vulkan.h" ]]; then
    echo "MOLTENVK_ROOT must contain the iOS library and Vulkan headers." >&2
    exit 1
fi

cmake -S "$root/ios" -B "$build" -G Xcode -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_SYSROOT=iphoneos -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DRAW_ISP_VULKAN_INCLUDE_DIR="$moltenvk/include" \
    -DRAW_ISP_VULKAN_LIBRARY="$library" -DSPEKTRACAM_GLSLC="$glslc"

signing=(CODE_SIGNING_ALLOWED=NO)
if [[ -n "${IOS_DEVELOPMENT_TEAM:-}" ]]; then
    signing=(DEVELOPMENT_TEAM="$IOS_DEVELOPMENT_TEAM" CODE_SIGN_STYLE=Automatic)
fi
xcodebuild -project "$build/SpektraCamIOS.xcodeproj" -scheme SpektraCam \
    -configuration Release -destination 'generic/platform=iOS' "${signing[@]}" build
