# Deploying to the Retroid Pocket Duo

Prerequisites: adb on PATH (or in the SDK platform-tools), USB debugging or Wireless debugging enabled,
your own Steam copy of the game, Python 3 (for the map, level art and UI bakes; about 40 s), the debuggable APK at `apk\out\Mina-debug.apk`.

```powershell
.\deploy-to-device.ps1 -WhatIf                            # dry run, prints what would happen
.\deploy-to-device.ps1                                    # full deploy
.\deploy-to-device.ps1 -Connect <device-ip>:5555 -Save   # wifi + push Steam cloud save
.\deploy-to-device.ps1 -SkipData -SkipShaders             # quick APK-only redeploy
.\deploy-to-device.ps1 -SkipInstall -SkipData -SkipShaders -InventoryMap <inventory-map.json>   # panel art only
.\deploy-to-device.ps1 -SkipPanelArt                     # skip level art, UI font/plates and inventory icons
```

Parameters: `-Apk -DataPath -ShaderRoot -Python -MapOutDir -Serial -Connect -Save -SavePath -SkipInstall -SkipData -SkipShaders -SkipMap -NoLaunch -WhatIf`.

Device layout (app private dir): `files/gdroot/gamedata/Mina the Hollower.app`,
`files/gdroot/gamedata/shaders_gles|shaders_spv`, `files/mapassets/{worldmap_big.png,locators_big.json,area_table.csv}`,
`files/saveData.yc` (an existing one is copied to `saveData.yc.orig` first).

The script never deletes anything on the device. Staging copies remain in `/data/local/tmp/mina_stage_*`
(~800 MB); remove them yourself, e.g. `adb shell rm -r /data/local/tmp/mina_stage_gd`, if you need the space.
Baked map art stays local (`deploy\_out\map`); never publish it or the game data.
