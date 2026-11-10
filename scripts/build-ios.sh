#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
glslc="${SPEKTRACAM_GLSLC:-$sdk/ndk/26.1.10909125/shader-tools/darwin-x86_64/glslc}"
sysroot=iphoneos
destination='generic/platform=iOS'
build_default="$root/build/ios"
deps="$root/build/deps"
moltenvk_base="$deps"
package=MoltenVK-ios
slice=ios-arm64
version=1.4.2
checksum=b5d947b1660e6e9fed40b9cd2387e160aaab9e80b775c0cef7e14059405178c1

if [[ "${1:-}" == --simulator ]]; then
    if [[ -n "${SPEKTRACAM_IOS_ARCHIVE_PATH:-}" ]]; then
        echo "App Store archives require a device build." >&2
        exit 1
    fi
    sysroot=iphonesimulator
    destination='generic/platform=iOS Simulator'
    build_default="$root/build/ios-simulator"
    moltenvk_base="$deps/simulator"
    package=MoltenVK-all
    slice=ios-arm64_x86_64-simulator
    checksum=562a15a29bc358446a56a4091c5f7e08f604184187c1d34f712148b61ef17276
    shift
fi
if [[ $# != 0 ]]; then
    echo "Usage: $0 [--simulator]" >&2
    exit 1
fi

build="${SPEKTRACAM_IOS_BUILD_DIR:-$build_default}"
moltenvk="${MOLTENVK_ROOT:-$moltenvk_base/MoltenVK/MoltenVK}"
library="$moltenvk/static/MoltenVK.xcframework/$slice/libMoltenVK.a"

if [[ ! -x "$glslc" ]]; then
    echo "Set SPEKTRACAM_GLSLC to an executable glslc." >&2
    exit 1
fi
if [[ -z "${MOLTENVK_ROOT:-}" && ( ! -f "$library" || ! -f "$moltenvk/include/vulkan/vulkan.h" ) ]]; then
    archive="$deps/$package-$version.tar"
    mkdir -p "$deps"
    if [[ ! -f "$archive" ]]; then
        curl --fail --location --retry 3 \
            "https://github.com/KhronosGroup/MoltenVK/releases/download/v$version/$package.tar" \
            --output "$archive.part"
        mv "$archive.part" "$archive"
    fi
    printf '%s  %s\n' "$checksum" "$archive" | shasum -a 256 --check
    mkdir -p "$moltenvk_base"
    tar -xf "$archive" -C "$moltenvk_base"
fi
if [[ ! -f "$library" || ! -f "$moltenvk/include/vulkan/vulkan.h" ]]; then
    echo "MOLTENVK_ROOT must contain the $slice library and Vulkan headers." >&2
    exit 1
fi

app_store=OFF
action=(build)
if [[ -n "${SPEKTRACAM_IOS_ARCHIVE_PATH:-}" ]]; then
    app_store=ON
    action=(-archivePath "$SPEKTRACAM_IOS_ARCHIVE_PATH" archive)
fi

cmake -S "$root/ios" -B "$build" -G Xcode -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_SYSROOT="$sysroot" -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DSPEKTRACAM_APP_STORE="$app_store" \
    -DRAW_ISP_VULKAN_INCLUDE_DIR="$moltenvk/include" \
    -DRAW_ISP_VULKAN_LIBRARY="$library" -DSPEKTRACAM_GLSLC="$glslc"

signing=(CODE_SIGNING_ALLOWED=NO)
if [[ "$sysroot" == iphoneos && -n "${IOS_DEVELOPMENT_TEAM:-}" ]]; then
    signing=(DEVELOPMENT_TEAM="$IOS_DEVELOPMENT_TEAM" CODE_SIGN_STYLE=Automatic)
fi
xcodebuild -project "$build/SpektraCamIOS.xcodeproj" -scheme SpektraCam \
    -configuration Release -destination "$destination" "${signing[@]}" "${action[@]}"
