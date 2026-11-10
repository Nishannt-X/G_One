# Dev only: resets farmer memory so the next demo take starts from the seed.
# Deletes exactly backend/data/g-one.db, g-one.db-wal and g-one.db-shm, nothing else.
# Usage (repo root, server stopped):  pwsh scripts/reset-memory.ps1 [-DataDir backend/data] [-Port 8080]
param(
    [string]$DataDir = (Join-Path $PSScriptRoot '..\backend\data'),
    [int]$Port = $(if ($env:PORT) { [int]$env:PORT } else { 8080 })
)
$ErrorActionPreference = 'Stop'

$listening = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
if ($listening) {
    Write-Error "Something is listening on port $Port. Stop the backend first (Ctrl+C in its terminal), then run this again."
    exit 1
}

$dir = (Resolve-Path -LiteralPath $DataDir -ErrorAction SilentlyContinue)
if (-not $dir) {
    Write-Host "No data directory at $DataDir; nothing to reset."
    exit 0
}

$removed = 0
foreach ($name in @('g-one.db', 'g-one.db-wal', 'g-one.db-shm')) {
    $file = Join-Path $dir $name
    if (Test-Path -LiteralPath $file -PathType Leaf) {
        Remove-Item -LiteralPath $file -Confirm:$false
        Write-Host "Deleted $file"
        $removed++
    }
}
if ($removed -eq 0) { Write-Host "No g-one.db files in $dir; nothing to reset." }
Write-Host "Start the backend again; check the log for 'Farmer memory seeded from ...'."
