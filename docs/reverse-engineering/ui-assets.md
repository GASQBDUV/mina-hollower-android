# Mina the Hollower: UI assets (fonts, frames, plates, markers) and MapScreen layout

All output lives in `analysis\_ui\` and is derived from the user's own game data: keep it local, never publish/commit.
Regenerate: `python analysis\tools\extract_ui.py "<...>/Resources/data" analysis\_ui` (uses `tools\anbfile.py`, `ycd.py`, `ycpak.py`, `wflz.py`).
`_ui\raw\` holds raw extracted paks entries + scratch (also local only).

## Output layout
- `fonts\<name>.png|.json` : Gothic8px-Regular, Gothic8px-Bold, Gothic7px-Bold, GothicHyroglyph-Regular (CJK fonts not converted).
- `sprites\<anb>\<sprite>[_fN].png` : every frame of every UI/*.anb.yc (89 anbs, ~1180 PNGs) + objects/checkpointMap.
- `plates\locationPlate_<Loc>.png` : 64x24 sample plate in each of the 17 palettes (+ `default`).
- `index.json` : `<anbTag>/<sprite>` -> {png,w,h,pivot,anchor,cel,dur,anb,palette}; `<anbTag>/_nineslice` for 9-piece frames; `locationPlate_<Loc>`.
- `contact_*.png` : per-anb contact sheets, `contact_locationPlates.png`, `font_sample.png` (rendered sample string).

## Font format (verified visually: ASCII, Latin-1 incl. a-ring/a-uml/o-uml, Cyrillic, kana present)
`.font.yc` = YCD container. root u32 glyphCount (615 for 8px Regular); root+0x10 -> header, root+0x20 -> glyph array (32 B each).
Header: f32 pixelSize-1, f32 -1.0, f32 lineHeight (13; 12 for Gothic7px), u32 x2 (unknown), u32 atlasW, u32 atlasH, u32 0, u32 pixelSize (8/7/5),
u32 256, u32 atlasBytes (=W*H), u32 0, ptr -> raw 8bpp atlas (only values 0 and 255; 255 = ink). Atlas 263x259 for 8px Regular.
Glyph: u32 codepoint (sorted), u32 x, y, w, h (atlas rect, includes a 2px blank pad on every side), f32 adv, f32 xoff (-2 or -1), f32 top.
JSON written (per glyph key = decimal codepoint): `{x,y,w,h,adv,xoff,top}`, plus `lineHeight, atlasW, atlasH, pixelSize`.
Render: for each char, blit rect (x,y,w,h) at `(penX + xoff, baselineY - top)`, then `penX += adv`. Atlas PNG is white with alpha=ink: tint any colour
(the game tints per text style; shadow/outline are shader effects font_SHADOW/STROKE, not in the atlas). Line pitch = lineHeight. Verified: baseline-top
placement gives correct descenders and baseline for "Hello Mina! ..." (see `_ui\font_sample.png`). Guessed: exact meaning of header u32 pair (13,12 / 287,288) and lineHeight use.
Gothic7px-Bold is an all-caps style font (lowercase maps to capitals; its a-ring appears as a-uml). The game localisation has no Swedish, but the font covers a-ring, a-uml, o-uml.
Other bitmap-number fonts are sprite based: `sprites\UI_text_6pxFont` (letters as sprites named 'A','B',...), `pickupFont*`, `tinyNumbers`, `comboNumbers`.

## ANB (sprite bundle) format (generalised from map-assets.md; `tools\anbfile.py`)
root+0x08 -> palette name; root+0x28/0x30 sprites (56 B: hash, name, frames ptr (16 B each: u32 cel, f32 dur), locators),
root+0x38/0x40 cels (56 B: f32 pivot x,y @+8 [=cel centre in sprite space], f32 canvas w,h @+0x10, slice count, slice ptr),
root+0x48/0x50 images (32 B: u32 w,h, csize, ptr -> WFLZ 8-bit paletted). Slice (24 B): u32 w,h, f32 dstX,dstY (top-left relative to sprite origin; y down), srcX,srcY in image `celIndex`.
Bug fixed vs older tools: slices are NOT offset by canvas/2 for UI cels; render the bbox of the slices (index `anchor` = position of sprite origin in the PNG).
Palettes: `palettes/<name>.pal.yc` (255 RGBA, index 0 transparent) named by the anb (global.pal, textBox.pal, HUD.pal, ...). Only needleThreadIcon (player palette) skipped.

## 9-slice frames (index `<tag>/_nineslice`)
Anbs with sprites topL/top/topR/left/center/right/botL/bottom/botR (each an 8x8 cel; margins 8/8/8/8): `UI_locationPlate`, `UI_popupPlate`, `UI_zoneNamePlate`,
`UI_text_textBox` (+ pieces). The game builds a plate of arbitrary size by tiling edge pieces (8 px) and filling the centre (black, 0,0,0,255 in plate palettes).
`extract_ui.py` builds samples (see `contact_locationPlates.png`, verified: ornate corners with light-blue gems, 2-tone dotted border). Location plate colour theme per palette:
AstralOrrery cyan, BayouA green, BayouB purple, BoneBeachA purple/pink, BoneBeachB orange, CryptA teal (border 0xA?F? light-green/teal #3d34a5 inner), CryptB orange, FrozenTrainyard ice blue,
IntroBeach yellow, MansionA/B tan, OWEast green, OWSouth pink-red, OWWest yellow, Ossex grey, SeptemburgA green, SeptemburgB orange (read exact colours from `sprites` or palettes).
Note: the 17 location names and which palette they map to are by name (locationPlate<Loc>.pal.yc, Loc = same as the list in map-assets.md).

## Map screen sprites (UI/worldMap.anb.yc, tag `UI_worldMap`) - coordinates relative to screen centre of the 256x144 view, y down
- `map` 256x144 background (already baked by bake_map.py); `letterBox`: black bars, top 9 px (y -72..-63) and bottom 12 px (y 60..72), full width.
- `frameWide` 104x128 at x 24..128, y -61..67 (right-hand side panel frame: cream double border + vertical divider, black fill; ornate corners), `frameThin` 51x128 at x 77..128, y -61..67
  (collapsed variant), `frameWideNoBar` = frameWide without the divider/bar. `frameTotals` 52x23 (small box, placed dynamically). `starMarker` uses the same cel as frameWide (probably a dummy/animated placeholder).
- `minaMarker` 9x7 (hair-ribbon face, anchor (4,7) = bottom centre, 12 fps blink = 0.0833 s/frame); `completeMarker` 11x11 yellow star (anchor 5,6; 0.08 s);
  `<loc>Outline` (17) region outlines, `<loc>Lit` (7) lit overlays.
- Collectible icons (12x12-ish): iconMaxHealth (red rose), iconTrinket, iconKey, iconWeapon, iconMagic, iconArmor, iconVial, iconTrinketSlot, iconSparkSlot, iconUnderlab, iconBonestone, iconFishing, iconOther, iconLock, iconDance.
- Other UI: `UI_feats/check` (green tick), `UI_feats/box`, `iconFrame`, trophies; `UI_menusGeneral/listBar` (selection bar), `scrollBar_*`, `icon_New`; `UI_HUD_pauseMenu` (lvlTop/Mid/Bot, namePlate, tabPlate_*, filigree*); `UI_HUD_levelUpMenu`, `UI_soundTest/selectionBar`,
  `UI_profileSelect/selectionHighlight*`, `UI_text_textBox`, `UI_arrowCursor`, `UI_HUD_cursor`, `objects_checkpointMap/idle` (checkpoint map icon). Browse `contact_UI_*.png`.
- Colour palette of frames: cream #FFE9C5-ish borders on black (from palette global).

## MapScreen layout (disassembly: Setup 0x1003e4fc8, SetPosFrame 0x1003e6ca8, UpdateTextLocation 0x1003e8ef0, ChangeLocation 0x1003e8418, UpdateLocationVisual 0x1003e8b14; dumps `_setup.txt`, `_setposframe.txt`, `_updtext.txt`, `_changeloc.txt`)
Verified from code:
- Setup order: fullscreenBlack (anim "Default") backdrop, then worldMap.anb widgets: `letterBox`, 17 location widgets (+ Outline/Lit layers), `frameTotals`, `map_controls` text widget, `map_allTotal` text widget. Mina marker widget at this+216; frames chosen in ChangeLocation: `frameWide` or `frameThin` (`frameWideNoBar` when checklist off).
- Widgets sit on a 256x144 virtual screen scaled (nearest) to the display; bake coordinates above are baked in the cels (no hard-coded floats in code except a few: 2.5, -19.0, -0.8, 4.0, 1.0 used in SetPosFrame for frame/tab slide offsets).
- Text keys (localised, English/FR/IT/DE/ES/JA/RU/PT/KO/ZH in `menus/menus.stb.yc`): `map_name` ("Isle Map"/"Enhanced Map"), `map_location` format `"%s: %s"`, `map_location_<loc>` names, `map_found` = "Found: %i/%i", `map_total` = "%i/%i",
  `map_allTotal` = "Total\n%i%%", `map_controls` = "[subweapon]: Hide [cancel]: Done", `map_controls_hidden` = "[subweapon]: Show [cancel]: Done", `map_controls_checklist` = "Hide / Checklist / Done",
  `map_unlock` = "Checklist unlocks when towers are repaired in:", `map_unlock_all`. English location names (map_location_*): astralOrrery "Astral Orrery", bayouA "Backwaters", bayouB "Nox's Bayou", boneBeachA "Sandfalls", boneBeachB "Bone Beach",
  cryptA "Mourner's Mile", cryptB "Queensbury Crypt", easternFields "Eastern Heath", frozenTrainyard "Coltrane Peak", introBeach "Loner's Landing", mansionA "Radiant Manor Foyer", mansionB "Radiant Manor", ossex "Ossex",
  septemburgA "Kindlewood", septemburgB "Septemburg", southernFields "Southern Outskirts", westernFields "Western Wilds".
- Text colour of the found-count (UpdateTextLocation): RGBA (255,233,197) cream while found < total, (255,231,55) yellow when complete (tables at 0x100d4e6a0/710/780 offset 0 / 36).
  The "revealed" flag SaveSlot+3422+loc toggles a widget flag (byte +2151) = grey-out of undiscovered areas.
- Mina marker: at the location's `MinaPos` locator (centre-relative, y up), minus camera; completed locations show `completeMarker` at `CompletePos`.
Guessed/unverified: exact pixel positions of the title text ("map_location") and totals text (computed at runtime from text width + SetPosFrame, not extracted); font used for map text is probably Gothic8px-Regular (most menu text), not confirmed by tracing the text style id.

## Suggested Android panel recipe
Use `Gothic8px-Regular` atlas+json for text, location plate 9-slice from the correct per-location palette (size 8px-multiple, e.g. 96x24) at the top, world map PNG scaled by integer nearest, minaMarker at MinaPos (blink 2 frames/0.083s per image), frameWide/frameThin on the right if a checklist is wanted,
letterBox bars top 9px / bottom 12px, text colours cream (255,233,197) / yellow (255,231,55), black (#000) fill behind everything.
