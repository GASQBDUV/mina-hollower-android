# Level authoring frame -> runtime world coordinates

## Result (VERIFIED in code + consistent with live data)
There is a global **world unit scale of 0.1**. No per-level or per-region offset exists.

    runtime_x =  authoring_x * 0.1          authoring_x = runtime_x * 10
    runtime_y = -authoring_y * 0.1          (authoring_y is the bake's layer-frame y, y DOWN)
    i.e. with the bake's JSON (wx, wy = world coords of the baked frame, y up, "px_per_unit=1"):
    runtime = (wx, wy) * 0.1        and    wx = runtime_x*10, wy = runtime_y*10
    PNG pixel: px = runtime_x*10 - origin_wx ;  py = origin_wy - runtime_y*10

Tile (col,row) of a layer covers runtime x = 0.1*(layer.x + 8*col) .. +0.8, y = -0.1*(layer.y + 8*row) .. -0.8 (tile = 0.8 world units).

## Evidence
1. main() @0x1009ba8a8..0x1009ba8b4: `mov w8,#52429; movk w8,#15820,lsl#16` (=0x3dcccccd = 0.1f) then `str w8,[x9,#3728]` -> global float at 0x100e24e90 (__data, initial file value 1.0). It is the ONLY store to it; it is loaded ~1100 times (every x*scale in the code). My earlier note "scale = 1.0" came from reading the static initial value; wrong.
2. SpawnManager::CreateSpawnPoint @0x100ace02c: `ldr s12,[0x100e24e90]; fmul s8,s0,s12` (x*s) and `fneg s0,s12; fmul s0,y,s0` (y*-s): CoreData pos = (x*0.1, -y*0.1). SpawnPoint::Spawn @0x100ad09b0 passes that to EntityFactory::CreateEntity with parent = World+6184 (a root ycEntity). World::World @0x100b6d694..: root entities get rotation (0,0,0,1), scale (1,1,1), pos 0 -> identity, so entity world pos == spawn pos.
3. TileLevelCollision::TileLayerOverlap @0x1007c2794: col = ((wx - state.x)/s - layer.x@+104)/tileW (s = same global; row uses -s). TileLevelComponent +128..136 (the other offset term) is zeroed in the WorldRegion ctor (@0x100b760c8) and never set -> no region offset. WorldRegion::StartLevel / CreateRegion / AppendChildWorldRegion store only the level name and link lists; no position.
4. Live data: RoomManager+512 box centre (518.4,-137.6) = exactly (5184,-1376)/10, which is a roomBounds entry of levels/overworld/hub (cx=5184, cy=-1376, w=h=256; w/h*0.1 = 25.6 = the "half extents" read). Player (519,-162.1) -> authoring (5190,-1621): hub_overworld ground (class map FLOOR) in the town centre, south part, just below that room; (529,-161) -> (5290,-1610) lands on an adjacent wall tile pixel (player walked ~10 units = 100 authoring px, so within tolerance of a cell edge). Old reading (242.3,-370.5) -> (2423,-3705) is inside hub_overworld_south (x 880..3648, y -3616..-5120) on FLOOR, which fitted no level before.
   Overlays: _levels/_check/hub_class_zoom.png, hub_art_zoom.png, hub_art_overview.png (yellow circles = the two player points; class map shows them on floor at the plaza's south entrance below the big building block), south_zoom.png.
   Note: RM+524..536 value 25.6 equals roomBounds w*0.1, i.e. it is the FULL size (not half) if bake's "w=full width" is right (256x144 = one screen supports full). Player is 117 authoring units below that room's bottom edge (-1504) - between rooms - so treat that RM box as "last room entered" and test with a 10x scale on neighbours too.

## Guessed / not verified
- Exact plaza identity ("big brick manor") not checked on real art (art PNG has no palette); only geometry (floor, building block north of the point).
- Whether anything else (TowerScrollingBg remap, camera) offsets Mina's pos: only in tower levels (not checked); camera is separate (not part of entity world pos).
- Level-room table at level+1112 (32 B entries) are presumably roomBounds*0.1 too (centre match confirms centre only).

## Minimal baked-JSON change
Add to every level JSON/index entry: `"world_scale": 0.1` (runtime = authoring*0.1, y negated is already in wx/wy since wy is y-up). Then:
    px = (rt_x/world_scale - origin_wx) * px_per_unit ;  py = (origin_wy - rt_y/world_scale) * px_per_unit
Level lookup by runtime pos: compare rt/0.1 against origin_wx..+width_px, origin_wy-height_px..origin_wy (levels overlap in the global frame, so use WorldRegion level names, WorldRegion+0x1c8, to disambiguate).
