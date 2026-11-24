# machismo -> Android (bionic, aarch64) som bibliotek - portanteckningar

Källa: kopia av machismo (GPL-3.0). Originalet i C:\Claude\mina-port\machismo är orört. Läget före biblioteksomarbetet
finns i C:\Claude\mina-port\machismo-android-prelib-backup\. **Inget har körts på enhet** (inget adb); allt nedan är
kompilerat/länkat med NDK 27.3 (aarch64-linux-android29) och statiskt granskat. Enda körda test: host-test (x86-64) av
`machismo_mem.h` (reservation/NOREPLACE/16K-justering/pread-laddning).

Bygg: `MSYS_NO_PATHCONV=1 wsl -d Ubuntu bash /mnt/c/Claude/mina-port/machismo-android/build-android.sh`
Resultat i build-android/:
- `libmachismo.so` (1,0 MB) - laddaren som bibliotek. Exporterar `machismo_main`, `machismo_main_bigstack` m.fl. Länkad `-z defs`
  (inga olösta symboler) + `--exclude-libs,ALL` (statiska libc++abi/libunwind exporteras inte) + 16 KB ELF-alignment.
- `libsystem_shim.so` - libSystem-ersättning för gästen.
- `machismo` - CLI-testexe (7 KB, `machismo_cli.c`), länkar libmachismo.so, rpath `$ORIGIN`. Beteende som gamla main()
  (avslutar med `_exit(rc)`).
- `libmachismo_unwind.so` (valfri, se 4) - LLVM libunwind med exporterade `_Unwind_*`/`__register_frame`.

## 1. Biblioteks-API (src/machismo_lib.h)
- `int machismo_main(int argc, char** argv)`: samma som CLI (`[-v] <mach-o> [args...]`, argv[0] = programnamn). Returnerar gästens
  exit-kod i stället för att avsluta processen. Alla `exit(n)` i loader.c/stack.c/machismo.c går via `machismo_exit()` som
  longjmp:ar tillbaka till `machismo_main` på anropande tråd (annan tråd -> riktig exit). Kan anropas **en gång per process**
  (globalt laddartillstånd). Gästens `main()` körs på ANROPARENS stack.
- `int machismo_main_bigstack(argc, argv, stack_bytes)`: kör machismo_main på ny pthread med stor stack (0 = 64 MiB), väntar.
  Använd den (eller `new Thread(group, r, name, stackSize)`) - Java-trådar har ~1 MB.
- `libpath.h`: `machismo_set_lib_dir`, `machismo_add_lib_alias`, `machismo_dlopen` (se 3).
- Konfig/env som appen sätter (setenv/Os.setenv före anropet): `MACHISMO_CONFIG` (absolut sökväg; annars `machismo.conf` i CWD),
  `MACHISMO_LIB_DIR`, `MACHISMO_LIB_ALIAS`, `MACHISMO_SHM_DIR` (appens cache-dir; shm_open emuleras som filer),
  `MACHISMO_NO_CHDIR=1` (annars `chdir` till spelbinärens katalog som upstream), `MACHISMO_UNWIND_LIB`, `MACHISMO_EH_MODE`.
- Gäst-`exit()` (spelet avslutar) anropar riktiga exit() och tar ner appen. Åtgärd vid behov: shimmen kan hooka exit -> longjmp.
- DRM/KMS: `splash_kms.c` bygger utan HAVE_LIBDRM (inerta stubbar) och splash-anropet är `#ifndef __ANDROID__`; i
  sdl_window_shim.c returnerar `sdl_window_kmsdrm_drop_master` -1 och `sdl_window_is_kmsdrm_env` 0 under `__ANDROID__`.
  bgfx_shim.c har kvar KMSDRM/wayland-vägar som aldrig väljs (kmsdrm_env=0; libwayland-egl aliasas till "-").

## 2. Segmentladdning (anonymt minne, 4K/16K)
- Ny `src/machismo_mem.h`. Huvudbinär (loader.c) och dylibs (dylib_loader.c): hela adressintervallet reserveras PROT_NONE
  anonymt med `MAP_FIXED_NOREPLACE` på önskad adress (0x100000000; skriver aldrig över ART-heap/andra mappningar; kärnor <4.17
  ignorerar flaggan, resultatet kontrolleras), annars valfri adress över-reserverad och trimmad till **16 KB-justering** (så
  Apple-kod ser 16K-justerade bilder även på 4K-kärna). Sedan per segment: mprotect RW -> `pread()` filinnehållet -> mprotect
  slutlig prot. bss-svansen (vmsize>filesize) är nollad av anon-mappningen. Inga filmappade segment finns kvar; header+load
  commands läses med pread till malloc (inga filmmap alls). dylib_loader behåller nu reservationen (ingen munmap/mmap-lucka).
