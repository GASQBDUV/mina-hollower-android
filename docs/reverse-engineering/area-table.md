# Area table (198 entries @0x10504c3f8) - static reconstruction

Output: `area_table.csv` (columns: table_idx, area_hash_hex, name, loc_index, loc_name, field16, theme_idx_field20, area_name_key, music, f40).

## How it was recovered (VERIFIED)
- Initializer = `__cxx_global_var_init.93` @0x100c94064, called from `_GLOBAL__sub_I_AreaDef.cpp` (@0x100c94060). It writes all 198 x 136 B entries as constants.
  Reconstructed by a mini emulator over the arm64 code (tools: scripts in C:\Claude: emu.py, dump.py, gen.py; all 26928 bytes covered).
- **entry+8 (area id) is 0 in the static init.** `Game::Init` @0x1009a3730 fills it at startup: `id = hashlittle(entry+0 name string, strlen, initval 1337)`
  (hash fn @0x1001b6d48, same lookup3 as lookup3.py). `Area::GetIdx(const char*)` @0x10056d648 hashes a name and linear-searches +8, so id == hash(name) CONFIRMED.
  CSV hashes = `lookup3.hashlittle(name, 1337)`; no collisions among the 198.
- Game::Init also copies inherited fields from a second 136-B table (the "biome/theme" AreaDef table at 0x10504bd10, index = entry+20: 0 None, 1 Bayou, 2 Crypt, 3 BoneBeach, 4 Septemburg, ...)
  into any empty palette-string fields (+56..+104, +32/+40 block, +48, +52, ...).

## Entry layout (136 B)
| off | meaning | confidence |
|---|---|---|
| +0 | name string ptr (e.g. "Bayou_1"); id source | verified |
| +8 u32 | area id = hashlittle(name,1337), set at Game::Init | verified |
| +12 i32 | map location index 0..16; 18 = none | verified (SetLocation, ChangeLocation) |
| +16 u32 | unknown small int (15 Bayou, 1 Crypt, 9 BoneBeach, 14 Astral...; same per region, 0 for a few) | unknown |
| +20 u32 | theme index into the biome AreaDef table (above) | verified (Game::Init uses it as parent) |
| +24 | localization key `area_name_*` (display name text) | verified (strings) |
| +32 | music cue string (`m_*`), may be inherited | likely |
| +56..+104 | palette strings (ripple, waterBurrow, splashWater, playerWaterBurrowExpiring palanm...) | likely |
No level file name / spawn point in the entry (rooms are located by AABB, not by this table).

## Location index -> name (final)
Source: static array of 17 records (stride 48 B) at 0x100dbfb90 in __const, records `{name, "map_location_*" key, 6 ints, plate palette}` in this order,
which is the order the MapScreen widgets are built with (index i = record i):

| idx | name | areas that map to it (from the table) |
|---|---|---|
| 0 | astralOrrery | Astral_* (10) |
| 1 | bayouA | OverworldBayou* , OverworldFishingHole, BayouOverworld_TrainStation (7) |
| 2 | bayouB | Bayou_1..7, Bayou_Tower (11) |
| 3 | boneBeachA | OverworldBoneBeach*, BoneBeach_OverworldPath, BoneBeach_Tower, OverworldBoneMinerTown (7) |
| 4 | boneBeachB | BoneBeach_1..16, AreaPeek (18) |
| 5 | cryptA | OverworldCrypt* (7) |
| 6 | cryptB | Crypt_*, CryptRoad*, Crypt_Tower (10) |
| 7 | easternFields | OverworldEast* (7) |
| 8 | frozenTrainyard | FrozenTrainyard_* (14) |
| 9 | introBeach | IntroBeach* (7) |
| 10 | mansionA | Mansion, Mansion_GrandHall, HubMansion_* (12) |
| 11 | mansionB | Mansion_Ballroom, Rafters, Gallery, ... Mansion_3 (19) |
| 12 | ossex | Hub*, Train_* (32) |
| 13 | septemburgA | Septemburg_Woods, Crossing, RailCave, Schoolhouse... (8) |
| 14 | septemburgB | Septemburg_WitheredFields, OldBarn, CrowTown, DarkWoods, Tower... (10) |
| 15 | southernFields | OverworldSouth*, OverworldDripCave (7) |
| 16 | westernFields | OverworldWest* (6) |
| 18 | none | None, Overworld_PoppitsCave, Test, PostCredits, IntroSea (6) |

(The alphabetical guess was wrong only for easternFields: it is 7, not 12; ossex is 12; southern 15; western 16.)

## Confidence
- VERIFIED: table contents, id = hash(name,1337), loc index per area, location array order (astral,bayouA,bayouB,boneBeachA,boneBeachB,cryptA,cryptB,easternFields,frozenTrainyard,introBeach,mansionA,mansionB,ossex,septemburgA,septemburgB,southernFields,westernFields).
- Cross-check: area-name groupings land exactly on these names (all Crypt_* -> 6, OverworldEast* -> 7, FrozenTrainyard -> 8, IntroBeach -> 9, Hub -> 12, OverworldSouth/West -> 15/16, Astral -> 0).
- NOT directly verified: that MapScreen::Setup indexes the 17-record array with i == location index (inferred; it matches the area groupings in every case, and the array is the only 17-name list in the binary).
- A/B semantics (e.g. A = overworld region, B = dungeon for bayou/boneBeach/crypt/septemburg) follows from the index order; mansionA = Lionel grounds/Hub mansion, mansionB = Radiant Manor.
- Several areas map to 18/none (IntroSea, PostCredits, Test, PoppitsCave): hide the dot.
