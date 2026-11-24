#!/bin/bash
# Release APK: non-debuggable, zipaligned (16 KB, -P 16), signed with a local keystore.
# Run in WSL:  MSYS_NO_PATHCONV=1 wsl -d Ubuntu -u root bash /mnt/c/Claude/mina-port/apk/build_release.sh
# Does NOT rebuild native libs (run build_all.sh first if needed). The debug flow (mina-quick.sh) is unchanged.
set -e
export ANDROID_HOME=${ANDROID_HOME:-$HOME/android-sdk}; export ANDROID_SDK_ROOT=$ANDROID_HOME
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
BT=$ANDROID_HOME/build-tools/35.0.0
A=/mnt/c/Claude/mina-port/apk
KS=$A/keystore/mina-release.jks
PW=$A/keystore/keystore.properties   # git-ignored; contains storePass/keyPass/alias
mkdir -p $A/keystore $A/out
if [ ! -f $KS ]; then
  P=$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)
  keytool -genkeypair -keystore $KS -alias mina -keyalg RSA -keysize 4096 -validity 36500 \
    -storepass "$P" -keypass "$P" -dname "CN=Mina Port, O=Local, C=SE" >/dev/null
  printf 'alias=mina\nstorePass=%s\nkeyPass=%s\n' "$P" "$P" > $PW
  echo "generated new keystore $KS"
fi
STOREPASS=$(grep '^storePass=' $PW | cut -d= -f2-); KEYPASS=$(grep '^keyPass=' $PW | cut -d= -f2-)

python3 $A/check_16kb.py $A/android/app/src/main/jniLibs/arm64-v8a

W=/root/andr/mina-rel; rm -rf $W; mkdir -p $W
cd $A/android && tar -cf - --exclude=build --exclude=.gradle . | tar -xf - -C $W
cd $W; sed -i 's/\r$//' gradlew; chmod +x gradlew
./gradlew --no-daemon assembleRelease 2>&1 | grep -E "error:|FAILED|BUILD|rror" | head -30
U=$(ls app/build/outputs/apk/release/*.apk | head -1)
$BT/zipalign -f -P 16 4 $U $W/aligned.apk
$BT/zipalign -c -P 16 -v 4 $W/aligned.apk | tail -2
$BT/apksigner sign --ks $KS --ks-key-alias mina --ks-pass pass:"$STOREPASS" --key-pass pass:"$KEYPASS" \
  --out $A/out/Mina-release.apk $W/aligned.apk
$BT/apksigner verify --verbose $A/out/Mina-release.apk | head -5
python3 $A/check_16kb.py $A/out/Mina-release.apk
ls -l $A/out
