# Colored level art (tools/bake_levels_color.py)

Run: python tools/bake_levels_color.py "<data dir>" _levels [name-substr ...]   (needs _levels/<name>.json from bake_levels.py; ~20 s for all 54 levels)
Outputs per level in _levels/: <name>_color.png (opaque RGB on bg 18,18,28, same pixel frame as _preview.png, 1 px = 1 authoring unit),
<name>_color_a.png (RGBA, transparent where nothing drawn), <name>_color_half.png (half resolution). Plus color_palettes.json (palettes used per level).
Local only (derived from game data). Totals: 54 levels, ~11 MB each variant; e.g. hub 7008x2720 (641 KB), mansion 5856x4336 (753 KB), bayou 3360x1776, crypt_overworld 3936x3464.

## VERIFIED
- Every layer of every tlb names its palette in TileLayer+48 (string array). All layers of all 49 tile levels name `palettes/global.pal.yc`
  (global_startup.pak.yc, 255 RGBA entries at indices 1..255, 0 transparent) except: frozenTrainyardB/Boss/C 1 layer each frozenTrainYardMaelstrom(+Back),
  mansion 6 layers environments/electricPit, introBeach 1 layer endingExplosion/fire1, randomEncounterBG 2 layers effects/randomEncounterBG/0a.
  So the tileset atlases (8-bit) use the ONE global palette; there is no per-region static palette in the .tlb. The textures array has 1 atlas per level.
- Rendering the hub with global.pal gives the right look (checked visually at Ossex square, authoring (5190,-1621) = pixel (4262,1221)):
  teal-slate roofs, red-brown brick house, green-roof house, grey cobbles, green lawns, red/blue awnings.
- Layer order: depth ascending (low = back). Flip bits 0x4000 = horizontal, 0x8000 = vertical (layer value & 0x3fff = tileInfo+1).
- global.pal indices 85..111 are pure (0,255,0) placeholders; 112+ transparent. palettes/environments/waterTileset_<x>.pal.yc replace only the water ramp
  (global idx 2..8; all other entries are green/black filler). Applied to layers named WATER*/DEEP_WATER* (not *DECOR/FLOWER/MOON): bayou water becomes purple (matches the game's toxic bayou).
- The other ~395 palettes/*.pal.yc are all full global-palette variants swapped at runtime (flash/dark/frozen/per-region like cryptOverworld0, overworldSouthDark,
  global_boneBeachOW, sleepyHollowDark*, globalOWWest_Dark1, waterBurrow_*, splashWater*, locationPlate*): they only differ in a handful of entries (cryptOverworld0: idx 11,19,22,26,33,70,75,255).
  The runtime region palette is not stored in the level; not applied here (static bake = default global palette).

## GUESSED / limitations
- Water layers: palette per level is chosen by me (WATER_BY_LEVEL in the script, by name similarity: bayou->waterTileset_bayou, hub->hub, ...). Only bayou has WATER* layers in the shown set; others unverified.
- Skipped layers (heuristic, SKIP list): parallax != 0 (BG sky/clouds/mountains/foreground FG_*), COLLISION/MOVE_/FOG/PIT_BLACK/BLOCK/VISTA/SHADOW, SPARKLES, KEY_REWARD, FLIP_ON/OFF, TAVERN_*, TRAIN*, BOSS*, ModeWeather,
  WLAYER_* (water-level / world-state variants: stacking them would be wrong, which one is active is runtime state), *MOON* (night variants), WINDOWBREAK, *_BURROW.
  Consequently some areas (bayou water-level rooms) appear as dark voids; black rectangles elsewhere are opaque palette idx 1 (0,0,0) interior fill.
- Animated tiles (tileInfo textureIndex 0xFFFF) are not drawn. Mode-dependent layers (DETAIL_!ModeIntroComplete) drawn as-is.
- Runtime palette modifications (SetWorldRegionPalette, MothNPC::EnableRoomPalette, ycRenderPalette) not decoded; day/night/weather tint not applied.
- The tan blocks in mansion and the purple mansion exterior are as the data renders; not compared to the in-game look.
- Stray far tiles can enlarge the frame (crypt_overworld has a stray pixel at the bottom-right), inherited from the class-map frame.

## Files
tools/bake_levels_color.py; _levels/*_color.png, *_color_a.png, *_color_half.png (54 each); _levels/color_palettes.json
Baked incl.: overworld/hub, hub_overworld_south/east/west, crypt_overworld, core/mansion, core/bayou, all other core/overworld/extra/ending levels with collision layers.
