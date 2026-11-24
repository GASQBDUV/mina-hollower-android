#!/bin/bash
# Build libglibc_compat.so + loadtest with the NDK (target aarch64-linux-android29), then patch libs.
# Run: MSYS_NO_PATHCONV=1 wsl -d Ubuntu bash /mnt/c/Claude/mina-port/android/compat/build.sh
set -e
NDK=${ANDROID_HOME:-$HOME/android-sdk}/ndk/27.3.13750724
BIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
CC=$BIN/aarch64-linux-android29-clang
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT=$HERE/out
mkdir -p "$OUT"
cd "$HERE"
# --no-undefined: every symbol must come from bionic (libc/libm/libdl). compiler-rt builtins
# (__divti3, __gttf2 ...) are NOT needed: libgcc_s.so.1 exports its own and verify.py proves it.
$CC -O2 -Wall -Wextra -Wno-unused-parameter -fPIC -shared -Wl,--no-undefined -Wl,-soname,libglc.so \
    -Wl,-z,max-page-size=16384 -Wl,--build-id=none \
    glibc_compat.c -o $OUT/libglibc_compat.so -lm -ldl -llog
$CC -O2 -Wall -pie loadtest.c -o $OUT/loadtest -ldl
$BIN/llvm-strip --strip-unneeded $OUT/libglibc_compat.so
# rename/patch the glibc libs into out/ (APK-safe names, glibc NEEDED redirected, symbol versions disabled)
python3 "$HERE/patch_libs.py" "$HERE/libs" "$OUT"
ls -l $OUT
