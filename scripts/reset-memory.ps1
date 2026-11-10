# Dev only: resets farmer memory so the next demo take starts from the seed.
# Deletes exactly backend/data/g-one.db, g-one.db-wal and g-one.db-shm, nothing else.
# Usage (repo root, server stopped):  pwsh scripts/reset-memory.ps1 [-DataDir backend/data] [-Port 8080]
param(
    [string]$DataDir = (Join-Path $PSScriptRoot '..\backend\data'),
    [int]$Port = $(if ($env:PORT) { [int]$env:PORT } else { 8080 })
)
$ErrorActionPreference = 'Stop'

# $true or $false for a listener on $Port, or $null when nothing could check. Any listener blocks the reset.
function Test-Listening([int]$Port) {
    if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
        try {
            # All listeners, then filter: asking for one port errors when there is none, which hides real failures.
            $all = @(Get-NetTCPConnection -State Listen -ErrorAction Stop)
            return [bool]($all | Where-Object { $_.LocalPort -eq $Port })
        } catch { }
    }
    if (Get-Command netstat -ErrorAction SilentlyContinue) {
        $out = netstat -an 2>$null
        if ($LASTEXITCODE -eq 0 -and $out) {
            return [bool]($out | Select-String -Pattern "[:.]$Port\s.*LISTEN" -Quiet)
        }
    }
    return $null
}

$listening = Test-Listening $Port
if ($null -eq $listening) {
    Write-Error "Could not check whether port $Port is in use (Get-NetTCPConnection and netstat both failed). Nothing was deleted. Stop the backend yourself and delete the g-one.db files by hand."
    exit 1
}
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