- Sidstorlek: `sysconf(_SC_PAGESIZE)` via `machismo_page_size()`; hårdkodade PAGE_SIZE/PAGE_ROUNDUP borttagna (loader.c,
  dylib_loader.c, machismo.c). Segmentstorlekar avrundas i 16 KB-enheter (multipel av både 4K och 16K). Sidstorlek >16 KB
  (64K-kärna) avvisas tydligt (ENOTSUP). Gäststack 64 KB; poolen (2 MB) är 16K-multipel. ELF-filerna har 16 KB max-page-size.
- commpage (0xFFFFFC000) och gäststack under den använder nu `MAP_FIXED_NOREPLACE` (tidigare MAP_FIXED = tyst överskrivning;
  i en app kan ART/lib ligga nära 4 GB-gränsen). Stacken faller tillbaka till valfri adress; commpage fortsätter utan om upptagen
  (loggar). Risk kvar: gäst som läser commpage på fast adress kraschar då.
- SELinux: patchning (RCPC/LSE, trampoliner, branch-island-pool RWX) sker nu i anonymt minne -> kräver `execmem`
  (untrusted_app_27, targetSdk <= 28: tillåtet) men INTE `execmod` (filmappade segment). På nyare domäner (targetSdk >= 29) nekas
  anon PROT_WRITE|PROT_EXEC normalt -> då krävs ett W^X-flöde (RW -> patch -> RX; poolen kräver ändå exec). Ej gjort.
- RLIMIT_AS: Mina __bss är ~66 MB; __PAGEZERO (4 GB) reserveras inte. OK.

## 3. dlopen / sökvägar (src/libpath.c)
All dlopen av Linux-.so (resolver dylib_map, DEFERRED, trampoline-lib/override_lib, bgfx-EGL, elfcalls dlopen, TLV-shim) går via
`machismo_dlopen()`. Ordning: (1) alias-tabell (exakt namn, sedan basename; senaste vinner), (2) `<dir>/<basename>` för varje dir i
lib_dir (kolonseparerad), (3) namnet som givet (upstream-beteende), (4) versionsstrippad soname `libfoo.so.N` -> `libfoo.so`
(steg 2+3 igen). Alias-mål `-` = "finns inte här", misslyckas tyst.
Konfig (machismo.conf `[general]`): `lib_dir = <nativeLibraryDir>` och upprepbart `lib_alias = from=to`; env
`MACHISMO_LIB_DIR`, `MACHISMO_LIB_ALIAS="a=b;c=d"` (env vinner). Inbyggda Android-alias:
`libSDL2-2.0.so.0 -> libSDL2.so` (även `libSDL2-2.0.so`), `libSDL3.so.0 -> libSDL3.so`, `libEGL.so.1 -> libEGL.so`,
`libGLESv2.so.2 -> libGLESv2.so`, `libz.so.1`, `libstdc++.so.6`/`libc++.so.1 -> libc++_shared.so`, `libvulkan.so.1 -> libvulkan.so`;
`libgcc_s.so.1`, `libwayland-egl.so.1`, `libdrm.so.2 -> -`.
Mönster i dylib_map.conf/machismo.conf: skriv Linux-sonamnet som vanligt (`lib = libSDL2-2.0.so.0`), sätt lib_dir = appens
nativeLibraryDir (libs ligger som `lib<namn>.so` i jniLibs/arm64-v8a). Trampolinens hårdkodade standard `libSDL2-2.0.so.0`
(trampoline.c) aliasas automatiskt till `libSDL2.so`. Shim-mappningen kan vara bara `libSystem.B = libsystem_shim.so`.
Namnrymder: för targetSdk 28 räcker det att biblioteken ligger i nativeLibraryDir (absolut sökväg eller via lib_dir) och att
systembibliotek är NDK-publika (libEGL, libGLESv2, libvulkan, libz, liblog, libandroid, libOpenSLES, libm/libdl/libc).
Gästens libc++ ("libc++.1.dylib" = Apple-ABI-bygge, `LIBCXX_STANDARD` i exemplen) måste NDK-byggas för Android och packas.

