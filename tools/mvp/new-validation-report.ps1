param(
    [string]$DeviceLabel = "unassigned-device",
    [string]$RuntimeStrategy = "xray+tun2socks",
    [string]$OutputDir = (Join-Path (Join-Path $PSScriptRoot "..\..") "build\mvp-validation")
)

$ErrorActionPreference = "Stop"

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$fileName = "validation-$timestamp-$DeviceLabel.md"
$path = Join-Path $OutputDir $fileName

$content = @"
# Tunguska MVP Validation Report

- Date: $(Get-Date -Format "yyyy-MM-dd HH:mm:ss zzz")
- Device: $DeviceLabel
- Runtime strategy: $RuntimeStrategy

## Inputs

- Profile import by paste: pending
- Profile import by QR camera: pending
- Profile import by QR image: pending
- VPN permission granted: pending

## Runtime checks

- Connect: pending
- TCP traffic passes: pending
- Disconnect: pending
- Reconnect: pending
- Split allowlist behavior: pending
- Split denylist behavior: pending
- Fail-closed on stop/revoke/error: pending

## Detector checks

- RKNHardering bypass verdict: pending
- VPN-Detector generic visibility: pending
- per-app-split-bypass-poc result: pending
- Termux excluded-app curl result: pending

## Blocking findings

- Open unauthenticated localhost proxy: pending
- Reachable management or debug API: pending
- Confirmed split-tunnel bypass: pending
- Underlying-network leak: pending
- Confirmed excluded-app tunnel egress via VPN network or tun0: pending

## Notes

- Attach the redacted diagnostic bundle path here.
- Record exact detector screenshots or logs here.
- Record whether the active xray+tun2socks lane matches the expected routing and detector behavior.
"@

Set-Content -Path $path -Value $content -Encoding UTF8
Write-Host "Validation report template created at $path"
