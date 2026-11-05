#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDCPP_SOURCE_DIR="${SDCPP_SOURCE_DIR:-/mnt/ssd/lpa-ondevice-inpaint/stable-diffusion.cpp}"
SDCPP_COMMIT="58bb3c1b1cd4749e94e39f537e9adfce295dcaee"
NDK_ROOT="${ANDROID_NDK_ROOT:-/home/jiangrui/android-sdk/ndk/28.2.13676358}"
SPIRV_HEADERS="${SPIRV_HEADERS:-/mnt/ssd/lpa-ondevice-inpaint/deps/SPIRV-Headers/install}"
VULKAN_HEADERS="${VULKAN_HEADERS:-/mnt/ssd/lpa-ondevice-inpaint/deps/Vulkan-Headers/include}"
BUILD_DIR="${SDCPP_JNI_BUILD_DIR:-/mnt/ssd/lpa-ondevice-inpaint/build-lpa-sdcpp-jni}"
OUTPUT_DIR="$ROOT/app/src/onDeviceGeneration/jniLibs/arm64-v8a"

test "$(git -C "$SDCPP_SOURCE_DIR" rev-parse HEAD)" = "$SDCPP_COMMIT"
test -f "$SPIRV_HEADERS/share/cmake/SPIRV-Headers/SPIRV-HeadersConfig.cmake"
test -f "$VULKAN_HEADERS/vulkan/vulkan.hpp"

cmake -S "$ROOT/tools/sdcpp-jni" -B "$BUILD_DIR" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$NDK_ROOT/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 \
  -DANDROID_STL=c++_static \
  -DSDCPP_SOURCE_DIR="$SDCPP_SOURCE_DIR" \
  -DSPIRV-Headers_DIR="$SPIRV_HEADERS/share/cmake/SPIRV-Headers" \
  -DVulkan_INCLUDE_DIR="$VULKAN_HEADERS" \
  -DVulkan_LIBRARY="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/28/libvulkan.so" \
  -DVulkan_GLSLC_EXECUTABLE="$NDK_ROOT/shader-tools/linux-x86_64/glslc" \
  -DCMAKE_CXX_FLAGS="-I$SPIRV_HEADERS/include"

cmake --build "$BUILD_DIR" --target lpa_sdcpp -j"$(nproc)"
mkdir -p "$OUTPUT_DIR"
cp "$BUILD_DIR/liblpa_sdcpp.so" "$OUTPUT_DIR/liblpa_sdcpp.so"
"$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" "$OUTPUT_DIR/liblpa_sdcpp.so"
sha256sum "$OUTPUT_DIR/liblpa_sdcpp.so"
