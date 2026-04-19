param(
    [string]$RemotePath = "files/tunguska-smoke",
    [string]$OutputRoot = "C:\src\tunguska\logs"
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\UiAutomatorTools.ps1"
$adb = Get-AdbPath

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$destination = Join-Path $OutputRoot "tunguska-smoke-$timestamp"
New-Item -ItemType Directory -Force -Path $destination | Out-Null

$files = Invoke-Adb -Arguments @("shell", "run-as", "io.acionyx.tunguska", "find", $RemotePath, "-type", "f")
if ($LASTEXITCODE -ne 0) {
    throw "Failed to enumerate Tunguska diagnostics from $RemotePath."
}
$relativeFiles = $files |
    Where-Object { $_ -and $_.Trim() } |
    ForEach-Object { $_.Trim() }

foreach ($remoteFile in $relativeFiles) {
    $relativePath = $remoteFile.Substring($RemotePath.Length).TrimStart('/', '\')
    if (-not $relativePath) {
        continue
    }
    $localPath = Join-Path $destination $relativePath
    $localDirectory = Split-Path -Parent $localPath
    if ($localDirectory) {
        New-Item -ItemType Directory -Force -Path $localDirectory | Out-Null
    }
    $process = Start-Process `
        -FilePath $adb `
        -ArgumentList @("exec-out", "run-as", "io.acionyx.tunguska", "cat", $remoteFile) `
        -NoNewWindow `
        -Wait `
        -PassThru `
        -RedirectStandardOutput $localPath
    if ($process.ExitCode -ne 0) {
        throw "Failed to pull Tunguska diagnostic file $remoteFile."
    }
}

Write-Host "Diagnostics pulled to $destination"
