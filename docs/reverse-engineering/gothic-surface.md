# Gothic-ytan: vad PortMaster-porten ersätter Metal med (Mina the Hollower)

Underlag (rådata i samma mapp): gp_exports.txt, gp_imports.txt, gp_all.txt, gp_strings.txt,
gp_glibcver.txt, mach_undef.txt, mach_selectors.txt, mach_sections.txt, mach_dylibs.txt.
Allt nedan är statisk analys (llvm-nm/objdump/strings + läsning av conf/skript/machismo-källa).
Inget kört, inget på nätverket, ingen adb.

## 1. libgothic_patches.so (aarch64 glibc ELF, 4,3 MB)

### 1.1 Beroenden
- NEEDED (strängar i filen, llvm-readobj i NDK 27 saknar --needed-libraries): libEGL.so.1,
  libGLESv2.so.2, libwayland-egl.so.1, libvulkan.so.1 (dlopen:as, inte hård länk),
  libstdc++.so.6, libgcc_s.so.1, libc.so.6, libdl.so.2, libpthread.so.0.
- Symbolversioner: GLIBC_2.17 (68 st, dvs lägsta möjliga aarch64-baslinje), GLIBCXX_3.4 .. 3.4.21
  (std::__cxx11::string, _Rb_tree, unordered_map), CXXABI_1.3(.9), GCC_3.0.
  Alltså ingen modern glibc-funktion (inget 2.3x-krav) i själva .so:n.
- glibc-specifika/icke-bionic importer (de som kräver åtgärd): `__errno_location` (bionic: `__errno`),
  `__fxstat` (bionic har bara `fstat`), `__sigsetjmp` (bionic: `sigsetjmp`/`_setjmp`-makro),
  `__getauxval` (bionic: `getauxval`), `__pthread_key_create`, `malloc_trim` (saknas i bionic),
  `pthread_setname_np(thread,name)` (finns i bionic API 26+, annan semantik OK), `clock_nanosleep` (API 30+, ok),
  `stderr` som symbol (bionic: `__sF`/`stderr` finns via headers, OK om omkompilerat),
  `dladdr/dlopen/dlsym` OK. Resten (malloc, mmap, madvise, pread, pthread_*) finns i bionic.
  Obs: alla dessa blir triviala vid omkompilering mot NDK; problemet uppstår bara om man försöker återanvända
  den färdiga binären (går inte: den är glibc-dynamiskt länkad).
- libstdc++ (GCC-ABI) används; NDK har libc++ (c++_shared/static). Ingen blandning möjlig, men koden är
  ren STL (map/set/vector/unordered_map) så omkompilering mot libc++ är oproblematisk.
- Importerade GLES: 94 st glXxx, GLES 3.0 (UBO, VAO, sampler objects, glTexStorage2D, glMapBufferRange,
  glInvalidateFramebuffer, glDrawElementsInstanced, glVertexAttribIPointer). Plus 11 egl*.
  Kräver GL_VERSION "OpenGL ES 3" (varnar annars) och GLSL ES 3.10-shaders. Allt täcks av Adreno/Android GLES 3.1.

### 1.2 Grupp A: objc-/Metal-skal ("Metal-as-memory model")
Exporterade (232 T/B/D/W-symboler totalt):
- `MTLCreateSystemDefaultDevice` (skapar EGL-display, returnerar sentinel-device). `MTLCopyAllDevices` importeras
  av spelet men ligger inte som export -> faller till resolverns return-0-stub.
- Mini-objc-runtime: objc_msgSend, objc_msgSendSuper2, objc_alloc/alloc_init/opt_*, retain/release/autorelease*,
  autoreleasePoolPush/Pop, storeStrong/storeWeak/loadWeakRetained, setProperty_*, getProperty, sync_enter/exit,
  exception_throw/rethrow/begin/end_catch, __objc_personality_v0, class_addMethod/addIvar/getName, sel_registerName/getName,
  objc_registerClassPair, objc_allocateClassPair, protocol_conformsToProtocol, method_get/setImplementation,
  _NSConcreteGlobalBlock/_NSConcreteStackBlock (block-ABI), NSObject-klass- och NSException-symboler.
