# Mina the Hollower: map assets, decoding, and dot placement

Static analysis only (no adb/emulator/gradle). Game data is the user's own Steam copy; nothing was uploaded.
Decoded images live only locally under `analysis\_x\` and `analysis\_out\` (do NOT publish them).

## TL;DR

1. The game's own map is the **18-location world map** (one illustrated 256x144 world map, 17 clickable regions).
   It has **no per-room / per-position map**. The in-game "Mina" marker is drawn at a **fixed per-location anchor
   ("MinaPos")**, not at the live player position. So there is no existing "map image of the current area with dot".
2. Everything for the world map is in `data/global.pak.yc` -> `UI/worldMap.anb.yc` (256x144) and
   `UI/worldMapBig.anb.yc` (512x288, used by `ToggleZoom`), palette in `data/global_startup.pak.yc` -> `palettes/global.pal.yc`.
   Formats are all decoded: YCD v8 container + 8-bit paletted images compressed with **wfLZ** ("WFLZ"). Working python decoders are in
   `analysis\tools\`; `bake_map.py` produces ready PNGs + a locator JSON (tested, output verified visually).
3. For a second-screen dot you have two options:
   - (A, game-faithful, done) world map PNG + dot at the current location's `MinaPos` (exactly what the game shows).
   - (B, finer, not done) per-area minimap from `levels/*.tlb.yc` tile layers + live world coords. Format is only partially mapped (see section 7).

## 1. Resources layout

`Contents/Resources/data/` holds 107 files, all `*.yc` (no loose PNG/DDS/KTX anywhere):
- 106 `*.pak.yc` (YCD v8 "ycPakBin" containers; 4157 entries total), plus `*.sndbnk.yc`, `*.cmap.yc`.
- Entry types inside paks: `.pal.yc` (2119 palettes), `.anb.yc` (1346 sprite/animation bundles = all 2D art), `.palanm.yc`,
  `.tlb.yc` (99 tile levels), `.font.yc`, `.stb.yc`, shader blobs.
- Only 3 names contain "map": `UI/worldMap.anb.yc` (87,509 B), `UI/worldMapBig.anb.yc` (153,343 B) in `global.pak.yc`,
  `objects/checkpointMap.anb.yc`, and `levels/bigArt/bigArt_boneBeachMap.tlb.yc` (a decorative in-world painting, not the UI map).
- Related UI: `UI/locationPlate.anb.yc` + `palettes/locationPlate*.pal.yc` (17 plates, one palette per location:
  AstralOrrery, BayouA, BayouB, BoneBeachA, BoneBeachB, CryptA, CryptB, FrozenTrainyard, IntroBeach, MansionA, MansionB, Ossex,
  OWEast, OWSouth, OWWest, SeptemburgA, SeptemburgB), `UI/collectionIcons.anb.yc`, `UI/HUD/*`.
- No room images, no per-room map art. Room geometry only exists as tile levels (`levels/core|overworld|extra/*.tlb.yc`).

## 2. Container: YCD v8 (already known from shaders/ycd_extract.py)

```
+0   'YCD\0'  u32 version=8
+8   u64 root_off     +0x10 u64 root_type_hash (ycPakBin = 0x00dabfa453c79641; .anb = 0x2dac6276a17bac0c; .pal = 0x691e3319647d3116;
                                              .tlb = 0x520534b4e863ef47)
+0x20 u32 flags (bit0 = fixups already applied)
+0x28 u64 base[4]   +0x48 u64 first[4]   (-1 = none)    4 fixup chains
```
Fixup: each site is `{u32 next_delta (-1 end), i32 rel}`; rewrite site as `u64 = site + rel` (absolute file offset), advance by next_delta.
After that every pointer in the file is an absolute offset. Arrays are `{u32 bytes, u32 bytes/4}` + pointer.
Pak root = `{u32,u32 count*4 ,u64 toc}`; TOC entry (32 B, at `toc+0x10+32*i`): name_len u32 @-0xc, name ptr @-8, size u32 @0, data ptr @+8.
Tools: `tools\ycd.py` (loader), `tools\ycpak.py list|extract`.

## 3. Image compression: wfLZ ("WFLZ")

Decompressor in the binary: `wfLZ_Decompress` @0x1000893e4 (disassembled). Block: `'WFLZ' u32 compSize u32 rawSize`, byte 15 = first literal count.
```
lit = in[15]; p = 16; prev_match = false
loop:
  if lit == 0 and not prev_match: stop
  copy `lit` literal bytes
  rec = in[p..p+4] : u16 offset, u8 len, u8 next_lit ; p += 4
  if len != 0: copy (len+4) bytes from out[-offset] (overlap allowed); prev_match = true else prev_match = false
  lit = next_lit
```
`tools\wflz.py`. Verified on all 63 + 52 images of both world maps and on 31 blocks of a .tlb (decoded size always equals rawSize).

## 4. Palette: `palettes/global.pal.yc` (in global_startup.pak.yc, 1507 B)

Root: `{u32 255, u32 85, ... ptr @root+0x10}`; pointer targets 255 RGBA entries (bytes R,G,B,A) = palette indices 1..255; index 0 = transparent.
(`anb_images.palette`.) Images are 8 bits/pixel row-major, index into this palette. The `.anb` names `palettes/global.pal.yc` explicitly;
per-location recolor palettes are applied by the game for the plates (".pal.yc" suffix strings in ToggleZoom), not needed for the map art.

## 5. worldMap.anb.yc structure

Sprite records (56 B each, from 0x170): `u32 name_hash`, `{0,len}`, `name ptr`(+16), `{16,4}`, `frames ptr`(+32, first dword = cel/image index, then f32 frame dur),
`{48,12}`, `locator array ptr` (+48, 3 ptrs). Hash = Jenkins lookup3 `hashlittle(str, len, initval=1337)` (binary @0x1001b6d48 has 0xdeadbeef+1337 baked in);
`hashlittle("MinaPos")=0x791fc5e7`, `"CompletePos"=0x9b8b9d0c`, third locator 0xb2dbee1b (unnamed, presumably the label/plate anchor).
Locator object (type id 0x6a0c7b7c0d2180a1): `{u64 type, u32 pad, u32 name_hash, f32 x, f32 y}`; the game reads `(x,y)` at +16 (UpdateLocationVisual).
Tool: `tools\anb_sprites.py` lists them (68 sprites: 17 location sprites, 17 "Outline", 7 "Lit", markers, icons, frames).

Images (WFLZ blocks, record `{u32 w,u32 h,u64 0,u32 csize,ptr}` 32 B x63): image 0 is the **192x192 atlas** of the map background, the rest are
region sprites/outlines/icons (9x7 .. 128x66). `minaMarker` = image 18 (9x7), `completeMarker` = 51, `starMarker`=46.

**Background reassembly** (sprite `map`, cel 0): cel table at `*(u64*)(root+0x40)` (0x21e8); cel0 = `{f32 canvasW=256, f32 canvasH=144, {bytes=192,..}, ptr}`;
slice list = `bytes/16` (=12) records of 24 B `{u32 w=64, u32 h=48, f32 dstX, f32 dstY, f32 srcX, f32 srcY}`; paste `atlas[src..src+64x48]` at
`(dst + canvas/2)`. Result (verified visually) is the whole stitched world map, `tools\anb_worldmap.py`. Big variant: 64x58 slices -> 512x288.

## 6. MapScreen semantics (disassembly, `tools\dis_annot.py`)

- `Setup()` @0x1003e4fc8: loads `UI/fullscreenBlack.anb.yc`, `UI/worldMap.anb.yc` (18-way anim loop creating the per-location widgets, 464 B each at `this+240`),
  sprite names `letterBox`, `map_controls`, `frameTotals`, `map_allTotal`; extra widget arrays at this+248/232/224 (outline/lit layers).
- `ToggleZoom(bool)` @0x1003e7764: swaps `UI/worldMap.anb.yc` <-> `UI/worldMapBig.anb.yc`, uses `minaMarker`, `completeMarker`, `".pal.yc"` palettes.
- `SetPosCamera(ycVec3)` @0x1003e69d4: clamps a camera (this+160,+164 = x,y; +168 = z) to the map extents and moves all 17 location widgets by `(-camX, -camY)`.
  `SetPosFrame` @0x1003e6ca8 positions the frame sprites. Pure UI scrolling, nothing to do with world coordinates.
- `UpdateLocationVisual(loc)` @0x1003e8b14: for every location widget it looks up locators `CompletePos` and `MinaPos` (`GetProperty(hash,-1,-1)`),
  scales by widget scale (this+400..) and places the layers; the **Mina marker widget (this+216) is positioned at the MinaPos of the location whose
  index equals the current location (`mapDefs[...]+68 == loc`)**, offset by `-camera`. There is NO use of player world coordinates anywhere in MapScreen.
- Therefore (3) "mapping from world coords to map-image coords" does **not exist** in the game; only location index -> locator.

### Location index -> sprite name (UNVERIFIED hypothesis)
Widgets 0..16 (17 of them). Best guess = alphabetical order of the 17 `locationPlate*` palettes:
0 astralOrrery, 1 bayouA, 2 bayouB, 3 boneBeachA, 4 boneBeachB, 5 cryptA, 6 cryptB, 7 frozenTrainyard, 8 introBeach, 9 mansionA, 10 mansionB,
11 ossex, 12 easternFields(OWEast), 13 southernFields(OWSouth), 14 westernFields(OWWest), 15 septemburgA, 16 septemburgB. (17 = spare/hell, 18 = none.)
Confirm at runtime: stand in e.g. the Crypt, read `MapScreen+340` / `*(SaveSlot+584)` -> table entry+12, and check against this list. The sprite order in the .anb
file (astralOrrery, bayouA, bayouB, boneBeachA, boneBeachB, cryptA, cryptB, easternFields, westernFields, frozenTrainyard, introBeach, mansionA, mansionB,
ossex, septemburgB, septemburgA, southernFields) is NOT the index order (checked against code structure: names are resolved through the runtime area table @0x10504c3f8).

Locator coordinates are map-centre relative with **y up**: `px = W/2 + x`, `py = H/2 - y` (256x144 small / 512x288 big, big = exactly 2x).
Visually verified (all 17 dots land on the matching regions). Values (small): astralOrrery (-51,39), bayouA (-77,-28), bayouB (-105,-14),
boneBeachA (23,-36), boneBeachB (76,-30), cryptA (68,9), cryptB (101,5), easternFields (43,-2), westernFields (-56,-4), frozenTrainyard (68,44),
introBeach (-41,-46), mansionA (2,21), mansionB (2,44), ossex (0,-4), septemburgA (-90,15), septemburgB (-98,41), southernFields (-11,-29).
Full JSON: `analysis\_out\locators_small.json` / `locators_big.json`.

## 7. Room geometry (option B) - partial

`levels/**.tlb.yc` = YCD (type 0x520534b4e863ef47), `ycTileLevel2`. Seen in cryptA.tlb: root `{u32 500,u32 500}` (level 500x500 tiles?), `{8,8}`, `{16,16}` (tile sizes?),
layers named `GROUND`, `EXTRA_COLLISION`, `DETAIL`, `FOG_BORDER`, `BG_Scroll_*`, `PIT_BLACK`, entities such as `BOSS ARENA`, `stage intro`; room table
(32 B entries at level+1112 in memory per position-offsets.md). Texture/chunk objects: 168-byte records
(`name`, f32 pos x,y, scale, ptr to a WFLZ block; 30 per level, block sizes 512..65536 B, all decode with `wflz.py`). Record layouts are runtime-built reflection
(`s_recordDef_*` in __DATA zero-fill), so the TileLayer/TileInfo field layout and tileset linkage were NOT decoded. Tilesets live in `levels/tilesets/**` (`.anb`, referenced by name strings).
To do a per-room minimap one would: decode GROUND/EXTRA_COLLISION chunks to a solid/empty bitmap (needs layer record layout), map world coords by tile size.
Alternative that needs no decoding: record the player's visited path/AABBs live (RoomManager+512..536) and draw them yourself.

## 8. Recipe for Android (second screen)

Offline (once, on the PC, from the user's own game data, never uploaded; push the result to the device private dir):
```
python analysis\tools\bake_map.py "<...>/Mina the Hollower.app/Contents/Resources/data" <outdir>
 -> worldmap_small.png (256x144), worldmap_big.png (512x288), locators_small.json / locators_big.json
```
Runtime (our loader, no game-format code needed on device):
1. Load the PNG once (nearest-neighbour scaling, integer multiples: second screen at 5x/6x etc.).
2. Each frame read `loc = *(u32*)(MapScreen+340)` if a MapScreen exists, else derive: `area = *(u32*)(*(u64*)0x105061938 + 584)` -> find entry in the 198-entry table
   (stride 136 @0x10504c3f8, entry+8 == area) -> `entry+12` = location index (negative = none, hide dot).
3. Name = table above (verify at runtime once). `(x,y) = locators[name]`; pixel = `(W/2 + x, H/2 - y)`; draw a blinking 9x7 `minaMarker`
   (decode image 18) or a plain dot there. Draw `completeMarker`/outline sprites only if you also read `SaveSlot+3405+loc` (visited) / `+3422+loc` (revealed).
4. Optional finer dot inside large regions (the three OW* overworld areas): linear-interpolate player world pos (entity+0xDC/+0xE0) within the
   current level's bounds around the MinaPos anchor; no game data gives the bounds in map pixels, so tune empirically (region bbox can be taken from the
   outline sprite images 19..34 + the cel placement, not decoded yet).
5. Unexplored areas: the game fades/dims locations by `SaveSlot+3405/3422+loc`; replicate by darkening the 64x48 slices or skip.

## 9. Files produced

- `analysis\tools\ycd.py ycpak.py wflz.py lookup3.py anb_images.py anb_sprites.py anb_worldmap.py bake_map.py` (decoders, tested),
  `dis_annot.py` (disassembler with string annotation, run under WSL), `rdva2.py`.
- `analysis\_out\` baked world-map PNGs + locators (derived from game data: keep local).
- `analysis\_x\` scratch extraction (sheets, dots overlay `worldmap_dots.png`, per-image PNGs): keep local.
- `analysis\_allentries.txt`: every entry in every pak (`pak size name`).
