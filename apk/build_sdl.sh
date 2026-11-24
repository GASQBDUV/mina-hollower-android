set -e
NDK=${ANDROID_HOME:-$HOME/android-sdk}/ndk/27.3.13750724
CM=$(ls -d ${ANDROID_HOME:-$HOME/android-sdk}/cmake/*/bin | head -1)
rm -rf /root/sdlb; mkdir -p /root/sdlb/src; tar xzf /mnt/c/Claude/sdl32/SDL2-2.32.10.tar.gz -C /root/sdlb/src
cd /root/sdlb
$CM/cmake -S src/SDL2-2.32.10 -B b -G Ninja -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release -DSDL_STATIC=OFF -DSDL_SHARED=ON -DSDL_TEST=OFF -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" -DCMAKE_MAKE_PROGRAM=$CM/ninja >/dev/null
$CM/cmake --build b 2>&1 | tail -3
mkdir -p /mnt/c/Claude/mina-port/apk/sdl; cp b/libSDL2.so /mnt/c/Claude/mina-port/apk/sdl/
cp -r src/SDL2-2.32.10/android-project/app/src/main/java /mnt/c/Claude/mina-port/apk/sdl/java
cp -r src/SDL2-2.32.10/include /mnt/c/Claude/mina-port/apk/sdl/include
ls -l /mnt/c/Claude/mina-port/apk/sdl
