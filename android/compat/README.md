# glibc-on-bionic compat layer (aarch64, API 29+)

Loads prebuilt glibc-aarch64 libs (`libgothic_patches.so`, `libc++.so.1`, `libc++abi.so.1`,
`libatomic.so.1`, Ubuntu 24.04 `libstdc++.so.6` + `libgcc_s.so.1`) inside an Android process.

## Files
| file | purpose |
|---|---|
| `glibc_compat.c` | ~80 exported glibc-only symbols, each a thin wrapper over bionic -> `libglibc_compat.so` (SONAME `libglc.so`) |
| `patch_libs.py` | renames SONAME/NEEDED to APK-safe names, redirects glibc NEEDED to `libglc.so`, disables symbol versioning |
| `build.sh` | builds compat + loadtest (NDK r27, `aarch64-linux-android29`, `-Wl,--no-undefined`) and runs patch_libs.py -> `out/` |
| `verify.py` | llvm-nm proof that all undefined symbols resolve (run in WSL: `python3 verify.py`) |
| `loadtest.c` | dlopen chain test (built to `out/loadtest`) |
| `libs/` | original glibc libs (libstdc++/libgcc_s copied from the Ubuntu 24.04 arm64 rootfs) |

Build: `MSYS_NO_PATHCONV=1 wsl -d Ubuntu bash /mnt/c/Claude/mina-port/android/compat/build.sh`

## Result of verify.py (done, offline)
Every undefined strong symbol of all 6 libs is provided by {NDK 29 stubs: libc, libm, libdl, liblog, libEGL,
libGLESv2, libGLESv3, libandroid, libz} + {the other bundled libs} + compat: **0 unresolved**.
Remaining weak-undefined (resolve to NULL, harmless): `_ZGTtdlPv _ZGTtnam _ITM_RU1 _ITM_RU8 _ITM_addUserCommitAction
_ITM_memcpyRnWt _ITM_memcpyRtWn _ZN9__gnu_cxx21zoneinfo_dir_overrideEv` (libstdc++ TM / tzdata hooks).
Not needed: compiler-rt builtins (`__divti3/__modti3/__gttf2/__lttf2/__trunctfdf2` are exported by libgcc_s.so.1 itself).
`mmap_registry_add` is no glibc symbol: it is defined by `libsystem_shim.so` (not in the chain). compat provides a
**weak no-op** fallback; if the real shim is loaded RTLD_GLOBAL first its strong definition wins. Signature unknown
-> check what it does before relying on the stub.

## Patched layout (out/)
`libglibc_compat.so`(SONAME libglc.so), `libgcc_sg.so`, `libstdcxxg.so`, `libcxxabig.so`, `libcxxg.so`,
`libatomicg.so`, `libgothic_patches.so`. NEEDED: libc.so.6/libpthread.so.0/librt.so.1/ld-linux-aarch64.so.1 -> `libglc.so`;
libm.so.6 -> libm.so; libdl.so.2 -> libdl.so; libEGL.so.1 -> libEGL.so; libGLESv2.so.2 -> libGLESv2.so.
The compat's SONAME is 9 chars on purpose (patching is in place and cannot lengthen strings). Name collisions with the
APK's own libs: none. In the APK put all of them in `lib/arm64-v8a/` (only `lib*.so` are extracted; versioned names
like `libstdc++.so.6` are NOT, which is why they are renamed). The original names no longer exist anywhere, so any
`dlopen("libstdc++.so.6")` in app code must be changed to `libstdcxxg.so`.

## Running loadtest (NOT run yet; do it manually, device untouched so far)
```
adb shell mkdir -p /data/local/tmp/glc
adb push out/. /data/local/tmp/glc/            # all libs + loadtest
adb shell chmod +x /data/local/tmp/glc/loadtest
adb shell 'cd /data/local/tmp/glc && LD_LIBRARY_PATH=/data/local/tmp/glc ./loadtest /data/local/tmp/glc'
```
Expected: 7x `OK`, `ctype_b['A']=0x5d08 (expect 0x5d08)`, `ALL LOADED (0 failures)`. On FAIL read dlerror(): `cannot locate symbol "X"`
= missing in compat (add to glibc_compat.c, rerun verify.py); `library "libX" not found` = NEEDED not patched/missing
file; `unsupported relocation`/`TLSDESC` -> see risks. From Java/app code: dlopen compat first by absolute path
(RTLD_GLOBAL not required thanks to NEEDED, but harmless), then the rest in the same order.

## Symbol versions
bionic's linker (linker_soinfo.cpp) does honour versions: `gnu_lookup` calls `find_verdef_version_index(this, vi)` and
`check_symbol_version(versym, n, verneed)`; a requester needing e.g. `memcpy@GLIBC_2.17` against a provider whose
verdef set lacks that version gets `verneed = kVersymGlobal` and then only *unversioned-global* symbols match.
bionic's real libc.so/libm.so carry their own verdefs (LIBC, ...), so a `GLIBC_2.17` requirement is at best
unreliable. Therefore patch_libs.py disables DT_VERSYM/DT_VERDEF/DT_VERDEFNUM/DT_VERNEED/DT_VERNEEDNUM in all glibc libs
(tags rewritten to the ignored DT_RELACOUNT with value 0; the genuine RELACOUNT hint stays last and is ignored by bionic).
Without versym, bionic's `check_symbol_version` returns true (`ver_table == nullptr`) -> plain name matching.
(I could not test this on a device; the source evidence is a web read of AOSP main, so confirm with loadtest.)
Side effect: libstdc++ exports some symbols in several versions under one name (e.g. `GLIBCXX_3.4` vs `3.4.x`
duplicates of old/new std::string/locale ABI, `_ZNSt...@GLIBCXX_3.4.x`); first one in the hash chain wins. Mostly
identical implementations, but an old-vs-new ABI mismatch is possible (see risks).

