set -e
cd /data/roms/ports/mina
G=gamedata
PAK="$G/Mina the Hollower.app/Contents/Resources/data/shaders.pak.yc"
T=/dev/shm/mina_patch
rm -rf $T; mkdir -p $T
./tools/ycd_extract "$PAK" $T/metallibs
echo "metallibs: $(ls $T/metallibs/*.metallib | wc -l)"
./tools/airlift build $T/metallibs $T/shaders_gles --spv-out $T/shaders_spv
echo "glsl: $(ls $T/shaders_gles/*.glsl | wc -l) spv: $(ls $T/shaders_spv/*.spv | wc -l)"
rm -rf $G/shaders_gles $G/shaders_spv
cp -r $T/shaders_gles $G/shaders_gles; cp -r $T/shaders_spv $G/shaders_spv
cat patch/corpus.version > $G/.shaders_ready
rm -rf $T
echo shaders-done