- Objektmodell: GOTHIC_MTL_{DEVICE,CMDQUEUE,BUFFER,TEXTURE,SAMPLER,LIBRARY,FUNCTION,PIPELINE,DEPTHSTENCIL,REFLECTION,THREAD,GENERIC}.
  MTL-objekt är C-structar i libgothic_patches, inte riktiga objc-objekt; `objc_msgSend` slår upp selector -> handler
  via `gothic_objc_set_dispatch`/`gothic_objc_dispatch_shader_tag`.
- Vertex-format: gothic_mtl_vertex_format + MTL_Char..UShort4Norm/Float/Half/Int-tabeller (MTLVertexFormat -> GL/VK).
- Särskilda knep: `gothic_objc_function_key` (plockar shader-nyckel ur AIR-blobben i newLibraryWithData:),
  `gothic_objc_buffer_copy`/`set_buffer_blit` (blit-encoder), `gothic_run_gc_completion` + `ycRenderGCThreadMtl::Advance`-block
  (emulerar command-buffer completion handlers), `gothic_pcm_cache_tick`.

### 1.3 Grupp B: GLES-RHI-backend (`g_gles_ops`)
gothic_gl_* (frame, next_frame, execute, drawcall, flush_batch, present, buffer/buffer_upload/buffer_blit/used_range/host_shadow/shadow_free,
texture_blit/prepare/name, offscreen/_color/_depth, sampler, program_get, index_max, defer_delete, invalidate_draw_state,
current_is_backbuffer, current_rt_size, tile_atlas_{lookup,demote}/tile_is_atlas_eligible), gothic_egl_{create_display,create_surface,make_current},
gothic_rhi_{execute,blend_factor,depth_compare,color_write_mask,clip_scissor,present_scale_mode,present_viewport_rect,signal_count,
queue_destroy,drain_destroys}. Egen tile-atlas för terräng (GOTHIC_ATLAS_*), UBO-arena (GOTHIC_UBO_ARENA_SIZE), batchning
(GOTHIC_BATCH_INDEX_CAP). Fönster/EGL-väg: SDL-ägd GBM/EGL (KMSDRM), Wayland (wl_egl_window), X11.
Shaders: GLSL ES 3.10 från `shaders_gles/*.glsl` + `.refl.json` (reflektion -> UBO-/sampler-bindningar).

### 1.4 Grupp C: Vulkan-backend (`g_vk_ops`, 102 distinkta vk*-namn i strängar)
- Laddas via dlopen("libvulkan.so.1") + vkGetInstanceProcAddr/DeviceProcAddr (inga vk*-importer i dynsym).
  Headers: Vulkan-Headers 1.3.296.
- Valdes med `GOTHIC_BACKEND=vulkan|gles`; annars auto-prefer Vulkan om "usable" (`gothic_vk_probe`), fallback till GLES
  ("treating Vulkan as unavailable, using GLES").
- Funktioner: gothic_vk_{probe,frame,execute,drawcall,cmd,buffer,buffer_upload,offscreen(+color/depth_view),pipeline_get,program_get/init/
  save_cache,sampler(_info),depth_sampler,texture_view/blit/prepare/flush_upload,tex_format,vertex_format,ubo_alloc/ptr/ring_begin_frame,
  desc_begin_frame,alloc_memory/free_memory,recreate_swapchain,create_surface,current_rp_id,install_objc_hooks}.
- Vulkan-API-yta: instans/enhet, swapchain (KHR), render pass + framebuffer, graphics pipelines + pipeline cache (sparas till disk),
  descriptor pools/set layouts/`vkUpdateDescriptorSets`, push descriptors (VK_KHR_push_descriptor), push constants, dynamic viewport/scissor,
  VK_EXT_extended_dynamic_state (valfritt), VK_KHR_maintenance1 (negativ viewport-höjd för Y-flip; krävs på 1.0-enheter),
  buffer->image-kopior, clear attachments, egna allokeringar (GothicVkAlloc). Ingen compute, inga timeline semaphores, inget dynamic rendering.
- Ytor: SDL_Vulkan_CreateSurface / GetInstanceExtensions (via SDL2) ELLER VK_KHR_display direkt (KMSDRM-fallback, `vkCreateDisplayPlaneSurfaceKHR`).
- Shaders: `shaders_spv/*.spv` + `.refl.json` (GOTHIC_VK_SHADER_DIR).
- Kommentaren "new-libmali fleet" i patch.bash: Vulkan prioriteras på nya Mali-drivrutiner (PanVK/libmali), inte generellt.

