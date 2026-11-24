#!/bin/bash
# Android (bionic, aarch64) cross-build of machismo as a library + CLI test exe.
#   MSYS_NO_PATHCONV=1 wsl -d Ubuntu bash /mnt/c/Claude/mina-port/machismo-android/build-android.sh
# Outputs (build-android/):
#   libmachismo.so        loader library, exports machismo_main()/machismo_main_bigstack()
#   libsystem_shim.so     libSystem replacement for the guest
#   machismo              CLI test exe (links libmachismo.so, rpath $ORIGIN)
#   libmachismo_unwind.so optional: LLVM libunwind with exported _Unwind_*/__register_frame
set -u
cd "$(dirname "$(readlink -f "$0")")"
NDK=${NDK:-${ANDROID_HOME:-$HOME/android-sdk}/ndk/27.3.13750724}
API=${API:-29}
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
CC=$TC/aarch64-linux-android$API-clang
OUT=build-android; mkdir -p $OUT/obj
# 16 KB ELF segment alignment: loads on 4 KB and 16 KB page-size kernels.
LDPG="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
CF="-Wall -O2 -g -fPIC -fno-omit-frame-pointer -D_GNU_SOURCE -DINSTALL_PREFIX=\"\" -DSYSTEM_ROOT=\"\" -Isrc"
# KMS/DRM splash is stubbed out (no HAVE_LIBDRM); kmsdrm paths are #ifdef __ANDROID__ no-ops.
LIBSRCS="src/machismo.c src/machismo_lib.c src/libpath.c src/commpage.c src/resolver.c src/trampoline.c src/gdb_jit.c src/heaptrack_trace.c src/crash_handler.c src/patcher.c src/config.c src/bgfx_shim.c src/fps.c src/sdl_window_shim.c src/splash_kms.c src/eh_frame.c src/dylib_loader.c src/isa_emul.c src/lua_entity_opt.c src/elfcalls/elfcalls.c src/elfcalls/threads.c"
rc=0
objs=""
for s in $LIBSRCS; do
  o=$OUT/obj/$(echo $s | tr / _).o
  $CC $CF -c $s -o $o 2>$OUT/$(basename $s).log || { echo "FAIL $s"; rc=1; }
  objs="$objs $o"
done
[ $rc = 0 ] || { echo "compile errors, see $OUT/*.log"; exit 1; }

# libmachismo.so: static libc++abi only for __cxa_demangle (heaptrack); --exclude-libs keeps
# its symbols (and the bundled libunwind) hidden so the guest never binds to them.
$CC $objs -shared -o $OUT/libmachismo.so -Wl,-soname,libmachismo.so $LDPG -Wl,-z,defs \
    -Wl,--exclude-libs,ALL -static-libstdc++ -lc++_static -lc++abi -ldl -lm 2>$OUT/link.log \
  || { echo LINKFAIL libmachismo; head -40 $OUT/link.log; rc=1; }

# CLI test exe (same behaviour as the old main()).
$CC $CF -c src/machismo_cli.c -o $OUT/obj/machismo_cli.o 2>>$OUT/link.log
$CC $OUT/obj/machismo_cli.o -o $OUT/machismo -L$OUT -lmachismo -Wl,-rpath,'$ORIGIN' $LDPG 2>>$OUT/link.log \
  || { echo LINKFAIL machismo; tail -20 $OUT/link.log; rc=1; }

# libsystem_shim.so
$CC $CF -shared src/shim/libsystem_shim.c -o $OUT/libsystem_shim.so -Wl,-soname,libsystem_shim.so $LDPG \
    -Wl,--version-script=src/shim/libsystem_shim.ver -lm -ldl 2>$OUT/shim.log || { echo "FAIL shim"; rc=1; }

# Optional: libmachismo_unwind.so. The NDK's libunwind.a is built with hidden visibility, so it cannot be
# turned into a shared unwinder directly. We rename its hidden global functions (prefix mu_) and add
# default-visibility tail-call wrappers under the original names. See ANDROID-PORT-NOTES.md (EH section).
UA=$NDK/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/18/lib/linux/aarch64/libunwind.a
if [ -f "$UA" ]; then
  W=$OUT/unwind; mkdir -p $W
  $TC/llvm-readelf -sW "$UA" | awk '$4=="FUNC" && $5=="GLOBAL" && $6=="HIDDEN" && $7!="UND" {print $8}' | sort -u > $W/syms.txt
  : > $W/redefine.txt; : > $W/wrap.S
  echo '.text' > $W/wrap.S
  while read -r n; do
    echo "$n mu$n" >> $W/redefine.txt
    printf '.globl %s\n.type %s,%%function\n%s:\n\tb mu%s\n.size %s,.-%s\n' "$n" "$n" "$n" "$n" "$n" "$n" >> $W/wrap.S
  done < $W/syms.txt
  $TC/llvm-objcopy --redefine-syms=$W/redefine.txt "$UA" $W/libunwind_mu.a \
  && $CC -shared -o $OUT/libmachismo_unwind.so -Wl,-soname,libmachismo_unwind.so $LDPG \
        $W/wrap.S -Wl,--whole-archive $W/libunwind_mu.a -Wl,--no-whole-archive -Wl,--exclude-libs,ALL -nostdlib++ -ldl 2>$W/link.log \
  || { echo "WARN: libmachismo_unwind.so not built (optional), see $W/link.log"; }
fi

ls -la $OUT/*.so $OUT/machismo
exit $rc
