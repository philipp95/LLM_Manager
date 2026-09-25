# Runs the Codex cross-review of the unreviewed skills.
# Codex hit its usage cap on 2026-09-24; quota resets 2026-09-30 11:05.
# Run from the repo root:  .\docs\run-codex-review.ps1

$ErrorActionPreference = "Stop"

$ext = Get-ChildItem "$env:USERPROFILE\.vscode\extensions" -Directory |
    Where-Object { $_.Name -like "openai.chatgpt-*-win32-x64" } |
    Sort-Object Name -Descending | Select-Object -First 1

if (-not $ext) { throw "ChatGPT/Codex VS Code extension not found." }

$codex = Join-Path $ext.FullName "bin\windows-x86_64\codex.exe"
if (-not (Test-Path $codex)) { throw "codex.exe not found at $codex" }

$repo   = Split-Path $PSScriptRoot -Parent
$brief  = Join-Path $PSScriptRoot "codex-review-brief.md"
$stamp  = Get-Date -Format "yyyyMMdd-HHmmss"
$outDir = Join-Path $PSScriptRoot "reviews"
New-Item -ItemType Directory -Force $outDir | Out-Null
$out    = Join-Path $outDir "codex-review-$stamp.md"

Write-Host "codex:  $codex"
Write-Host "brief:  $brief"
Write-Host "output: $out"
Write-Host ""

# Pipe via stdin — passing the prompt as an argument mangles it on PowerShell.
# Do NOT add `2>$null`: it makes the native call report exit 1 on PS 5.1.
Get-Content $brief -Raw | & $codex exec --sandbox read-only --cd $repo - |
    Tee-Object -FilePath $out

Write-Host ""
Write-Host "Saved to $out"