### 1.5 Grupp D: motor-detours
Utöver objc-skalet ersätts (match_local=true i `[trampoline.gothic]`, override_lib = libgothic_patches.so; den ersätter symboler
i spelets Mach-O som är LOKALA (icke N_EXT), annars matchar bara exporterade):
- `ycRenderCmdList::Execute()` (kärnan: tolkar kommando-opcodes YC_OP_BEGIN_PASS/END_PASS/SET_VIEWPORT(_REL)/SET_SCISSOR(_REL)/
  CLEAR_COLOR/DRAW_CALL/CAPTURE/CALLBACK_A0/A1/VCALL_0x10/0x18/END -> GL/VK direkt i stället för MTL-encoder),
- `ycRenderWindow::InitSwapChain()`,
- `ycFile::Read`, `ycDir::Exists` (filsystemet: Mac-case/sökvägar, fallback-alloc),
- `ycThread::Start` (tråd-start med egen stack/trampolin; 8 args inkl. namn),
- `ycAudio::FileManager::InitBankQueue` (+ strängar för LoadEvents/CacheEvents/Decoder-köer, ycFileManager::AddRef/ReleaseRef:
  minneshantering på 2 GB-enhet),
- `SDL_GetBasePath` (game-path),
- `ycGpuBuffer::Upload`: separat instruktionspatch i conf/patches/gothic.conf (NOP av `tbnz w8,#1`, mönster 370800e8, läcka i Mac-bygget).
Ingen annan ycRender*-funktion exporteras; resten av rendering går via objc_msgSend-skalet (spelets Metal-kod körs oförändrad och
landar i skalet).

## 2. Kedjan shader-pak -> GLSL ES / SPIR-V (patch.bash + conf)

1. patch.bash (körs av PortMaster-patchern första gången, villkor: patch/corpus.version=5 vs gamedata/.shaders_ready).
2. Hämtar spelpaketet (GOG .pkg via 7zzs->gunzip->bundlad GNU cpio, eller Steam Mac-depot, eller färdig .app) till gamedata/.
3. Indata: `Mina the Hollower.app/Contents/Resources/data/shaders.pak.yc`.
4. `tools/ycd_extract <pak> <dir>` (ren C, 14 KB, inga beroenden): YCD-container -> 117 st `.metallib` (Metal-bibliotek med AIR-bitkod).
5. `tools/airlift build <metallibs> <shaders_gles> --spv-out <shaders_spv>` (C++, 12 MB, statiskt libstdc++, bygger på LLVM 20 + SPIRV-Tools + SPIRV-Cross):
   AIR-bitkod -> SPIR-V -> (SPIRV-Cross) GLSL ES 3.10. Utdata: 114 `.glsl` + 114 `.refl.json` (GLES) och 114 `.spv` + 114 `.refl.json` (Vulkan).
   (117 metallibs -> 114 shaders.) Sedan stämplas Mali-workaround-markörer i GLSL: 14 AIRLIFT_CLIP_VIA_SCISSOR (8 hårda + 6 mjuka font),
   8 AIRLIFT_COMPOSITE_OVER, 18 AIRLIFT_FONT_COVERAGE_OVER; skriptet failar om antalen inte stämmer (skydd mot fel speltversion).
6. Kopieras atomiskt till gamedata/shaders_gles och gamedata/shaders_spv; `.shaders_ready` skrivs.
7. Runtime: libgothic_patches hämtar shader-nyckeln ur AIR-blobben vid `newLibraryWithData:error:` (gothic_objc_function_key) och mappar
   `newFunctionWithName:` -> rätt .glsl/.spv + reflektion. Okänd nyckel -> "unresolved program".
8. Inget shader-material levereras med porten; allt genereras från användarens egen kopia (~1 MB, sekunder).
9. machismo.conf: dylib_map= conf/dylib_map.conf (libc++.1 -> bundlad Apple-ABI libc++, libSystem.B -> libsystem_shim.so,
   libobjc.A + Metal -> libgothic_patches.so, libiconv/steam -> STUB, Foundation/AppKit/QuartzCore/CoreFoundation/CoreGraphics/
   CoreAudio/AudioToolbox... -> SKIP), patches = gothic.conf, splash_image = splash.png (KMS-splash), [trampoline.sdl2] `_SDL_*` -> enhetens
   libSDL2 (spelet länkar SDL2 statiskt, ~871 symboler), [trampoline.steam_noop] `_SteamAPI_/_SteamInternal_` -> STUB.
   Notera: SDL2, audio (ogg/vorbis/samplerate) är statiskt i Mach-O:n -> inga Mach-O-dylibs att ladda.

