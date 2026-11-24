# Architecture

## Process layout

```
MinaActivity (SDLActivity subclass)         Java: copies config, creates the second-screen Presentation
  libSDL2.so                                 window, input, GL surface
  libmain.so  SDL_main                       sets the environment, loads the chain below, runs the guest
    libglibc_compat.so (SONAME libglc.so)    glibc-only symbols implemented over bionic
    libgcc_sg / libstdcxxg / libcxxabig /    renamed glibc C++ runtime libraries
    libatomicg / libcxxg
    libgfxshim_sdl.so                        forwards SDL_* symbols that the game looks up by name
    libsystem_shim.so                        libSystem replacement for the guest (incl. bzero etc.)
    libmachismo.so                           the Mach-O loader as a library
    libgothic_patches.so                     glibc engine patches (from the PortMaster port)
    libmachismo_unwind.so                    LLVM libunwind with exported __register_frame
  guest: MinaTheHollower (macOS arm64)       runs on a 64 MiB pthread via machismo_main_bigstack
```

`libmain.c` also starts the tracker thread and exposes it to Java through `nativeState`, `nativeMapFlags`
and `nativeLevels`.

## Loader (machismo-android)

- Segments are `pread` into anonymous memory at the Mach-O's fixed address (`MAP_FIXED_NOREPLACE`,
  0x100000000), because Android's linker does not let us map the file directly.
- `libpath.c` resolves the game's dylibs against an app directory with an alias list
  (`MACHISMO_LIB_ALIAS`) and `MACHISMO_LIB_DIR`.
- The guest uses ARMv8.1 atomics. On CPUs without them, LDXR/STXR "patch islands" are generated; on CPUs
  with LSE the patching is skipped (hwcap check).
- Synthetic `eh_frame` FDEs for the guest are registered through `libgcc_sg.so` so that C++ exceptions
  unwind.
- `targetSdk 28` keeps SELinux's `untrusted_app_27` domain, which permits anonymous RWX memory.

## Compatibility layer (`android/compat`)

`glibc_compat.c` exports about 80 glibc-only symbols as thin wrappers, including `mmap` and `sysconf`
wrappers (glibc constants are mapped to bionic's). `patch_libs.py` rewrites SONAME and NEEDED strings in
place (Android only extracts `lib*.so` and matches exact names), turns `STB_GNU_UNIQUE` into `GLOBAL` and
disables symbol versioning. `verify.py` proves offline that every undefined symbol resolves.

## Graphics

The engine takes the "SDL owns the GL context" path when SDL reports an Android window. The GLES backend
needs ES 3.1 or newer. `libgfxshim_sdl.so` and the interposers in `android/gfxshim` keep the engine's own
EGL and Wayland assumptions out of the way. Shaders come from the game's data (`shaders.pak.yc`) and are
converted offline (Metal AIR to SPIR-V to GLES).

## Tracker and map panel

1. `find_all(vtable)` scans readable anonymous mappings through `/proc/self/mem` for the `Mina` object
   (vtable value `0x100dfe6d0`), skipping its own stack and buffer.
2. Candidates are validated (plausible entity pointer, entity vtable inside the binary's range, finite
   floats). The tracker re-validates every 100 ms and backs off on rescans.
3. The position is at `entity + 0xDC` (x, y, z). The area id is `*(u32*)(saveSlot + 584)`, a hash of the area
   name (Jenkins `hashlittle`, seed 1337). The area table (198 entries, stride 136) gives the map location.
4. The active level names are read from the `WorldRegion` objects (`+0x1c8`) and select the level image.
5. `MapView` renders the chosen view on the second display.

All offsets belong to the analysed macOS binary and will differ in other versions of the game.
