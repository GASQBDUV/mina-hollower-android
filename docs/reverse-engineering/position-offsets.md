# Mina the Hollower (arm64 Mach-O): position / room / location offsets
Static analysis only. Addresses are vaddrs in the Mach-O. CERTAIN = read directly in disassembly of several functions; LIKELY/GUESS noted.

## 1. Player world position (x,y,z floats)

Chain: Mina* (Player/GameComponent) -> [Mina+0x10] = ycEntity* -> floats at entity+220/224/228 (0xDC/0xE0/0xE4) = WORLD position. (CERTAIN)

    ycEntity* e = *(ycEntity**)(mina + 0x10);
    float x = *(float*)(e + 0xDC), y = *(float*)(e + 0xE0), z = *(float*)(e + 0xE4);

Evidence:
- Player::WriteSession(SessionPlayerStateData&) @0x1009d753c: `ldr x8,[x0,#16]; ldr s0,[x8,#228]; ldur d1,[x8,#220]; stur d1,[x1,#12]; str s0,[x1,#20]` -> saved player position = entity+220..228 (stored at SessionPlayerStateData+12/16/20). Same read in Player::OnRoomTransitionEnd @0x1009da40c and RoomManager::NewRoom @0x100a794c8 (`ldr x12,[x12,#16]; ldr s0,[x12,#228]; ldur d1,[x12,#220]`).
- GameComponent::GameComponent(ycEntity*,GameComponentDesc*) @0x1009b2fa4: `stp x1,x8,[x0,#16]` -> GameComponent+0x10 = owner ycEntity*.
- ycTransform is 40 bytes: +0..16 rotation, +16..28 scale, +28..40 position (ycTransform::Composite @0x1001e4c1c uses [x0+28] etc.).
- ycEntity layout (ycEntity::SetWorldTransform @0x1001cad0c, RecalcWorldTransform @0x1001ca31c): LOCAL transform at entity+128 (pos at +156/160/164), WORLD transform at entity+192 (pos at +220/224/228), [entity+16] = parent, [entity+24] = flags.
- Writing: PlayerUtil::SetPos @0x100a54580 writes the 40-byte local transform at entity+128 and then calls the refresh. For teleporting write local pos (entity+156/160/164) then refresh/RecalcWorldTransform; the +220 world copy gets overwritten otherwise.
- Mina+324/328/332 (0x144..) = facing, saved in WriteSession (`add x8,x0,#324 ... str d1,[x1,#24]`, session +24..32). Mina+348 saved at session +36.

### Finding the Mina pointer from a global (LIKELY, layout ambiguous)
g_playerUtil @0x105061d58 holds the player list. RoomManager::BeginRoomTransition/EndRoomTransition/NewRoom use Player* p = *(Player**)(0x105061d58 + 8 + 8*idx), count = *(int*)(0x105061d58+0x1c) (@0x100a78cd4 `ldrsw x8,[x22,#3444]`). PlayerUtil::SetPos/GetNearestPlayerPos index it as [g+16+8*i] with count [g+24]. These disagree by 8 bytes, so verify at runtime (first entry is at g+8 or g+16). Both then use entity=[p+16] and pos at entity+220. Since you already have Mina (vtable 0x100dfe6d0), just use +0x10.

## 2. Current room / location

### RoomManager (instance = *(0x105055fe0)) (offsets CERTAIN, names are mine)
There is NO room-id integer; rooms are identified by AABB.
- RM+512..+524: current room AABB center xyz; RM+524..+536: half-extents xyz. Set in RoomManager::EndRoomTransition(bool) @0x100a784f8 (`str q0,[x21,#512]; str x8,[x21,#528]`) and InitRoomCamera @0x100a79658.
- RM+536..+560: PREVIOUS room AABB (copied just before, @0x100a784dc..0x100a784ec).
- RM+396..+420: target room AABB (center +396, half-extents +408) stored by BeginRoomTransition(int playerIdx, ycAABB, ycVec3, ycVec3, bool) @0x100a7625c (`str q0,[x9]` with x9=RM+396; `str w23,[x21,#444]`). RM+444 = index of the player that triggered it; RM+464/468/472 and RM+64/68/72 = the two vec3 args (entry position / direction).
- RM+296 (byte) = busy flag checked first by CheckEntityTransition @0x100a7610c (`ldrb w8,[x0,#296]`). Proper query: RoomManager::IsRoomTransition() @0x100a7b448.
- RM+580+12*i: cached player i position (floats), written in NewRoom @0x100a79494..0x100a794d8.
- RM+16 -> game object G; [G+80] = world state W; [W+168] = current level (ycTileLevel2*). Level room table: 32-byte entries at level+1112 (+0 type 1/2/4, +4..+16 bounds compared against RM+512..532), count [level+1624] (NewRoom @0x100a7930c/0x100a79348). RM+256 -> object with a copy of the room AABB at +27088 (@0x100a79664).
- RM+0x12c/0x130 timers (yours) unrelated.
Practical room identifier: hash the AABB at RM+512..+536 (it changes exactly on EndRoomTransition), or use the level pointer [[RM+16]+80]+168.

