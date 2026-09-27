<#
.SYNOPSIS
  Seeds a running Android emulator with a fake Fuji-card DCIM tree + a matching
  GPX track so the Geoimport app can be exercised end to end without real data.

.DESCRIPTION
  - Detects the emulator's removable SD-card volume and writes DCIM/NNN_FUJI
    folders with dummy .RAF/.MOV files onto it.
  - Sets each file's modification time to a fake "capture" time. The app reads
    capture time from EXIF first and falls back to the file time; the dummy
    files carry no EXIF, so the file time is what geotagging matches against.
  - Writes a GPX whose track points bracket those capture times (zoneless local
    times, so they line up with the file times regardless of device timezone).
  - Drops the GPX in Documents/Geoimport so the in-app picker opens on it.
  - Grants "All files access" so the Import button doesn't bounce to Settings.

  Dummy .RAF files are not real Fuji raws, so the RAF-EXIF GPS writer rejects
  them and geotagging falls back to XMP sidecars. That still exercises the whole
  import + geotag + summary flow; only the real EXIF-write path needs a genuine
  card dump.

.PARAMETER BaseDate
  Capture date for the fake photos, yyyy-MM-dd. Must be <= the device's date.

.PARAMETER FileSizeKB
  Size of each dummy media file. Bigger = the copy progress bar lingers longer.

.PARAMETER Reset
  Wipe previously seeded card folders and the app's Pictures/Import** folders
  first, so the import starts from a clean slate. On by default.
#>
param(
    [string]$BaseDate = "2026-07-21",
    [int]$FileSizeKB = 512,
    [bool]$Reset = $true
)

$ErrorActionPreference = "Stop"

# --- locate adb -------------------------------------------------------------
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
    foreach ($c in @("$env:USERPROFILE\AppData\Local\Android\Sdk\platform-tools\adb.exe",
                     "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe")) {
        if (Test-Path $c) { $adb = $c; break }
    }
}
if (-not $adb) { throw "adb not found. Add platform-tools to PATH or edit this script." }

function Adb { & $adb @args }

# --- find the removable SD-card volume --------------------------------------
# `sm list-volumes` prints e.g. "public:253,80 mounted 0000-0000"; the last
# column is the fsUuid, and the volume is mounted at /storage/<fsUuid>.
$volLine = (Adb shell sm list-volumes) -split "`n" | Where-Object { $_ -match '^public:.*\bmounted\b' } | Select-Object -First 1
if (-not $volLine) {
    throw "No mounted public SD-card volume found. Give the AVD an SD card (hw.sdCard=yes) and reboot it."
}
$uuid = ($volLine.Trim() -split '\s+')[-1]
$card = "/storage/$uuid"
$dcim = "$card/DCIM"
Write-Host "SD card volume: $card" -ForegroundColor Cyan

# --- dataset ----------------------------------------------------------------
# folder, filename, capture time (local wall-clock on $BaseDate)
$items = @(
    @{ Dir = "101_FUJI"; Name = "DSCF0001.RAF"; Time = "10:02:00" },  # interpolated -> tagged
    @{ Dir = "101_FUJI"; Name = "DSCF0002.RAF"; Time = "10:12:00" },  # interpolated -> tagged
    @{ Dir = "101_FUJI"; Name = "DSCF0003.RAF"; Time = "10:25:00" },  # interpolated -> tagged
    @{ Dir = "101_FUJI"; Name = "DSCF0004.MOV"; Time = "10:28:00" },  # video -> sidecar
    @{ Dir = "102_FUJI"; Name = "DSCF0005.RAF"; Time = "10:33:00" },  # near last point -> tagged
    @{ Dir = "102_FUJI"; Name = "DSCF0006.RAF"; Time = "13:00:00" }   # far from track -> skipped
)

# GPX track points: zoneless local times so they match the file times in the
# device's own timezone. lat/lon/ele around Zurich.
$track = @(
    @{ Time = "10:00:00"; Lat = 47.3769; Lon = 8.5417; Ele = 400 },
    @{ Time = "10:10:00"; Lat = 47.3780; Lon = 8.5430; Ele = 410 },
    @{ Time = "10:20:00"; Lat = 47.3800; Lon = 8.5460; Ele = 405 },
    @{ Time = "10:30:00"; Lat = 47.3820; Lon = 8.5490; Ele = 420 }
)

# --- optional reset ---------------------------------------------------------
if ($Reset) {
    Write-Host "Resetting previous test data..." -ForegroundColor DarkGray
    Adb shell "rm -rf $dcim/*_FUJI" 2>$null | Out-Null
    Adb shell "rm -rf /sdcard/Pictures/Import*" 2>$null | Out-Null
}

# --- create + push dummy media files ----------------------------------------
$staging = Join-Path $env:TEMP "geoimport-seed"
New-Item -ItemType Directory -Force -Path $staging | Out-Null
$blank = Join-Path $staging "blank.bin"
[IO.File]::WriteAllBytes($blank, (New-Object byte[] ($FileSizeKB * 1024)))

foreach ($it in $items) {
    $destDir = "$dcim/$($it.Dir)"
    Adb shell "mkdir -p '$destDir'" | Out-Null
    $dest = "$destDir/$($it.Name)"
    Adb push $blank $dest | Out-Null
    Adb shell "touch -d '$BaseDate $($it.Time)' '$dest'" | Out-Null
    Write-Host "  $dest  @ $BaseDate $($it.Time)"
}

# --- build + push GPX -------------------------------------------------------
$sb = [System.Text.StringBuilder]::new()
[void]$sb.AppendLine('<?xml version="1.0" encoding="UTF-8"?>')
[void]$sb.AppendLine('<gpx version="1.1" creator="seed-emulator.ps1" xmlns="http://www.topografix.com/GPX/1/1">')
[void]$sb.AppendLine('  <trk><name>Geoimport test track</name><trkseg>')
foreach ($p in $track) {
    [void]$sb.AppendLine("    <trkpt lat=`"$($p.Lat)`" lon=`"$($p.Lon)`"><ele>$($p.Ele)</ele><time>${BaseDate}T$($p.Time)</time></trkpt>")
}
[void]$sb.AppendLine('  </trkseg></trk>')
[void]$sb.AppendLine('</gpx>')

$gpxLocal = Join-Path $staging "geoimport-test.gpx"
[IO.File]::WriteAllText($gpxLocal, $sb.ToString())
Adb shell "mkdir -p /sdcard/Documents/Geoimport" | Out-Null
Adb push $gpxLocal /sdcard/Documents/Geoimport/geoimport-test.gpx | Out-Null
Write-Host "GPX -> /sdcard/Documents/Geoimport/geoimport-test.gpx"

# --- grant All-files access so Import doesn't bounce to Settings ------------
Adb shell appops set com.pbungert.geoimport MANAGE_EXTERNAL_STORAGE allow 2>$null | Out-Null

Write-Host ""
Write-Host "Done. In the app: tap 'Select GPS track...' (opens on geoimport-test.gpx)," -ForegroundColor Green
Write-Host "then 'Import from SD card'. Expected: 6 files -> Import 01, 5 RAF / 1 MOV," -ForegroundColor Green
Write-Host "geotagged 5 of 6 (0 EXIF / 5 sidecar), 1 skipped." -ForegroundColor Green
