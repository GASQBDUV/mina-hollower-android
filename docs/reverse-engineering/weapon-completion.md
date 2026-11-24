# Weapon level + per-area completion (static analysis)

Machine-readable: `analysis\completion-map.json`. All offsets are bytes into SaveSlot (ptr = `*(u64*)0x105061938`). Derived from the user's own game copy and validated against `data\save\saveData.yc` slot 0.
Helper tools written: `analysis/tools/blxref.py` (bl xrefs), `offscan.py` (ldr/str imm-offset xrefs), `dump_ic.py` (ItemCollection reader), `comp.py`/`gen.py` (totals + JSON generator).

## 1. Weapon level

### Verified from code
- `weaponPickup`@3108 u32[5] (bit k of word w = tier-k item of weapon w collected; item type = 3w+2+k), `weaponPickupStart`@3128 u32[5], `weaponChestStarters`@3148 u32[5], `starterWeaponType`@3168 u32.
- New game (`ShipIntroBossCutscene::InitState` @0x100675a3c..a60): `weaponPickupStart[0]=2; weaponPickup[0] |= 4; starterWeaponType=0`. Taking a weapon from the chest (`WeaponsChestMenu::InitState` @0x1007a2d3c..d54): `start[w]=2; pickup[w] |= 4; starterWeaponType=w`. `CheatManager::ApplyCheatWeapon` @0x100210a9c does the same. So in normal play start is always 2: the weapon you obtain is stored as bit 2 and counts as level 1.
- `Player::CanUseWeaponLevelAbility(weapon, L)` @0x100a49fd4 (the authoritative gate for abilities):
  ```
  if (L == 1) return true;
  k = L - 1;                                    // 1 or 2
  s = weaponPickupStart[weapon];
  bit = k - ((s != 0 && s >= k) ? 1 : 0);
  return (weaponPickup[weapon] >> bit) & 1;
  ```
  With s=2: level 2 <=> bit0, level 3 <=> bit1. With s=1: L2 <=> bit0, L3 <=> bit2. With s=0: L2 <=> bit1, L3 <=> bit2.
- `SaveSlot::GetWeaponLevelFromPickup(weapon, tier)` @0x100a80cc8 maps a world pickup tier (0..2) to the effective upgrade index (0 = base, 1 = upgrade 1, 2 = upgrade 2): `if (s == tier) return 0; if none of the 3 tiers collected return 0; return tier + (s > tier ? 1 : 0)`. `Player::InitState` @0x1009f2a7c uses it with `Weapons::GetWeaponTypeFromItemType` / `GetUpgradeLevelFromItemType`; equipped item = base itemId + result (base ids from table 0x100e176c0, stride 32). Consistent with the formula above.
- Weapon owned (as used by the chest menu @0x1007a1acc): `(weaponPickup[w] >> weaponPickupStart[w]) & 1`.

### Formula for the panel
```
owned(w)  = (pickup[w] >> start[w]) & 1
canL(w,L) = L==1 || ((pickup[w] >> (L-1 - ((start[w]!=0 && start[w]>=L-1)?1:0))) & 1)
level(w)  = !owned(w) ? 0 : canL(w,3) ? 3 : canL(w,2) ? 2 : 1
```
Upgrade names: level 2 = `upgrade1`, level 3 = `upgrade2` in inventory-map.json (equipped item id = base + level-1; base ids 2,5,8,11,14).
Validation: save has Whip pickup=4, start=2 -> owned (bit2), canL(2)=bit0=0, canL(3)=bit1=0 -> Whip level 1. The other 4 weapons have pickup=0 -> not owned. SaveSlot has no equipped-tier field (only sWeaponName@312), so level is derived as above.
Guessed: no code enforces L3 requiring L2; formula takes the highest available. Which world entry is "Dread Mace" etc. differs from level order when start=2, so use the formula, not item names.

## 2. Per-area completion

