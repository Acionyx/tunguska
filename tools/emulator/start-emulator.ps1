param(
    [string]$AvdName = "tunguska-api36",
    [switch]$Headless,
    [switch]$ColdBoot,
    [switch]$WipeData,
    [string]$CameraBack = "emulated"
)

$ErrorActionPreference = "Stop"
$sdkRoot = "C:\Users\vladi\AppData\Local\Android\Sdk"
$emulator = Join-Path $sdkRoot "emulator\emulator.exe"
$adb = Join-Path $sdkRoot "platform-tools\adb.exe"

if (-not (Test-Path $emulator)) {
    throw "Android Emulator is not installed at $emulator"
}

New-Item -ItemType Directory -Force -Path "C:\src\tunguska\logs" | Out-Null
$stdout = "C:\src\tunguska\logs\emulator-stdout.log"
$stderr = "C:\src\tunguska\logs\emulator-stderr.log"

if (-not (Get-Process | Where-Object { $_.ProcessName -like "emulator*" -or $_.ProcessName -like "qemu-system*" })) {
    $arguments = @(
        "-avd", $AvdName,
        "-no-boot-anim",
        "-no-audio",
        "-gpu", "swiftshader_indirect",
        "-camera-back", $CameraBack
    )
    if ($Headless) {
        $arguments += "-no-window"
    }
    if ($ColdBoot -or $WipeData) {
        $arguments += "-no-snapshot-load"
        $arguments += "-no-snapshot-save"
    }
    if ($WipeData) {
        $arguments += "-wipe-data"
    }
    Start-Process `
        -FilePath $emulator `
        -ArgumentList $arguments `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr | Out-Null
}

& $adb wait-for-device | Out-Null
$deadline = (Get-Date).AddMinutes(5)
do {
    Start-Sleep -Seconds 5
    $boot = (& $adb shell getprop sys.boot_completed 2>$null).Trim()
} while ((Get-Date) -lt $deadline -and $boot -ne "1")

if ($boot -ne "1") {
    throw "Emulator did not finish booting in time."
}

& $adb shell settings put global window_animation_scale 0 | Out-Null
& $adb shell settings put global transition_animation_scale 0 | Out-Null
& $adb shell settings put global animator_duration_scale 0 | Out-Null

Write-Host "Emulator ready (headed=$(!$Headless))."
