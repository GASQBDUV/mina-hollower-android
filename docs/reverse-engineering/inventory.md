# Mina the Hollower: inventory data (SaveSlot layout, enums, icons)

Static analysis only. Machine-readable result: `analysis\inventory-map.json` (fields, 5 weapons, 14 sidearms, 61 trinkets with English name/description and icon PNG, upgrade bits, counters).
Icon paths in the JSON are relative to `analysis\_ui` (e.g. `sprites/UI_HUD_trinketIcons/putridClump.png`). All derived from the user's own game copy; keep local.

## How the layout was found (verified method)
- The save file `data\save\saveData.yc` is a TEXT YCD ("[YCD Version: 1]") with real field names (`fHealth: 80`, `sVialCount: 3`, ...). `m_rSlots` holds 10 SaveSlot records; slot 0 is the user's real game, slots 1-9 are empty defaults.
- The record-def of SaveSlot is built at runtime by code at ~0x100c14000..0x100c21xxx (one block per field: name string, then `str wN,[fieldobj,#48]` = type code, `[+56]` = array count, `[+72]` = byte offset in SaveSlot). Parsed all 213 fields from the disassembly (dump: scratchpad only). Offsets known earlier (sArea 584, mapSeen 3405, mapChecklist 3422, playerUpgrades 3176) match exactly, which validates the method.
- Type codes: 4=i32, 8=u32, 9=u64, 10=f32, 15=f64, 11=u8/bool, 12=char[16] string; array count at +56. SaveSlot size = 4552 (slot array stride in PlayerDataUtil: `*4552 + 1664`).

## Verified offsets (all against code and/or the real save)
| field | off | type | notes |
|---|---|---|---|
| fPlayTime / Cleared / Total | 8/16/24 | f64 | seconds. Save: 15588.75 = 4h19m |
| playerName | 32 | char[16] | "Greger" |
| sLifeSparks / sLifeSparksFlags | 80 / 84 | i32 / u32 | current / container bitmask (GetSparkSlotMax = popcount(84)) |
| fHealth | 288 | f32 | current health |
| uHealthUpgradeFlags | 304 | u32 | max HP = 8*popcount, x1.25 if playerUpgrades&32 (code at 0x100a0dffc). Save: popcount 10 -> 80 = fHealth |
| sWeaponName | 312 | char[16] | "Whip","Hammer","Daggers","BusterBat","Shield" |
| sSubWeaponName / 2 | 328 / 344 | char[16] | sidearm string = kItemType name without prefix (see JSON `saveString`). Second only with playerUpgrades bit4 |
| sSubWeaponCount | 364 | i32 | current Joules |
| uSubWeaponUpgradeFlags | 368 | u32 | max Joules = min(99, 10*popcount) (code 0x100a0e098) |
| sPlayerStatLevel[4] | 372.. | i32 | [0] Attack, [1] Defense, [2] Sidearms (GetStatLevel); [3] unknown |
| sNextLevel | 388 | i32 | bones for next level |
| sVialCount / sVialCountFlags | 392 / 396 | i32 / u32 | current / pouch bitmask, max = popcount (save: 3/7) |
| sMoney | 408 | i32 | bones (2423 in save) |
| sMoneyFound | 424 | u64 | lifetime bones found |
| uBonesSpent / Shop | 432 / 436 | u32 | |
| sBonestone | 492 | i32 | banked Bonestone currency (1536) |
| sKeysFlags / sKeysSpent / sLockFlags | 496 / 504 / 512 | u64 / i32 / u64 | keys held = popcount(496) - i32@504 (save: 6-6=0, popcount(512)=6 too) |
| sArea | 584 | u32 | area hash |
| sDeathCount | 592 | u32 | 21 |
| trinketCollectedOrder | 892 | u32[61] | 1-based pick-up order |
| trinketCollected | 1136 | u32[61] | nonzero = owned. idx = itemId-134 (SetItemCollected @0x10088b930: `sub w9,w12,#134; str w1,[x8,#1136]`) |
| trinketEquipped | 1868 | i32[6] | stores ITEM IDS (134..194). Save has 152 = Fly Bait (idx 18, which is also owned) |
| trinketTime | 1896 | f64[61] | seconds equipped |
| trinketNew | 2388 | u8[61] | |
| subweaponSeen | 2449 | u8[14] | idx = itemId-21 |
| statWeaponUse / statSubweaponUse | 2992 / 3012 | u32[5] / u32[14] | |
| boneStoneFlags | 3080 | u32[7] | collected bonestone pickups, count = sum popcount (save: 20) |
| weaponPickup | 3108 | u32[5] | bit (t-2)%3 of word (t-2)/3 for weapon item types 2..16 |
| playerUpgrades | 3176 | u32 | bit table below |
| fishCollectedFlags | 3440 | u32 | |
| mapSeen / mapChecklist | 3405 / 3422 | u8[17] | |