## 4. C++-undantag (eh_frame.c)
Läge: Minas libc++.1 mappas till ett Apple-ABI-libc++ (alternate string layout), alltså ett eget bygge vars libc++abi väljer
unwinder. På glibc interponerade eh_frame.c `_dl_find_object` (GCC15/glibc 2.35) och registrerade via libgcc `__register_frame`.
bionic har ingen `_dl_find_object`; hooken är nu `#ifndef __ANDROID__`. På Android används LLVM libunwind, som:
(a) hittar FDE:er för kända ELF-bilder via `dl_iterate_phdr` (gäst-PC finns inte där), (b) som fallback slår upp **dynamiskt
registrerade FDE:er** (`DwarfFDECache`, fylls av `__register_frame(fde)` / `__unw_add_dynamic_fde`). Skillnad mot libgcc:
LLVM-varianten tar **en enda FDE**, libgcc hela sektionen. Implementerat (`register_with_unwinders`): kandidatbibliotek =
RTLD_DEFAULT + alla handles resolvern dlopen:at (`eh_frame_add_unwinder_handle`) + `$MACHISMO_UNWIND_LIB` + redan laddade
`libmachismo_unwind.so`/`libc++abi.so`/`libc++.so`/`libunwind.so`; flavour avgörs per bibliotek (har `__register_frame_info` =>
libgcc/sektion, annars LLVM/en FDE i taget; `MACHISMO_EH_MODE=fde|section` tvingar); både syntetiska FDE:er (kompakt unwind ->
DWARF) och Mach-O:s egen `__eh_frame` registreras (loopen avgränsas av sektionsstorlek; Apples __eh_frame saknar terminator).
Varnar högt om ingen unwinder med `__register_frame` hittas.
**Viktigt fynd (verifierat med llvm-nm/readelf):** NDK:s `libc++_shared.so` exporterar INTE `__register_frame`/`_Unwind_*` (libunwind
är dold; endast `__cxa_throw` m.fl.), och `libunwind.a` är byggt med HIDDEN visibility. Därför måste gästens Apple-ABI-libc++abi
byggas mot en unwinder som exporterar API:t. Lösning i build-android.sh: `libmachismo_unwind.so` = NDK:s libunwind.a med
dolda globala funktioner omdöpta (`mu<namn>`, llvm-objcopy) + tail-call-wrappers med originalnamn och default-visibility
(40 exporter bl.a. `_Unwind_RaiseException`, `__register_frame`, `__unw_add_dynamic_fde`). Gästens libc++abi länkas
`-lmachismo_unwind` (DT_NEEDED) så att `__cxa_throw` och registreringen når SAMMA unwinder. Alternativ: bygg LLVM libunwind
från källa med default-visibility. **Ej testat under körning**; osäkerheter: prestanda (DwarfFDECache är en linjär lista; tiotusentals
FDE:er => långsamt per undantag men korrekt), dylibs registreras ännu inte (som upstream).

## 5. TLS / tpidrro_el0 / commpage
Verifierat i källan: machismo innehåller INGEN hantering av `tpidr_el0`/`tpidrro_el0` (grep tom i src/, isa_emul, crash_handler,
shim). Mach-O TLV hanteras helt via descriptor-thunken: `__thread_vars`-descriptorer {thunk,key,offset} får thunk = shimmens
`_tlv_bootstrap` (dyld skulle annars byta till `_tlv_get_addr`), som allokerar ett block per tråd via `pthread_key`
(en nyckel per descriptor, `pthread_getspecific` vid VARJE åtkomst, kopierar `__thread_data`-bilden + `__thread_bss`).
`setup_tlv_image()` (machismo.c) sätter `__tlv_image_*` i shimmen (nu även via RTLD_DEFAULT-fallback eftersom bionic inte matchar
relativ RTLD_NOLOAD mot absolut laddad lib). Minas exe har 4 descriptorer (0x60 byte `__thread_vars`) och **0 instruktioner
`mrs xN, tpidrro_el0`/`tpidr_el0`** i hela `__text` (skannat med skript) -> exe är opåverkad. Apples `tpidrro_el0` (pthread_self,
`_tlv_get_addr`-snabbväg) lever bara i libSystem, som ersätts av shimmen. Linux-kärnan äger tpidrro_el0 för 64-bitstrådar
(nollas vid kontextbyte; med KPTI ej garanterat) - efter minnet av arch/arm64/kernel/process.c, EJ verifierat mot kärnkälla här;
bionic använder `tpidr_el0` för sin TLS. Slutsats: en Mach-O-dylib som innehåller `mrs tpidrro_el0` får 0/skräp -> krasch;
skanna ev. dylibs. Bionic-skillnader: `PTHREAD_KEYS_MAX` = 128 (glibc 1024; räcker för 4 TLV men räkna med dylibs/Steam),
x18 (Apple reserverat, gästen rör det aldrig; bionic SCS-kod bevarar sin x18 över gästanrop), MTE/TBI-pekartaggning (targetSdk 28
ger ingen taggning; annars `android:allowNativeHeapPointerTagging="false"`). Commpage: fältlayout oförändrad; CPU-caps via
`getauxval(AT_HWCAP)` fungerar på bionic; sidstorlek skrivs som riktig shift (12 eller 14).

