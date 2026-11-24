set -e
NDK=/home/gasqbduv/android-sdk/ndk/27.3.13750724
CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang
H=/mnt/c/Claude/mina-port
J=$H/apk/android/app/src/main/jniLibs/arm64-v8a
mkdir -p $J
$CC -O2 -fPIC -shared -Wl,-z,max-page-size=16384 -I$H/apk/sdl/include $H/apk/native/libmain.c -o $J/libmain.so -llog -ldl
cp $H/apk/sdl/libSDL2.so $J/
M=$H/machismo-android/build-android
cp $M/libmachismo.so $M/libsystem_shim.so $M/libmachismo_unwind.so $J/
C=$H/android/compat/out
cp $C/libglibc_compat.so $C/libgcc_sg.so $C/libstdcxxg.so $C/libcxxabig.so $C/libatomicg.so $C/libcxxg.so $C/libgothic_patches.so $J/

cp /mnt/c/Claude/mina-port/android/gfxshim/out/apk/libgfxshim_sdl.so $J/
ls $J | wc -l
