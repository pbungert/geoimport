<#
.SYNOPSIS
  Clears the app's imported output folders so the next import re-runs from a
  clean slate. The SD-card source files are left untouched (import copies, not
  moves), so no re-seeding is needed to retest.

.DESCRIPTION
  Deletes /sdcard/Pictures/Import** on the running emulator. With no start
  filename/timestamp set, PhotoImporter resumes after the last file in the
  highest "Import NN" folder; removing those folders makes it import everything
  on the card again.
#>
$ErrorActionPreference = "Stop"

$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
    foreach ($c in @("$env:USERPROFILE\AppData\Local\Android\Sdk\platform-tools\adb.exe",
                     "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe")) {
        if (Test-Path $c) { $adb = $c; break }
    }
}
if (-not $adb) { throw "adb not found. Add platform-tools to PATH or edit this script." }

& $adb shell "rm -rf /sdcard/Pictures/Import*"
$left = & $adb shell "ls -d /sdcard/Pictures/Import* 2>/dev/null || echo none"
Write-Host "Cleared imported folders. Remaining: $left" -ForegroundColor Green