## Risks / open points
1. **TLSDESC relocations**: libgcc_s (1) and libstdc++ (3) contain `R_AARCH64_TLSDESC` (exception globals). bionic's
   linker supports TLSDESC on arm64 only from recent Android (about Android 14/API 34; Android 16 on the Duo is fine);
   on API 29-33 `dlopen` fails with unsupported relocation. Workaround if needed: relink libs with `-mtls-dialect=trad`
   (needs sources) or patch the 4 descriptor sites. All other relocs (RELATIVE/ABS64/GLOB_DAT/JUMP_SLOT) are fine.
2. **Two C++ runtimes**: Android's `libc++_shared.so` (std::__ndk1) and glibc libstdc++ (std::) coexist, namespaces differ
   so no type clash, BUT the unmangled/C-linkage and ABI-level symbols collide: `__cxa_*`, `__gxx_personality_v0`,
   `_Unwind_*`, `operator new/delete`, `__cxa_throw`. Loaded with local scope, glibc libs resolve them against libgcc_s/
   libstdcxxg first (own NEEDED order) but `libcxxabig.so` also exports `__cxa_*` + `__gxx_personality_v0`, and the
   already-global bionic libc++ / system libunwind may win in the global group. Do not RTLD_GLOBAL-load libstdcxxg/libcxxabig/libcxxg.
   Exceptions must not cross between the glibc world and the app's NDK libc++ world. Do not load libc++.so.1 (glibc LLVM libc++)
   and libstdc++ in one consumer: the game lib uses libstdc++ only (libc++/libc++abi are for other libs).
3. **Data-structure ABI**: glibc `pthread_mutex_t` is 48 B, bionic 40 B (glibc-built code allocates larger -> OK);
   statically-initialised *recursive/errorcheck* glibc mutexes (`PTHREAD_RECURSIVE_MUTEX_INITIALIZER_NP`) are misread
   by bionic; `sem_t` 32 vs 16 B; `FILE` is opaque (fine), `struct stat` identical on aarch64, `jmp_buf` bigger in glibc (fine).
   `stdin/stdout/stderr` bind to bionic's FILE* variables (OK from API 23).
4. **ctype**: tables cover the C locale only (ASCII classes), built lazily with pthread_once. `__newlocale` masks LC_ALL_MASK
   down to bionic's 6 categories; bionic only knows the C/POSIX (and C.UTF-8) locales -> `std::locale("en_US.UTF-8")` throws.
   `__wcsftime_l` ignores the locale. gettext = identity.
5. **TLS / destructors / dlclose**: `__cxa_thread_atexit_impl` is bionic's; TLS dtors of the glibc libs run at thread exit
   via bionic. **Never dlclose** these libs (libstdc++ registers atexit/TLS dtors and static-init guards pointing into
   itself; unwinder registrations in libgcc_s; our `__libc_single_threaded = 0` is fine but static state in compat
   (ctype tables) would dangle). Keep them loaded for process lifetime. `_dl_find_object` returns -1 so libgcc_s
   uses `dl_iterate_phdr` (works; slower unwinding, needs `PT_GNU_EH_FRAME`, present).
6. **Namespaces**: app libs from the APK are in the app's classloader namespace; /data/local/tmp from adb shell is fine for
   the test only. Within the app, load by absolute path from `nativeLibraryDir` (or System.loadLibrary of each in order)
   so that all libs end up in the same namespace; system libs (libc/libm/libdl/libEGL/libGLESv2) are reachable from it.
   `libglc.so` NEEDED is satisfied by SONAME match only if the compat was loaded earlier (or lives in the same lib dir).
7. **API behavior differences** hidden behind same-named calls: glibc `qsort`, `printf("%n")`, `getenv` locale, `malloc_usable_size`
   are bionic's; `fopen64/stat64/lseek64` names exist in bionic. Not hit by this chain, but new glibc functions in
   later builds will show up in verify.py.
8. **Ubuntu 24.04 libstdc++ (GLIBCXX_3.4.33)**: needs 32 glibc-only symbols from compat (locale `_l` family, `__*_chk`,
   gettext, `__isoc23_strtoul`). An older libstdc++ (Debian 11) was NOT evaluated or downloaded; it would mainly drop the
   `__isoc23_*` import. Since compat already covers everything, the gain is small; revisit only if C++ ABI issues appear.
9. `libsystem_shim.so` (macOS shim, exports malloc/calloc/free/dlopen/fcntl/dup3 ...) is deliberately NOT part of the chain;
   loading it RTLD_GLOBAL would interpose bionic's malloc for the whole process.