### Verified: how the counts are built (`MapData::FillChecklist` @0x1003e2948)
- Buffer is `Checklist[18 categories][17 locations]` of `{u32 collectedWithPending, u32 collectedSaved, u32 total}` (12 B; cell = base + 204*cat + 12*loc; 3672 B). (inventory.md had the indexes swapped.) MapScreen keeps it at MapScreen+416.
- Source table: ItemCollection, 361 entries * 80 B at 0x100e3f180: +24 itemType, +28 flag/bit index (i32, -1 none), +32 location (18 = none), +36 sub-index, +44 hash (special-case id), +48 byte flags (bit0 = excluded from checklist), +76 link (runtime = own index; only RandomizeItemWarps changes it; zero in the file image).
- Per entry i: skip if byte[+48]&1; skip if type == 162; skip if location > 16; cat = `MapData::GetItemTypeToChecklist(type)` @0x1003e285c; `total += 1` (type 18 HealingVialFirst adds 3 and its collected state is the first three bits of sVialCountFlags); `collectedSaved += IsItemCollected(i, pending=false)`, `collectedWithPending += IsItemCollected(i, pending=true)` (pending = items picked up this session, ids in SaveSlot+680..752 / +760..880; a tracker can use saved bits).
- Location = `Items::GetFixedLocation` @0x10088c498 = entry+32 with overrides on the +44 hash: 0x7FE59403 and 0x17C2C9D8 force ossex(12) (entry 322 BellowsBustle trinket, entry 243 key flag 36; already counted under ossex below); 0x4A4C7C3A (entry 263 LaceGlove) moves to westernFields(16) when SaveSlot byte 642 bit0 is set and byte 3404 == 0 (dynamic, not modelled).
- `StatusMenu::GetCompletionPercent` @0x100af0050 calls FillChecklist into a 3672-byte stack buffer, then `MapData::GetCompletionPerc` @0x1003e2c34 (sums all cells with some exclusions; exact rule not decoded).
- Area complete (`MapScreen::IsLocationComplete` @0x1003e9554, verified): `mapSeen[loc] (u8@3405+loc) && playerUpgrades@3176 bit2 (Enhanced Map) && mapChecklist[loc] (u8@3422+loc) && for cat 0..16: collected >= total` (cat 17 not tested). mapChecklist is recomputed by `MapData::UpdateSaveChecklists` @0x1003e1de4 from gamestate bits in slot+656 and ANDed with mapSeen, so just read the byte.
- Guessed: the HUD ItemChecklist (`ItemChecklist::Update` @0x1002fdb84, vectorised) shows per-location x/y as the sum over categories; I take collected from the saved column.

### Categories and SaveSlot tests (`Items::IsItemCollected` @0x10088bbb0, verified per case)
| cat | meaning | item types | test |
|---|---|---|---|
| 0 | Health Rose | 69 | uHealthUpgradeFlags@304 bit `flag` |
| 1 | Trinket | 134..194 except 162 | trinketCollected@1136+4*(type-134) == 1 |
| 2 | Key | 63 | sKeysFlags@496 (u64) bit `flag` |
| 3 | Weapon pickup | 2..16 | weaponPickup@3108 word (t-2)/3 bit (t-2)%3 |
| 4 | Joule box | 68 | uSubWeaponUpgradeFlags@368 bit `flag` |
| 5 | Armor upgrade | 79,80,81 | playerUpgrades@3176 & 0x20/0x40/0x80 |
| 6 | Vial pouch | 71, 18 | sVialCountFlags@396 bit `flag`; type 18 = bits 0..2 |
| 7 | Trinket slot | 72 | trinketEquipMaxFlags@2384 bit `flag` |
| 8 | Life spark | 70 | sLifeSparksFlags@84 bit `flag` |
| 9 | Underlab upgrade | 74..86, 102, 103 | playerUpgrades@3176 & mask (table 0x100d4f110; masks in JSON) |
| 10 | Bonestone | 52..56 | boneStoneFlags@3080: u32 word flag>>5, bit flag&31 |
| 11 | Fish | 104..119 | fishCollectedFlags@3440 bit (flag+1) |
| 12 | Fishing upgrade | 87,88,89 | playerUpgrades & 0x2000/0x4000/0x8000 |
| 13 | Lock | 64 | sLockFlags@512 (u64) bit `flag` |
| 14 | 2nd sidearm HUD | 91 | playerUpgrades & 0x10 |
| 15 | Dance | 90 | playerUpgrades & 0x10000 |
| 16 | Ticket pass | 94 | u8@448 != 0 |

Cat 17 gets no items. `completion-map.json` lists per location and category `total` and `ids` (flag index for bit cats, trinket index = type-134, weapon tier index (type-2), fish bit index, or itemType for playerUpgrades cats; "first3" for the intro vials).

