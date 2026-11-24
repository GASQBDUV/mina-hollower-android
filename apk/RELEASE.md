# Release build

Default flow is unchanged: `mina-quick.sh` builds the debuggable `out/Mina-debug.apk`, which the deploy
script needs (`run-as`).

Release: `MSYS_NO_PATHCONV=1 wsl -d Ubuntu -u root bash /mnt/c/Claude/mina-port/apk/build_release.sh`
-> `out/Mina-release.apk` (non-debuggable, `zipalign -P 16`, apksigner v2/v3). It packages whatever is in
`app/src/main/jniLibs` (run `build_all.sh` first to refresh native libs).

Signing: the first run generates `keystore/mina-release.jks` and a random password in
`keystore/keystore.properties`. `apk/.gitignore` excludes `keystore/` and `out/`; never commit or publish them.
Back the keystore up: updates must be signed with the same key.

## Manifest
package `com.mina.port`, minSdk 26, targetSdk 28, arm64-v8a only, `extractNativeLibs=true`,
launcher = `SetupActivity` (starts `MinaActivity` at once if `Mina the Hollower.app/.../MinaTheHollower` exists
in `files/gdroot/gamedata` or `/sdcard/Mina/gamedata`; otherwise shows the setup/copy screen).
targetSdk 28 is deliberate (legacy storage + RWX anonymous memory for the guest loader); Play Store would reject it,
and Android 14+ shows a "built for an older version" warning on install.

## Works in release
- First-run setup (SAF folder copy, /sdcard search, save import) and launching the game.
- Game, native libs, second-screen map panel (code identical to debug).

## Does not work / differs
- `adb shell run-as com.mina.port ...` fails (not debuggable): the deploy script (`deploy-to-device.ps1`) cannot push data;
  use the in-app setup instead, or `adb push` to `/sdcard/Mina/gamedata` (needs storage permission).
- No `adb backup`/debugger attach; logcat from the app still works.
- Needs enough free internal storage for the data copy (roughly the game's install size).
- Any machismo behaviour that relied on debuggable flags is untested; release was only build/manifest-verified,
  not run (no emulator available; physical devices off limits).

## 16 KB pages
`python3 apk/check_16kb.py <apk|dir|so>`: all 13 libs pass (PT_LOAD p_align >= 0x4000, 0x10000 for the g-prefixed
compat libs). Link flags `-Wl,-z,max-page-size=16384` already set in build_all.sh, build_sdl.sh,
android/compat/build.sh, android/gfxshim/build.sh and machismo-android/build-android.sh. APK is zipaligned with `-P 16`.
Because `extractNativeLibs=true`, libs are extracted at install and zip alignment is not needed for loading.
Open item (not a loader-alignment issue): `machismo-android/src/loader.h` and `dylib_loader.h` pool allocators align to a
hardcoded 4096; on a 16 KB device per-allocation `mprotect` on such chunks would be rounded to 16 KB. Untested on a
16 KB-page device; every other site uses `sysconf(_SC_PAGESIZE)`.