## 6. Verifiering (llvm-nm -D -u, NDK 27.3)
- libmachismo.so: länkad med `-z defs`; samtliga odefinierade symboler är bionic-libc (@LIBC) utom svaga `memfd_create`
  (API 30; weak). `sem_open/sem_close/sem_unlink` finns men är ENOSYS-stubbar i bionic (named semaphores).
- libsystem_shim.so: `__assert_fail` (glibc-only) ersatt med bionic `__assert2` -> endast @LIBC/libm kvar (+ weak memfd_create).
  Shimmens `dlsym(RTLD_NEXT, <glibc-namn>)`-forwarding (stat64/xstat/fopen64 m.fl.) syns inte i nm; kan ge NULL i runtime (olöst).
- machismo (exe): bara libc + libmachismo.so (`machismo_main`).
- libmachismo_unwind.so: bara libc (dl_iterate_phdr, pthread_rwlock*, ...).

## 7. Integrationsrecept (Android-app, targetSdk 28)
1. jniLibs/arm64-v8a: libmachismo.so, libsystem_shim.so, libSDL2.so, guest-libc++ (Apple-ABI) + libmachismo_unwind.so, ev. override-libs.
2. Java: `System.loadLibrary("machismo")`; JNI-funktion som `setenv`:ar MACHISMO_* + bygger argv och anropar
   `machismo_main_bigstack(argc, argv, 0)` på en bakgrundstråd; spelets data/`MinaTheHollower` på appens fildir/extern lagring.
3. machismo.conf: `[general] dylib_map=/abs/dylib_map.conf`, `lib_dir=<getApplicationInfo().nativeLibraryDir>`; dylib_map:
   `libSystem.B = libsystem_shim.so`, `libc++.1 = <apple-abi-libc++>.so`, `libSDL2 = libSDL2-2.0.so.0` (aliasas).
4. SDL2:s Java-del (SDLActivity) måste initiera JNI/EGL innan gästen skapar fönster; `sdl_window_shim`/`bgfx_shim` antar
   `SDL_*` via dlsym(RTLD_DEFAULT) - libSDL2.so laddas RTLD_GLOBAL av resolverns dlopen före första SDL-anrop.

## 8. Kvarvarande risker / ej gjort
- Hög: unwinder-kedjan (4) ej körd; Apple-ABI-libc++ för Android måste byggas (patches/libcxx-darwin-pthread-compat.patch är glibc-anpassad).
- Hög: SELinux på targetSdk >= 29 (anon RWX nekas); signalhantering (crash_handler.c via sigaction under ART/libsigchain: ART tar sina
  SIGSEGV först, gästens guard-pages (trampoline MAX_GUARDED_PAGES) bör vidarebefordras - ej verifierat).
- Medel: shimmens glibc-dlsym-forward; `exit()` från gästen dödar appen; en `machismo_main` per process; commpage-adress kan vara upptagen.
- Medel: Linux-antaganden kvar i resolver.c (link_map/l_name), `/proc/self/maps` i crash_handler (OK i egen process).
- Låg: heaptrack/gdb_jit/KMS-splash inaktiva; named semaphores; PTHREAD_KEYS_MAX 128.
- CMakeLists.txt oförändrad (build-android.sh används). Filer jag rört har behållit CRLF; nya filer är LF.
