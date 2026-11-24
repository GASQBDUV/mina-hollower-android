#!/bin/bash
# Build the Android graphics shims for libgothic_patches.so (compile only; nothing is run).
#   MSYS_NO_PATHCONV=1 wsl -d Ubuntu bash /mnt/c/Claude/mina-port/android/gfxshim/build.sh
set -e
NDK=${NDK:-${ANDROID_HOME:-$HOME/android-sdk}/ndk/27.3.13750724}
BIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
CC=$BIN/aarch64-linux-android29-clang
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-$HERE/out}
mkdir -p "$OUT/apk"
CFLAGS="-O2 -fPIC -fvisibility=hidden -Wall -Wextra -Wno-unused-parameter"
LDFLAGS="-shared -Wl,--no-as-needed -Wl,-z,max-page-size=16384 -Wl,--build-id=none"

# Files named after their SONAME (for adb / LD_LIBRARY_PATH tests) ...
$CC $CFLAGS $LDFLAGS -Wl,-soname,libgfxshim_sdl.so  "$HERE/sdl_shim.c"          -ldl            -o "$OUT/libgfxshim_sdl.so"
$CC $CFLAGS $LDFLAGS -Wl,-soname,libEGL.so.1        "$HERE/egl_interposer.c"    -ldl -lEGL      -o "$OUT/libEGL.so.1"
$CC $CFLAGS $LDFLAGS -Wl,-soname,libwayland-egl.so.1 "$HERE/wayland_egl_stub.c" -landroid       -o "$OUT/libwayland-egl.so.1"

# ... and empty "name carriers": a library with SONAME X whose only content is DT_NEEDED on the
# real Android library. libgothic's DT_NEEDED/dlopen of X is satisfied, and symbol lookup through
# the dependency tree reaches the real library. (No forwarding code needed.)
echo 'int gfxshim_empty_marker;' > "$OUT/empty.c"
$CC $CFLAGS $LDFLAGS -Wl,-soname,libGLESv2.so.2 "$OUT/empty.c" -lGLESv2 -o "$OUT/libGLESv2.so.2"
$CC $CFLAGS $LDFLAGS -Wl,-soname,libvulkan.so.1 "$OUT/empty.c" -lvulkan -o "$OUT/libvulkan.so.1"
rm -f "$OUT/empty.c"

# APK-packaging copies: the package manager only extracts lib*.so, so ship them under
# *.so names and dlopen them by path in this order BEFORE libgothic_patches.so
# (the linker then satisfies DT_NEEDED "libEGL.so.1" etc. by SONAME of the already loaded lib).
cp "$OUT/libgfxshim_sdl.so"     "$OUT/apk/libgfxshim_sdl.so"
cp "$OUT/libEGL.so.1"           "$OUT/apk/libgfxshim_egl.so"
cp "$OUT/libGLESv2.so.2"        "$OUT/apk/libgfxshim_gles.so"
cp "$OUT/libvulkan.so.1"        "$OUT/apk/libgfxshim_vk.so"
cp "$OUT/libwayland-egl.so.1"   "$OUT/apk/libgfxshim_wlegl.so"

echo "== built =="
for f in "$OUT"/lib*; do
  [ -f "$f" ] || continue
  echo "-- $(basename "$f")"
  $BIN/llvm-readobj --dynamic "$f" | grep -E "SONAME|NEEDED" | sed 's/^ *0x[0-9A-F]* */   /'
  $BIN/llvm-nm -D --defined-only "$f" | awk '{print "   export " $3}'
done
