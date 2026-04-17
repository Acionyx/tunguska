param(
    [string]$RemotePath = "/sdcard/Download/tunguska-smoke",
    [string]$OutputRoot = "C:\src\tunguska\logs"
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\UiAutomatorTools.ps1"

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$destination = Join-Path $OutputRoot "tunguska-smoke-$timestamp"
New-Item -ItemType Directory -Force -Path $destination | Out-Null

Invoke-Adb -Arguments @("pull", $RemotePath, $destination) | Out-Null
Write-Host "Diagnostics pulled to $destination"