## 3. Spelets Mach-O (arm64, 17,4 MB)

- Länkade frameworks: Metal, QuartzCore, AppKit, Foundation, CoreFoundation/Graphics/Audio/Video/Services, AudioToolbox, IOKit, ForceFeedback,
  Carbon, IOSurface, libc++, libSystem, libiconv, libobjc, libsteam_api (weak). De flesta (AppKit/IOKit/CF*/CG*/CV*) kommer från den
  statiskt inlänkade SDL2:s Cocoa-backend, inte från motorn.
- Odefinierade symboler: 538 totalt. Metal-relevanta: `MTLCreateSystemDefaultDevice`, `MTLCopyAllDevices`, klasser
  `MTLRenderPassDescriptor`, `MTLRenderPipelineDescriptor`, `MTLTextureDescriptor`, `MTLSamplerDescriptor`, `MTLDepthStencilDescriptor`,
  `MTLVertexDescriptor`, `MTLCaptureManager` (7 klasser). Övrigt objc: ~35 `objc_*` (msgSend, retain/release, autoreleasePool, opt_*,
  exceptions, weak) + `kCAFilterNearest`.
- Själva Metal-protokollen (MTLDevice/CommandQueue/CommandBuffer/RenderCommandEncoder/BlitCommandEncoder/Buffer/Texture/Library/SamplerState)
  nås enbart via objc_msgSend på objekt från ovan.
- Selectorer: __objc_methname = 905 strängar (inkl. SDL:s Cocoa-selektorer, ivar-typer); __objc_selrefs = 0x1488/8 = 657 selector-referenser totalt.
  Metal-/render-delen: ca 125-135 distinkta selectorer (genom heuristisk filtrering, se mach_selectors.txt; exakt gränsdragning mot SDL osäker).
  Kärnan: newBufferWithLength:options:, newTextureWithDescriptor:, newSamplerStateWithDescriptor:, newDepthStencilStateWithDescriptor:,
  newRenderPipelineStateWithDescriptor:options:reflection:error:, newLibraryWithData:error:, newFunctionWithName:, newCommandQueue,
  renderCommandEncoderWithDescriptor:, blitCommandEncoder, setRenderPipelineState:/setDepthStencilState:/setCullMode:/setFrontFacingWinding:/
  setDepthBias:slopeScale:clamp:/setViewport:/setScissorRect:/setStencilReferenceValue:, setVertex/FragmentBuffer:offset:atIndex:,
  setVertex/FragmentBytes:length:atIndex:, setVertex/FragmentTexture:/SamplerState:atIndex:, drawPrimitives:(+instanceCount:),
  drawIndexedPrimitives:...(+instanceCount:), copyFromBuffer->Texture/Buffer, copyFromTexture->Texture, synchronizeTexture:slice:level:,
  getBytes:..., endEncoding, commit, addCompletedHandler:, presentDrawable:, nextDrawable (CAMetalLayer), samt ~70 descriptor-settrar
  (pixelFormat, load/storeAction, blend-faktorer, stencil-ops, MSAA sampleCount/resolveTexture, mip/anisotropi, usage/storageMode m.fl.).
- Slutsats: ytan är liten och sluten: ett enda 2D/3D forward-renderingsflöde utan compute, tessellation, argument buffers eller indirect.

## 4. Slutsats för Android-port (renderingsdelen)

Säkert (läst direkt):
- Designen är "behåll Mac-motorns arm64-kod, fånga Metal-anropen i ett eget skal". Skalet + två RHI-backends är ~4 MB .so, helt C/C++ över GLES 3.x
  eller Vulkan; ingen Apple-kod behövs i renderingsvägen. Android/Adreno stöder GLES 3.1 och Vulkan 1.1+, så båda backends är i princip ersättningsbara.

Bedömning:
1. GLES-backenden är den direkta kandidaten: kräver bara att EGL-ytan skapas via ANativeWindow/SDL (i stället för KMSDRM/GBM/Wayland/X11-grenarna) och
   att kompilera om mot NDK. Shaders är GLSL ES 3.10 (Adreno OK). Risker: Mali-workarounds (clip-via-scissor, composite-over, font-coverage) är gjorda
   för Mali-tile-bugg och kan vara onödiga/ofarliga på Adreno; Adreno-specifika buggar okända.
