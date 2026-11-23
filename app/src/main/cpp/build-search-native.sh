#!/usr/bin/env bash
# 编译频道搜索的原生驱动 libsqlitejni.so（只出 armeabi-v7a）。
#
# 为什么不用 Gradle 的 externalNativeBuild：
#   AGP 8.x 的 CmakeOptions 既不给 `arguments = [...]` 属性，也没有 `arguments(String)`
#   方法，无法传 -DANDROID_PLATFORM；而本工程 arm32 的 minSdk 是 14，低于 NDK r26
#   的下限 21，必须显式指定 ANDROID_PLATFORM=android-21。
#   所以改成在 Gradle 之外单独编译，产物直接落到 jniLibs —— 和工程里其它预编译 so
#   一样被正常打包。好处是 AGP 版本变化、本机有没有装 NDK，都不会影响工程能否构建。
#
# 用法：
#   app/src/main/cpp/build-search-native.sh              # 自动找 NDK
#   ANDROID_NDK_HOME=/path/to/ndk/26.1.10909125 app/src/main/cpp/build-search-native.sh
#   ANDROID_ABI=armeabi-v7a ANDROID_PLATFORM=android-21 app/src/main/cpp/build-search-native.sh
#
# Android Studio 里要带上搜索功能，先跑一次本脚本即可（so 会留在 jniLibs 里被一起打包）。
set -euo pipefail

# 脚本就在 app/src/main/cpp 下，往上三级是 :app 模块目录
APP_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SRC="$APP_DIR/src/main/cpp"
OUT="$APP_DIR/build/search-native"
ABI="${ANDROID_ABI:-armeabi-v7a}"
PLATFORM="${ANDROID_PLATFORM:-android-21}"

find_ndk() {
  if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
    echo "$ANDROID_NDK_HOME"; return
  fi
  if [ -n "${ANDROID_NDK_ROOT:-}" ] && [ -d "$ANDROID_NDK_ROOT" ]; then
    echo "$ANDROID_NDK_ROOT"; return
  fi
  for base in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [ -n "$base" ] && [ -d "$base/ndk" ] || continue
    # 优先用 r26 及以上（NDK 低版本没有 platforms.json，路径约定也不同）
    for candidate in "$base"/ndk/26* "$base"/ndk/27* "$base"/ndk/28*; do
      [ -d "$candidate" ] && { echo "$candidate"; return; }
    done
  done
}

find_cmake() {
  for base in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [ -n "$base" ] && [ -x "$base/cmake/3.22.1/bin/cmake" ] && { echo "$base/cmake/3.22.1/bin/cmake"; return; }
  done
  command -v cmake
}

NDK="$(find_ndk || true)"
if [ -z "$NDK" ] || [ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]; then
  echo "找不到 Android NDK（需要 r26 及以上）。" >&2
  echo "请安装后重试，或用 ANDROID_NDK_HOME 指向 NDK 目录。" >&2
  exit 1
fi
CMAKE="$(find_cmake || true)"
if [ -z "$CMAKE" ]; then
  echo "找不到 cmake（需要 3.22 以上）。" >&2
  exit 1
fi

echo "NDK     : $NDK"
echo "CMake   : $($CMAKE --version | head -n 1)"
echo "ABI     : $ABI"
echo "Platform: $PLATFORM"

"$CMAKE" -S "$SRC" -B "$OUT" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" \
  -DANDROID_PLATFORM="$PLATFORM" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$OUT/out" \
  -DJNI_CLASS_NAME=xiao_bu_tv_SQLiteEngine

"$CMAKE" --build "$OUT" -j "$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"

DEST="$APP_DIR/src/main/libs/$ABI"
mkdir -p "$DEST"
cp "$OUT/out/libsqlitejni.so" "$DEST/libsqlitejni.so"
echo "已生成: $DEST/libsqlitejni.so"
ls -la "$DEST/libsqlitejni.so"
