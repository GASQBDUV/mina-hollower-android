set -e
export ANDROID_HOME=${ANDROID_HOME:-$HOME/android-sdk} ANDROID_SDK_ROOT=${ANDROID_HOME:-$HOME/android-sdk}
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
W=/root/andr/mina; rm -rf $W; mkdir -p $W
cd /mnt/c/Claude/mina-port/apk/android && tar -cf - . | tar -xf - -C $W
cd $W; sed -i 's/\r$//' gradlew; chmod +x gradlew
./gradlew --no-daemon assembleDebug 2>&1 | grep -E "error:|FAILED|BUILD|rror" | head -30
mkdir -p /mnt/c/Claude/mina-port/apk/out
cp app/build/outputs/apk/debug/app-debug.apk /mnt/c/Claude/mina-port/apk/out/Mina-debug.apk
ls -l /mnt/c/Claude/mina-port/apk/out
