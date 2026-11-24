<#
.SYNOPSIS
  Deploys the Mina port (APK + your own game data + map art [+ save]) to an Android device over ADB.

.DESCRIPTION
  Steps: check adb/device -> install APK -> grant all-files access -> push game data and shaders
  (via /data/local/tmp, then `run-as` cp into the app's private files/gdroot/gamedata) -> bake and push
  map art -> optionally push a save (existing saveData.yc is first copied to saveData.yc.orig) -> launch.
  Nothing is ever deleted on the device. Staging copies stay in /data/local/tmp/mina_stage_*.
  The app must be a debuggable build (run-as). Game data is your own Steam copy; nothing is bundled.

.PARAMETER WhatIf
  Dry run: prints every adb/python command that would change something, runs none of them.
  (Read-only adb queries such as `adb devices` still run.)

.EXAMPLE
  .\deploy-to-device.ps1 -WhatIf
  .\deploy-to-device.ps1 -Connect <device-ip>:5555 -Save
  .\deploy-to-device.ps1 -Serial ABC123 -SavePath D:\backup\saveData.yc -SkipInstall
#>
[CmdletBinding()]
param(
  [string]$Apk        = (Join-Path $PSScriptRoot '..\apk\out\Mina-debug.apk'),
  [string]$DataPath   = 'C:\Program Files (x86)\Steam\steamapps\content\app_1875580\depot_1875584\Mina the Hollower.app',
  [string]$ShaderRoot = (Join-Path $PSScriptRoot '..\data'),
  [string]$Python,
  [string]$MapOutDir  = (Join-Path $PSScriptRoot '_out\map'),
  [string]$Serial,
  [string]$Connect,                 # e.g. <device-ip>:5555 -> runs `adb connect` first
  [switch]$Save,                    # push a save (auto-detect Steam cloud save unless -SavePath)
  [string]$SavePath,                # implies -Save
  [string]$Package    = 'com.mina.port',
  [string]$Activity   = 'com.mina.port/.MinaActivity',
  [switch]$SkipInstall,
  [switch]$SkipData,
  [switch]$SkipShaders,
  [switch]$SkipMap,
  [switch]$SkipPanelArt,            # skip the coloured level art, UI font/plates and inventory icons
  [string]$InventoryMap,           # inventory-map.json (offsets + names; not in the repo). Default: ..\analysis\inventory-map.json
  [switch]$NoLaunch,
  [switch]$WhatIf
)

$ErrorActionPreference = 'Stop'
$script:Adb = $null
$script:Dev = $null
$GdRoot  = 'files/gdroot/gamedata'      # relative to the app's private dir (run-as cwd)
$Stage   = '/data/local/tmp'

function Step($m) { Write-Host "`n== $m" -ForegroundColor Cyan }
function Info($m) { Write-Host "   $m" }
function Warn($m) { Write-Host "   WARNING: $m" -ForegroundColor Yellow }
function Fail($m) { Write-Host "`nERROR: $m" -ForegroundColor Red; exit 1 }
function Fwd([string]$p) { ([System.IO.Path]::GetFullPath($p)) -replace '\\','/' }   # C:/... for adb

# Runs adb; returns @{Out;Code}. Read-only queries pass -Query so they also run under -WhatIf.
function Invoke-Adb([string[]]$a, [switch]$Query, [switch]$Quiet) {
  $full = @(); if ($script:Dev) { $full += @('-s', $script:Dev) }; $full += $a
  if ($WhatIf -and -not $Query) { Info "[WhatIf] adb $($full -join ' ')"; return @{ Out = ''; Code = 0 } }
  $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  try {
    if ($Quiet) { $out = (& $script:Adb @full 2>&1 | Out-String) }
    else { $out = (& $script:Adb @full 2>&1 | ForEach-Object { Write-Host "   $_"; "$_" } | Out-String) }
    $code = $LASTEXITCODE
  } finally { $ErrorActionPreference = $old }
  return @{ Out = $out; Code = $code }
}
function Invoke-AdbOrFail([string[]]$a, [string]$what) {
  $r = Invoke-Adb $a
  if ($r.Code -ne 0) { Fail "$what failed (adb exit $($r.Code)). Output: $($r.Out.Trim())" }
  return $r
}
# Shell command run inside the app's sandbox. $cmd must contain no quotes or spaces in paths.
function RunAs([string]$cmd, [switch]$Query) {
  if ($Query -and $WhatIf -and -not $script:DeviceReady) { return @{ Out = 'No such file'; Code = 1 } }
  return Invoke-Adb @('shell', "run-as $Package $cmd") -Query:$Query -Quiet:$Query
}
function RunAsOrFail([string]$cmd, [string]$what) {
  $r = RunAs $cmd
  if ($r.Code -ne 0 -or $r.Out -match 'run-as:|Permission denied|No such file') { Fail "$what failed: $($r.Out.Trim())" }
}
# Pushes a local file/dir into a staging dir on the device, then copies it into the app dir.
function PushViaStage([string]$local, [string]$stageName, [string]$destRel, [string]$what) {
  $st = "$Stage/$stageName"
  Info "$what : $local"
  Invoke-AdbOrFail @('shell', "mkdir -p $st") "creating $st" | Out-Null
  Invoke-AdbOrFail @('push', (Fwd $local), "$st/") "pushing $what to $st" | Out-Null
  RunAsOrFail "mkdir -p $destRel" "creating $destRel"
  RunAsOrFail "cp -r $st/. $destRel/" "copying $what into the app"
}

if ($WhatIf) { Write-Host "*** DRY RUN (-WhatIf): nothing will be changed ***" -ForegroundColor Magenta }

$ToolsDir = @('..\analysis\tools', '..\tools') | ForEach-Object { Join-Path $PSScriptRoot $_ } | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1

# ---------------------------------------------------------------- 1. adb + device
Step '1/9 Checking adb and device'
$cmd = Get-Command adb -ErrorAction SilentlyContinue
if ($cmd) { $script:Adb = $cmd.Source }
else {
  foreach ($c in @("$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe", "$env:ANDROID_HOME\platform-tools\adb.exe",
                   "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe", 'C:\Program Files\platform-tools\adb.exe')) {
    if ($c -and (Test-Path $c)) { $script:Adb = $c; break }
  }
}
if (-not $script:Adb) { Fail 'adb not found. Install Android platform-tools and put adb.exe on PATH.' }
Info "adb: $script:Adb"
if ($Connect) { Info "adb connect $Connect"; $r = Invoke-Adb @('connect', $Connect) -Query; if ($r.Code -ne 0) { Warn 'adb connect reported an error' } }

$r = Invoke-Adb @('devices') -Query -Quiet
$devs = @($r.Out -split "`r?`n" | Where-Object { $_ -match '^\S+\s+(device|unauthorized|offline)\s*$' } |
          ForEach-Object { ($_.Trim() -split '\s+')[0..1] -join ' ' })
$ready = @($devs | Where-Object { $_ -match ' device$' } | ForEach-Object { ($_ -split ' ')[0] })
$script:DeviceReady = $true
if ($Serial) {
  if ($ready -notcontains $Serial) {
    if ($WhatIf) { Warn "Serial $Serial not ready (continuing, dry run)"; $script:DeviceReady = $false }
    else { Fail "Device '$Serial' is not connected/authorized. adb sees: $($devs -join '; ')" }
  }
  $script:Dev = $Serial
} elseif ($ready.Count -eq 1) { $script:Dev = $ready[0] }
elseif ($ready.Count -gt 1) { Fail "Several devices connected ($($ready -join ', ')). Pick one with -Serial." }
else {
  $unauth = $devs | Where-Object { $_ -match 'unauthorized' }
  if ($unauth) { Fail 'Device is unauthorized: accept the USB-debugging prompt on the device and rerun.' }
  if ($WhatIf) { Warn 'No device connected (continuing, dry run).'; $script:Dev = '<device>'; $script:DeviceReady = $false }
  else { Fail 'No device found. Connect via USB (enable USB debugging) or use -Connect <ip:5555> (Wireless debugging).' }
}
Info "device: $script:Dev"
if ($script:DeviceReady) {
  $r = Invoke-Adb @('shell', 'getprop ro.product.model; getprop ro.build.version.release; getprop ro.product.cpu.abi') -Query -Quiet
  Info ("model/android/abi: " + (($r.Out -split "`r?`n" | Where-Object { $_ }) -join ' / '))
  if ($r.Out -notmatch 'arm64') { Warn 'Device ABI is not arm64; the APK only contains arm64 libs (an x86_64 emulator works via ndk_translation).' }
}

# ---------------------------------------------------------------- 2. inputs on the PC
Step '2/9 Checking local inputs'
if (-not $SkipInstall) {
  if (-not (Test-Path -LiteralPath $Apk)) { Fail "APK not found: $Apk (build it first, see apk\build_all.sh)" }
  $Apk = (Resolve-Path -LiteralPath $Apk).Path; Info "APK: $Apk"
}
$ResData = Join-Path $DataPath 'Contents\Resources\data'
if (-not $SkipData -or -not $SkipMap) {
  if (-not (Test-Path -LiteralPath $ResData)) { Fail "Game data not found: expected '$ResData'. Pass -DataPath '<...>\Mina the Hollower.app' (your own Steam copy)." }
  Info "Game data: $DataPath"
}
foreach ($d in 'shaders_gles','shaders_spv') {
  if (-not $SkipShaders -and -not (Test-Path -LiteralPath (Join-Path $ShaderRoot $d))) { Fail "Shader dir missing: $(Join-Path $ShaderRoot $d). Use -ShaderRoot or -SkipShaders." }
}
$SaveFile = $null
if ($Save -or $SavePath) {
  if ($SavePath) {
    if (-not (Test-Path -LiteralPath $SavePath)) { Fail "Save file not found: $SavePath" }
    $SaveFile = (Resolve-Path -LiteralPath $SavePath).Path
  } else {
    $cands = @(Get-ChildItem -Path 'C:\Program Files (x86)\Steam\userdata\*\1875580\remote\saveData.yc' -ErrorAction SilentlyContinue)
    if ($cands.Count -eq 0) { Fail 'No Steam cloud save found (userdata\*\1875580\remote\saveData.yc). Use -SavePath.' }
    if ($cands.Count -eq 1) { $SaveFile = $cands[0].FullName }
    else {
      Write-Host '   Several saves found:'
      for ($i = 0; $i -lt $cands.Count; $i++) { Write-Host ("   [{0}] {1}  ({2})" -f $i, $cands[$i].FullName, $cands[$i].LastWriteTime) }
      if ($WhatIf) { $SaveFile = $cands[0].FullName; Info 'dry run: assuming [0]' }
      else {
        $n = Read-Host '   Choose number'
        if ($n -notmatch '^\d+$' -or [int]$n -ge $cands.Count) { Fail 'Invalid choice.' }
        $SaveFile = $cands[[int]$n].FullName
      }
    }
  }
  Info "Save: $SaveFile"
}

# ---------------------------------------------------------------- 3. install
Step '3/9 Installing APK'
if ($SkipInstall) { Info 'skipped' } else { Invoke-AdbOrFail @('install', '-r', (Fwd $Apk)) 'APK install' | Out-Null }

# ---------------------------------------------------------------- 4. permissions / run-as
Step '4/9 All-files access and run-as check'
$r = if ($script:DeviceReady) { Invoke-Adb @('shell', "appops get $Package MANAGE_EXTERNAL_STORAGE") -Query -Quiet } else { @{ Out = ''; Code = 1 } }
if ($r.Out -match 'allow') { Info 'MANAGE_EXTERNAL_STORAGE already allowed' }
else {
  $r = Invoke-Adb @('shell', "appops set $Package MANAGE_EXTERNAL_STORAGE allow")
  if ($r.Code -ne 0) { Warn 'Could not grant all-files access (non-fatal; app data lives in its private dir).' }
}
if (-not $WhatIf) {
  $r = RunAs 'ls files' -Query
  if ($r.Out -match 'not debuggable|unknown package|is not an application|package not found') { Fail "run-as failed: $($r.Out.Trim()). Install a debuggable build." }
  Info 'run-as OK'
}
Info 'stopping app before copying data (am force-stop; deletes nothing)'
Invoke-Adb @('shell', "am force-stop $Package") | Out-Null

# ---------------------------------------------------------------- 5. game data
Step '5/9 Game data -> files/gdroot/gamedata'
if ($SkipData) { Info 'skipped' }
else {
  Info 'About 800 MB; the push can take several minutes (staged in /data/local/tmp/mina_stage_gd, kept afterwards).'
  PushViaStage $DataPath 'mina_stage_gd' $GdRoot 'game data'
}

# ---------------------------------------------------------------- 6. shaders
Step '6/9 Shaders -> files/gdroot/gamedata/shaders_gles + shaders_spv'
if ($SkipShaders) { Info 'skipped' }
else {
  # adb push places the dir as <stage>/shaders_gles; cp -r <stage>/. gamedata/ keeps that name.
  PushViaStage (Join-Path $ShaderRoot 'shaders_gles') 'mina_stage_shg' $GdRoot 'shaders_gles'
  PushViaStage (Join-Path $ShaderRoot 'shaders_spv')  'mina_stage_shs' $GdRoot 'shaders_spv'
}

# ---------------------------------------------------------------- 7. map art
Step '7/9 Map art -> files/mapassets'
if ($SkipMap) { Info 'skipped' }
else {
  if (-not $Python) {
    foreach ($c in 'python','python3') { if (Get-Command $c -ErrorAction SilentlyContinue) { $Python = (Get-Command $c).Source; break } }
    if (-not $Python -and (Get-Command py -ErrorAction SilentlyContinue)) { $Python = 'py' }
  }
  if (-not $Python) { Fail 'Python not found (needed to bake the map). Use -Python <exe> or -SkipMap.' }
  $bake = Join-Path $ToolsDir 'bake_map.py'
  $csv  = Join-Path $PSScriptRoot '..\analysis\area_table.csv'
  if (-not (Test-Path -LiteralPath $bake)) { Fail "Missing file: $bake" }
  $bake = (Resolve-Path $bake).Path
  if (Test-Path -LiteralPath $csv) { $csv = (Resolve-Path $csv).Path } else { Warn 'area_table.csv not found; area names will be missing from the panel title'; $csv = $null }
  $MapOut = [System.IO.Path]::GetFullPath($MapOutDir)
  if ($WhatIf) { Info "[WhatIf] $Python `"$bake`" `"$ResData`" `"$MapOut`"" }
  else {
    New-Item -ItemType Directory -Force -Path $MapOut | Out-Null
    Info "baking: $bake"
    & $Python $bake $ResData $MapOut
    if ($LASTEXITCODE -ne 0) { Fail 'bake_map.py failed (see output above).' }
    foreach ($f in 'worldmap_big.png','locators_big.json') {
      if (-not (Test-Path (Join-Path $MapOut $f))) { Fail "bake_map.py did not produce $f" }
    }
  }
  $stm = "$Stage/mina_stage_map"
  Invoke-AdbOrFail @('shell', "mkdir -p $stm") 'creating map staging dir' | Out-Null
  foreach ($f in @((Join-Path $MapOut 'worldmap_big.png'), (Join-Path $MapOut 'locators_big.json'), $csv | Where-Object { $_ })) {
    Invoke-AdbOrFail @('push', (Fwd $f), "$stm/") "pushing $f" | Out-Null
  }
  RunAsOrFail 'mkdir -p files/mapassets' 'creating files/mapassets'
  RunAsOrFail "cp -r $stm/. files/mapassets/" 'copying map assets'
}

# ---------------------------------------------------------------- 7b. panel art
Step '7b/9 Panel art -> files/mapassets/levels and files/mapassets/ui'
if ($SkipPanelArt -or $SkipMap) { Info 'skipped' }
else {
  $OutRoot = Split-Path -Parent ([System.IO.Path]::GetFullPath($MapOutDir))
  $LvOut   = Join-Path $OutRoot 'levels_raw'
  $LvPush  = Join-Path $OutRoot 'panel\levels'   # pushed as a directory, so its name becomes files/mapassets/levels
  $UiRaw   = Join-Path $OutRoot 'ui_raw'
  $UiPush  = Join-Path $OutRoot 'panel\ui'
  $InvOut  = Join-Path $UiPush 'inv'
  if (-not $InventoryMap) { $InventoryMap = Join-Path $PSScriptRoot '..\analysis\inventory-map.json' }
  foreach ($s in 'bake_levels.py','bake_levels_color.py','extract_ui.py','stage_inv.py') {
    if (-not (Test-Path -LiteralPath (Join-Path $ToolsDir $s))) { Fail "Missing tool: $(Join-Path $ToolsDir $s)" }
  }
  function Run-Tool([string]$name, [string[]]$toolArgs) {
    $script = (Resolve-Path (Join-Path $ToolsDir $name)).Path
    if ($WhatIf) { Info "[WhatIf] $Python `"$script`" $($toolArgs -join ' ')"; return }
    Info "running: $name"
    & $Python $script @toolArgs | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail "$name failed (see output above)." }
  }
  Run-Tool 'bake_levels.py'       @($ResData, $LvOut)
  Run-Tool 'bake_levels_color.py' @($ResData, $LvOut)
  Run-Tool 'extract_ui.py'        @($ResData, $UiRaw)
  if (Test-Path -LiteralPath $InventoryMap) {
    Run-Tool 'stage_inv.py' @((Resolve-Path $InventoryMap).Path, $UiRaw, $InvOut)
  } else {
    Warn "inventory-map.json not found ($InventoryMap): the inventory view will say 'No inventory data'. Pass -InventoryMap <file>."
  }
  if (-not $WhatIf) {
    # keep only what the panel loads: level index + class and colour images, the two fonts, the plates and the marker
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $LvPush
    New-Item -ItemType Directory -Force -Path $LvPush | Out-Null
    Get-ChildItem -LiteralPath $LvOut -File | Where-Object { $_.Name -eq 'index.json' -or $_.Name -like '*_preview.png' -or $_.Name -like '*_color.png' } |
      ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $LvPush }
    New-Item -ItemType Directory -Force -Path (Join-Path $UiPush 'fonts'), (Join-Path $UiPush 'plates') | Out-Null
    foreach ($f in 'Gothic8px-Regular','Gothic8px-Bold') {
      foreach ($e in 'png','json') { Copy-Item -LiteralPath (Join-Path $UiRaw "fonts\$f.$e") -Destination (Join-Path $UiPush 'fonts') -Force }
    }
    Copy-Item -Path (Join-Path $UiRaw 'plates\*.png') -Destination (Join-Path $UiPush 'plates') -Force
    Copy-Item -LiteralPath (Join-Path $UiRaw 'sprites\UI_worldMap\minaMarker.png') -Destination (Join-Path $UiPush 'marker.png') -Force
  }
  if ($WhatIf) { Info "[WhatIf] push $LvPush -> files/mapassets/levels and $UiPush -> files/mapassets/ui" }
  else {
    PushViaStage $LvPush 'mina_stage_levels' 'files/mapassets' 'level art'
    PushViaStage $UiPush 'mina_stage_ui'     'files/mapassets' 'panel UI assets'
  }
}

# ---------------------------------------------------------------- 8. save
Step '8/9 Save file'
if (-not $SaveFile) { Info 'skipped (use -Save or -SavePath)' }
else {
  $has = RunAs 'ls files/saveData.yc' -Query
  if ($has.Out -match 'No such file') { Info 'no existing saveData.yc on device; no backup needed' }
  else {
    $orig = RunAs 'ls files/saveData.yc.orig' -Query
    $bk = 'files/saveData.yc.orig'
    if ($orig.Out -notmatch 'No such file') {
      $bk = 'files/saveData.yc.orig.' + (Get-Date -Format 'yyyyMMdd-HHmmss')
      Warn "saveData.yc.orig already exists (kept untouched); backing up to $bk instead"
    }
    RunAsOrFail "cp files/saveData.yc $bk" 'backing up existing save'
    Info "existing save backed up as $bk"
  }
  $sts = "$Stage/mina_stage_save"
  Invoke-AdbOrFail @('shell', "mkdir -p $sts") 'creating save staging dir' | Out-Null
  Invoke-AdbOrFail @('push', (Fwd $SaveFile), "$sts/saveData.yc") 'pushing save' | Out-Null
  RunAsOrFail "cp $sts/saveData.yc files/saveData.yc" 'installing save'
}

# ---------------------------------------------------------------- 9. launch
Step '9/9 Launch'
if ($NoLaunch) { Info 'skipped' }
else {
  $r = Invoke-Adb @('shell', "am start -n $Activity")
  if ($r.Code -ne 0 -or $r.Out -match 'Error') {
    Warn 'am start failed, trying monkey launcher'
    Invoke-Adb @('shell', "monkey -p $Package -c android.intent.category.LAUNCHER 1") | Out-Null
  }
}
Write-Host "`nDone." -ForegroundColor Green
if ($WhatIf) { Write-Host 'This was a dry run; nothing was changed.' -ForegroundColor Magenta }