### Static totals per location x category
Entries with location 18, excluded flag or type 162 are not counted (so the 8 starting roses, starter whip, first 2 joule boxes etc. are absent).

| location | 0 rose | 1 trinket | 2 key | 3 weapon | 4 joule | 5 armor | 6 vial | 7 slot | 8 spark | 9 upgr | 10 bonestone | 11 fish | 12 fishing | 13 lock | 14 | 15 | 16 | sum |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| astralOrrery | 1 | 3 | 3 | 1 | 0 | 0 | 0 | 1 | 0 | 0 | 8 | 1 | 0 | 3 | 0 | 0 | 0 | 21 |
| bayouA | 0 | 4 | 2 | 0 | 1 | 0 | 1 | 0 | 0 | 0 | 4 | 2 | 2 | 3 | 0 | 0 | 0 | 19 |
| bayouB | 1 | 4 | 3 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 7 | 1 | 0 | 1 | 0 | 0 | 0 | 18 |
| boneBeachA | 0 | 3 | 1 | 0 | 1 | 0 | 1 | 0 | 0 | 0 | 5 | 1 | 1 | 3 | 0 | 0 | 0 | 16 |
| boneBeachB | 1 | 2 | 3 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 12 | 1 | 0 | 1 | 0 | 0 | 0 | 21 |
| cryptA | 0 | 2 | 2 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 7 | 0 | 0 | 3 | 0 | 0 | 0 | 15 |
| cryptB | 1 | 4 | 3 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 7 | 1 | 0 | 1 | 0 | 0 | 0 | 18 |
| easternFields | 0 | 4 | 2 | 0 | 1 | 0 | 0 | 1 | 1 | 0 | 7 | 1 | 0 | 4 | 0 | 0 | 0 | 21 |
| frozenTrainyard | 1 | 3 | 3 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 5 | 1 | 0 | 2 | 0 | 0 | 0 | 16 |
| introBeach | 0 | 1 | 2 | 2 | 0 | 0 | 3 | 0 | 0 | 0 | 5 | 1 | 0 | 4 | 0 | 0 | 0 | 18 |
| mansionA | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 1 |
| mansionB | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 4 | 0 | 0 | 1 | 0 | 0 | 0 | 7 |
| ossex | 4 | 15 | 14 | 6 | 3 | 3 | 3 | 3 | 1 | 9 | 13 | 1 | 0 | 5 | 1 | 1 | 1 | 83 |
| septemburgA | 0 | 5 | 3 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 5 | 0 | 0 | 3 | 0 | 0 | 0 | 17 |
| septemburgB | 1 | 1 | 4 | 1 | 0 | 0 | 0 | 0 | 1 | 0 | 10 | 2 | 0 | 1 | 0 | 0 | 0 | 21 |
| southernFields | 0 | 3 | 3 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 6 | 1 | 0 | 2 | 0 | 0 | 0 | 16 |
| westernFields | 0 | 4 | 2 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 5 | 2 | 0 | 3 | 0 | 0 | 0 | 17 |

Overall: roses 10, trinkets 60, keys 50, weapons 14, joule 8, armor 3, vials 10, trinket slots 5, sparks 3, upgrades 9, bonestones 111, fish 16, fishing 3, locks 40, misc 3 = 345 cells.

### Validation vs PC save slot 0
Saved bits mapped through the table reproduce the known counters exactly: bonestones 20 set bits, all 20 present in the table (cryptA 4/7, cryptB 4/7, easternFields 3/7, introBeach 4/5, ossex 3/13, southernFields 2/6); keys 6 (matches sKeysSpent 6); locks 6; trinkets 5 (trinketCollected has 5 ones); roses: flags 9 and 14 beyond the 8 starting ones -> ossex 1/4, cryptB 1/1; world map upgrade -> ossex upgrades 1/9; intro vials 3/3.
Not validated (zero/absent in the save): fish, trinket slots, ticket, dynamic location overrides, the `pending` column.

### Open items
- Exact exclusions in GetCompletionPerc (global %).
- Whether the on-screen x/y excludes some categories.
- When `starterWeaponType != 0`, `Weapons::GetStarterReplacement` @0x100b51970 redirects the starter weapon's tier-2 entry to the Whip-L3 entry (type 4) for cat 3; totals above assume starter = Whip.
