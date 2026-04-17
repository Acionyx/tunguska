param(
    [string]$ChromeApkPath = "C:\src\tunguska\tools\browser\chrome.apk"
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\UiAutomatorTools.ps1"

function Test-ChromeInstalled {
    $packages = Invoke-Adb -Arguments @("shell", "pm", "list", "packages", "com.android.chrome")
    return ($packages -join "`n") -match "package:com\.android\.chrome"
}

if (Test-ChromeInstalled) {
    Write-Host "Chrome is already installed."
    exit 0
}

Write-Host "Chrome is not installed. Trying install-existing."
Invoke-Adb -Arguments @("shell", "cmd", "package", "install-existing", "com.android.chrome") | Out-Null
Start-Sleep -Seconds 2
if (Test-ChromeInstalled) {
    Write-Host "Chrome was enabled from the system image."
    exit 0
}

if (Test-Path $ChromeApkPath) {
    Write-Host "Installing Chrome from $ChromeApkPath"
    Invoke-Adb -Arguments @("install", "-r", $ChromeApkPath) | Out-Null
    Start-Sleep -Seconds 2
    if (Test-ChromeInstalled) {
        Write-Host "Chrome installed successfully."
        exit 0
    }
}

throw "Chrome is required for the IP proof test. Add a Chrome APK at $ChromeApkPath or use an AVD image that includes com.android.chrome."