2. Vulkan-backenden kan i princip återanvändas på Adreno: den använder bara standard-1.0/1.1-API + KHR_swapchain, push_descriptor,
   maintenance1, valfri extended_dynamic_state. Adreno 6xx/7xx stöder alla på Android 10+ (push_descriptor finns på Adreno-drivrutinen; verifiera med
   vkEnumerateDeviceExtensionProperties på Duo). Ändringar: ytan skapas redan via `SDL_Vulkan_CreateSurface`, så Android-SDL fungerar utan VK_KHR_display;
   libvulkan.so.1 -> libvulkan.so (dlopen-sträng); pipeline-cache-sökväg; ev. pre-rotation/Surface-transform vid rotation. Osäkerhet: ej testat; backendens
   Y-flip (negativ viewport) och render-pass-beroenden kan trigga Adreno-specifika valideringsfel; backend-valet bygger på "usable"-probe som
   måste tränas om för Adreno. Rekommendation: börja med GLES (färre rörliga delar), behåll Vulkan som andra steg.
3. glibc -> bionic: i libgothic_patches endast `__errno_location`, `__fxstat`, `__sigsetjmp`, `__getauxval`, `__pthread_key_create`, `malloc_trim`
   (byts/stubbas), plus libstdc++ -> libc++. `mmap_registry_add` är en intern symbol från machismo. I machismo/libsystem_shim kommer fler glibc-
   specifika anrop (se jämförelse nedan).
4. Delar som är rena C/C++ och kan kompileras om oförändrade (eller nästan): objc-skalet, hela GLES-/Vulkan-RHI, tile-atlas, ycd_extract (ren C,
   14 KB), shader-reflektionsparser, patch-formatet (conf/patches/gothic.conf). airlift (C++/LLVM 20 + SPIRV-Tools/Cross) behöver
   ingen port till enheten: kör på PC/WSL och leverera `shaders_gles/shaders_spv` förgenererade i APK (men då distribueras härledda
   spelresurser; måste göras lokalt hos användaren om man vill undvika det. Alternativt cross-bygga airlift för bionic).
5. Det som INTE är renderingen men blockerar: (a) machismo själv (Mach-O-loader, fixups, trampolines, isa_emul, commpage) - ett försök finns redan
   i C:\Claude\mina-port\machismo-android (android_compat.h för __errno/ctype/shm_open/bswap); (b) 16 KB-sidor: Mac arm64 antar 16K, Android
   har 4K på de flesta enheter (Duo: kolla `getconf PAGESIZE`), machismo-segmentmappningen och MAP_FIXED vid fast bas 0x100000000 måste verifieras;
   (c) Android tillåter ej W^X-skriv på exekverbart minne (instruktionspatchar, stub-poolen, trampoliner) utan särskilda åtgärder (mprotect på
   anonymt minne fungerar normalt för egen process, men SELinux/targetSdk>=29 spärrar exec av nedladdade filer; ladda från APK-libs);
   (d) Apple-ABI libc++ måste byggas för bionic; (e) SDL2 trampolinen `_SDL_*` -> enhetens libSDL2: på Android måste man bundla SDL2 (finns ingen systemlib);
   (f) ljud: statiskt SDL2-audio, ok; (g) hela libsystem_shim (130 KB) måste granskas för bionic.
6. Osäkerheter: exakt selectorantal i Metal-delen (heuristik); om skalet täcker alla anrop i alla spelavsnitt (porten verkar köra på RG DS enligt
   BUILDING.md, men "Phase 1 smoke-test"); prestanda på Adreno för Mac-motorns instruktionsmönster; hur mycket av machismo som är glibc-specifikt
   utöver det som redan hittats i machismo-android; stabilitet hos instruktionspatchen vid annan speltversion (patch söker mönster, avbryter vid 0/flera träffar).

Total bedömning: renderingen är den smalaste delen av problemet. Mest arbete ligger i loadern/ABI (machismo, 4K/16K-sidor, Apple-libc++) och
SDL/EGL-fönstret, inte i Metal-ytan. GLES-backenden bör gå att få att köra på Adreno med små ändringar; Vulkan-backenden är återanvändbar men oprövad.