### SaveSlot / map location (use CERTAIN, pointer LIKELY)
- Active SaveSlot pointer: *(SaveSlot**)(0x105061938) = g_saveManager+24 (MapScreen::ChangeLocation @0x1003e843c; MapData::SetLocation @0x1003e2190: `adrp x8,..; ldr x21,[x8,#2360]` resolves to 0x105061938).
- SaveSlot+584 (0x248): current area id. MapData::SetLocation(SaveSlot*,ycStackString&,...) @0x1003e20b8 does `ldr w8,[x21,#584]` and linearly searches a 198-entry table (stride 136 bytes, at 0x10504c3f8+... "s_defs", runtime-built so zero in the file) comparing entry+8 == id; entry+12 = map LOCATION index (negative = none), entry+20 = parent entry index, entry+24 = name string ptr. Other area-ish ids: SaveSlot+528, +540, +560, +592 (fallbacks used under flags +76 / +560, i.e. warp/transition states; +540 is the "other/previous" area at @0x1003e2264). GUESS which of 528/540 is which.
- SaveSlot+3176 bit2: map-owned flag, checked before showing icons.
- SaveSlot+3405+loc (byte, loc 0..17): location discovered/visited. SaveSlot+3422+loc (byte): location map-revealed/owned. Used by MapScreen::ChangeLocation @0x1003e8444/0x1003e85f4 and IsLocationComplete @0x1003e9564.
- 18 locations (0..17); 18 = "none" (`cmp w1,#18` in ChangeLocation/IsLocationComplete).

### MapScreen
- MapScreen+340 = current displayed location index (0..17, 18 none) (ChangeLocation @0x1003e8454 `ldr w8,[x0,#340]`, store @0x1003e8560). +336 = timer reset to 0 on change. +248 = array of 464-byte per-location widgets (flag at +341). +345/+346 = flags (checklist/zoom). +348+4*loc float = per-location fade/alpha.
- Checklist counters: MapScreen+416 + 204*k + 12*loc ({collected, ?, total}) for 17 categories k; IsLocationComplete @0x1003e9554 returns true when collected>=total for all.

## 3. Visited rooms / MapLevelSave
- s_recordDef_MapLevelSave @0x105062260 lies in __DATA zero-fill (built at runtime), so its field layout could not be read statically (same for SessionRoomData 0x105062488, SessionRestorePointData 0x1050633c0). To get it: disassemble the static initializer that fills 0x105062260, or inspect at runtime.
- Static evidence of what tracks exploration: per-location bytes SaveSlot+3405+loc and +3422+loc, and the MapScreen checklist counters (MapData::FillChecklist @0x1003e2948 fills Checklist[18][17] from ItemCollection + SaveSlot). Per-room visited map tiles are probably MapLevelSave records inside SaveSlot (NOT located; GUESS).
- SessionPlayerStateData (Player::WriteSession): +12/16/20 pos, +24/28/32 facing (Mina+324..332), +36 = Mina+348, +72.. = Player+3984.., +96 = Player+4048, +108 = Player+8720.

## Quick answers
1. Position: `*(float*)(*(void**)(mina+0x10)+0xDC/0xE0/0xE4)` (world); local at +0x9C/0xA0/0xA4. Teleport via PlayerUtil::SetPos (0x100a54580) or write local pos then refresh.
2. Room: RoomManager+512..+536 AABB (no int id). Location: SaveSlot+584 area id -> table -> location index; UI mirror MapScreen+340. SaveSlot* = *(0x105061938).
3. Map visibility: SaveSlot+3405+loc / +3422+loc, MapScreen checklist counters; per-room MapLevelSave layout still unknown.
