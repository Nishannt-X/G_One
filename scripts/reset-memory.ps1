# Creates a fresh data directory for a new demo take. Existing memory is never deleted.
# Usage: $env:G_ONE_DATA_DIR = (& pwsh scripts/reset-memory.ps1)
param([string]$DataDir = (Join-Path $PSScriptRoot '..\backend\data'))
$ErrorActionPreference = 'Stop'

$parent = [System.IO.Path]::GetFullPath($DataDir)
[System.IO.Directory]::CreateDirectory($parent) | Out-Null
do {
    $take = Join-Path $parent ('take-' + [guid]::NewGuid().ToString('N'))
} while (Test-Path -LiteralPath $take)
[System.IO.Directory]::CreateDirectory($take) | Out-Null
Write-Output $take
