# Status and milestones

Everything below was observed on the date given. "Device" means a Retroid Pocket Duo (Android 16,
Adreno 722, OpenGL ES 3.2). "Emulator" means an Android 36 x86_64 AVD with ARM translation and a host GPU.

## Milestones

| Milestone | Where | Evidence |
|---|---|---|
| machismo builds for bionic as `libmachismo.so`; the guest loads and runs | emulator | `ARCHITECTURE.md` |
| glibc libraries load in a bionic process (0 unresolved symbols, checked offline with `android/compat/verify.py`) | host | `android/compat/README.md` |
| SDL owns the GL context; first frames; shaders compile on ES 3.1 | emulator | `screenshots/01-title-screen.png` |
| Intro, main menu, profile and name entry | emulator | `screenshots/02-profile-name-entry.png` |
| Audio decoder crash found and fixed (missing `bzero`, `pthread_setcanceltype`) | emulator | "Bugs" below |
| PC Steam save loads, world renders | emulator | `screenshots/03-pc-save-loaded-status.png`, `08-feats-menu.png` |
| Player position and area read from game memory | emulator | `reverse-engineering/position-offsets.md` |
| Second-screen world map with marker, area table and location order | emulator | `screenshots/09-map-marker-teleport-test.png` |
| Tile levels decoded, world coordinate scale (0.1) found, room view | emulator | `reverse-engineering/level-to-world.md` |
| All of the above running on a real device | device | `screenshots/04-duo-top-screen-gameplay.png`, `05-duo-bottom-screen-world-map.png` |

## Verified on the device (2026-11-24)

- The APK installs, the game starts and renders at 1920x1080 on the top screen.
- The saved game loads, the player walks in the Ossex hub, and the log shows the active levels
  (`levels/overworld/hub`, `hub_overworld_south`) and the position changing.
- The second display shows the panel, and the world map view puts the marker on Ossex.
- Confirmed by hand afterwards: sound works, the controls work and moving between different areas works.
- Not measured yet on the device: frame rate, battery, long-session stability.

## Verified on the emulator

- About 45 minutes of automated walking, attacking, jumping and menu cycling (`android/tests/walkhunt.py`)
  without a crash. Memory stayed between 1.2 and 1.3 GB. The walker only reached one small pocket of the
  hub, so area loading was not exercised.
- The emulator reached only about 10 fps. That says nothing about the device.

## Map details

- There are 17 map locations plus "none". The order was read from the game's static data (alphabetical by
  sprite name): astralOrrery, bayouA, bayouB, boneBeachA, boneBeachB, cryptA, cryptB, easternFields,
  frozenTrainyard, introBeach, mansionA, mansionB, ossex, septemburgA, septemburgB, southernFields,
  westernFields.
- Three locations were checked in game by writing an area id into the save slot and watching where the
  marker landed: `Crypt_1` to cryptB, `OverworldBoneBeach` to boneBeachA and `FrozenTrainyard_01` to
  frozenTrainyard. This does not move the player; it only changes the value the panel reads. The first guess
  (plain alphabetical order of the location plates) was wrong for four entries and was corrected from the
  static array.
- Runtime world coordinates are the baked level coordinates times 0.1 (a global float set in `main`). In
  the hub class map the player stands on floor tiles at the expected plaza; the art was not compared pixel
  by pixel.

## Bugs that took the longest

- **Audio decoder crash (SIGSEGV in `ycAudio::Decoder`).** Root cause: `bzero`, `pthread_setcanceltype`
  and `pthread_setcancelstate` were missing from the shim and silently stubbed, so a decoder slot was never
  cleared. An earlier theory (broken LSE emulation) was disproved with a load-linked/store-conditional test.
- **`sysconf` numbers.** glibc `_SC_*` values differ from bionic's, so `sysconf(30)` was read as something
  else and broke the page-cache overlay `mmap` (EINVAL). Fixed with a mapping wrapper in the compat layer.
- **CRLF in config files.** Config copied from Windows carried `\r` into library paths and `dlopen` failed
  with confusing names. All config is LF.
- **Shader version.** The shader corpus is GLSL ES 3.10, so the emulator needs `-gpu host`; SwiftShader's
  ES 3.0 is not enough.
- **SDL symbol lookup.** `SDL_CreateWindow` was not found with `dlsym(RTLD_DEFAULT)`. Solved with a
  forwarding shim loaded globally and a fallback lookup in the loader.
- **Stale objects in memory scans.** Scanning for the player's vtable returned freed copies. The tracker now
  validates every candidate and re-checks each tick.