## Enums
- Item table (static, in the binary): 196 entries * 104 bytes at 0x100e52ea0 (+104*itemId; Items::GetItem loops it from 0x100e52eb0). Each entry has the localisation key, description key, icon anb and sprite name. itemId == kItemType (names `kItemType_*` recovered from the table).
- Weapons: itemIds 2..16 (3 each: base, upgrade 1, upgrade 2): Whip 2 "Nightstar", Hammer 5 "Blaststrike Maul", Daggers 8 "Whisper and Vesper", BusterBat 11 "Battery Buster", Shield 14 "Guardian Casket". Icons `UI_HUD_equipmentIcons/{whip,hammer,daggers_f0,buster,shield}`.
- Sidearms: itemIds 21..34 (index = id-21): ThrowingAxe=Volt Hatchet, ThrowingKnife=Gyro Dagger, WarpPortal=Recall Disc, ChargeLantern=Dynamo Lantern, ShieldBash=Drill Driver, ChemicalClackers=Bounding Bombs, Bicycle=Iron Steed, HollowersRocks, FogThrower, LeashedPhantom=Beckoning Collar, MistShift=Mist Jar, HauntedUrn=Gnawing Ghosts, BarrierParasol=Deflector Parasol, FishingRod. Icons in `UI_HUD_equipmentIcons`.
- Trinkets: itemIds 134..194, array index = id-134, 61 total; English `ring_name_N` / `ring_desc_N` keys from `menus/menus.stb.yc` (in global_startup.pak.yc; parsed as key\0 + 11 language strings). Icon sprite per trinket in JSON (`UI_HUD_trinketIcons`, Beastium 34-39 in `UI_HUD_trinketBeastIcons`). The ring_name_N number is NOT the index; use the JSON.
- playerUpgrades bits: 0 Memory Goggles (candleVision), 1 World Map, 2 Enhanced Map, 3 All-Seeing Skull, 4 Dual Sidearm Permit, 5 Cloak Vitality Vest, 6 Cloak Safety Shroud, 7 Custom Fitting, 8 Joule Alembic, 9 Sidearm Recoverer, 10 Sidearm Duplicator, 11 Training Dummy, 12 Phonograph, 13 Fishing Rod, 14 Angler's Raft, 15 Gilded Rod, 16 Disturbing Dance. (Save value 2 = World Map only.) Icons: underlab ones in `UI_HUD_underlabUpgradeIcons`; no sprite found by name for bits 4-7: use `UI_HUD_sideArmUpgradeIcon/idle` and `UI_HUD_armorIcon/armor1..3`.
- Bonestone items 51..62 (`UI_HUD_bonestoneIcons`: dust, chip, chunk, whole, perfect, tower).

## Verified against the user's save (slot 0)
Health 80 = 8*10 roses; vials 3/popcount 3; keys 6 collected/6 spent; sidearm ShieldBash (idx 4) has use count 31 and seen flag; 7 seen sidearms match 7 non-zero use counts (idx 0,1,4,6,7,10,12); trinketEquipped 152 is in trinketCollected; stat levels [4,3,3,3]; mapSeen/mapChecklist layout as before.

## Guessed / unresolved
- Sidearm index order (id-21) is consistent with the seen/use arrays but not independently proven by a code path reading subweaponSeen.
- Weapon level (upgrade state): weaponPickup bits are verified, but how the game derives "level 1..3" together with weaponPickupStart/weaponChestStarters (SaveSlot::GetWeaponLevelFromPickup @0x100a80cc8) is not decoded. In the save Whip has weaponPickup=4, Start=2.
- Trinket slot count: code uses popcount(trinketEquipMaxFlags @2384) but the save has that field 0 while one trinket is equipped (maybe a base slot or stale value). In the UI just draw non-zero trinketEquipped entries (max 6).
- sPlayerStatLevel[3], sVialCountExtra, fHealthSludge/fRecovHealth, bSubWeaponFlip, trinketState/Progress: meaning guessed.
- Completion % and area-found counts: not read from SaveSlot directly. MapData::FillChecklist @0x1003e2948 builds Checklist[18 locations][17 categories] of {collected,?,total} (12 bytes each, 204 per location, 3672 bytes) from the ItemCollection table (361 entries * 80 bytes at 0x100e3f180: +24 itemType, +28 flag bit index, +76 linked entry) using Items::GetFixedLocation (0x10088c498) and Items::IsItemCollected (0x10088bbb0). Total % = sum collected / sum total (map_allTotal). Easiest at runtime: reuse the live MapScreen checklist counters (MapScreen+416+204*loc+12*cat) or call FillChecklist with a 3672-byte buffer (untested); a full static port needs the location function decoded.
- Max sparks may have a base added to popcount(@84). Play time on the HUD may be from SaveMasterData.fTotalPlayTime (not in SaveSlot).
- The tracker already finds the active slot via `*(u64*)0x105061938`; all offsets above are relative to that pointer (runtime pointer is the machismo-relocated slide of the same struct).
