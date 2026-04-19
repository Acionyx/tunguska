param(
    [string]$ShareLink = "",
    [string]$JavaHome = "C:\Program Files\Java\jdk-24",
    [string]$AvdName = "tunguska-api34",
    [string]$AnubisRepo = "C:\temp\anubis-plan",
    [switch]$Headless,
    [switch]$NoHardReset,
    [switch]$SkipInstall,
    [int]$MemoryMb = 16384,
    [int]$CpuCores = 12
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$androidHome = "C:\Users\vladi\AppData\Local\Android\Sdk"
$adb = "$androidHome\platform-tools\adb.exe"

function Assert-EmulatorOnline {
    $deviceList = (& $adb devices) -join "`n"
    if ($deviceList -notmatch "emulator-\d+\s+device") {
        throw "No headed emulator device is attached to adb."
    }
    $emulatorProcess = Get-Process |
        Where-Object { $_.ProcessName -like "emulator*" -or $_.ProcessName -like "qemu-system*" } |
        Select-Object -First 1
    if (-not $emulatorProcess) {
        throw "adb reports an emulator device, but no emulator process is running locally."
    }
}

function Assert-LastExitCode {
    param([string]$Step)

    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code $LASTEXITCODE."
    }
}

Push-Location $root
try {
    Write-Host "Phase: start emulator"
    & "tools\emulator\start-emulator.ps1" `
        -AvdName $AvdName `
        -Headless:$Headless `
        -HardReset:$(!$NoHardReset) `
        -MemoryMb $MemoryMb `
        -CpuCores $CpuCores
    Assert-EmulatorOnline
    Write-Host "Phase: ensure Chrome"
    & "tools\emulator\ensure-chrome.ps1"
    Assert-LastExitCode "Ensure Chrome"
    Assert-EmulatorOnline
    Write-Host "Phase: ensure Shizuku"
    & "tools\integration\ensure-shizuku.ps1"
    Assert-LastExitCode "Ensure Shizuku"
    Assert-EmulatorOnline

    if (-not $ShareLink -and $env:TUNGUSKA_REAL_SHARE_LINK) {
        $ShareLink = $env:TUNGUSKA_REAL_SHARE_LINK
    }
    if (-not $ShareLink) {
        throw "A real VLESS share link is required. Pass -ShareLink or set TUNGUSKA_REAL_SHARE_LINK."
    }

    $shareLinkHex = ([System.Text.Encoding]::UTF8.GetBytes($ShareLink) | ForEach-Object {
        $_.ToString("x2")
    }) -join ""

    if (-not $SkipInstall) {
        Assert-EmulatorOnline
        Write-Host "Phase: install Tunguska + trafficprobe"
        $env:JAVA_HOME = $JavaHome
        & .\gradlew.bat `
            :app:installDebug `
            :app:installDebugAndroidTest `
            :trafficprobe:installDebug `
            --no-daemon `
            --no-build-cache `
            --no-configuration-cache `
            --no-parallel `
            "-Dkotlin.incremental=false"
        Assert-LastExitCode "Tunguska install"
    }

    Assert-EmulatorOnline
    Write-Host "Phase: prepare Tunguska automation fixture"
    & $adb shell am force-stop sgnv.anubis.app
    & $adb shell cmd package enable io.acionyx.tunguska | Out-Null
    & $adb shell am instrument -w -r `
        -e class io.acionyx.tunguska.app.PrepareAutomationFixtureTest `
        -e profile_share_link_hex $shareLinkHex `
        io.acionyx.tunguska.test/androidx.test.runner.AndroidJUnitRunner
    Assert-LastExitCode "Tunguska fixture preparation"
    Assert-EmulatorOnline

    & "tools\emulator\pull-diagnostics.ps1"
    Assert-EmulatorOnline

    Push-Location $AnubisRepo
    try {
        $env:JAVA_HOME = $JavaHome
        $env:ANDROID_HOME = $androidHome
        $env:ANDROID_SDK_ROOT = $androidHome

        if (-not $SkipInstall) {
            Assert-EmulatorOnline
            Write-Host "Phase: install Anubis"
            & .\gradlew.bat `
                :app:installDebug `
                :app:installDebugAndroidTest `
                --no-daemon `
                --no-build-cache `
                --no-configuration-cache `
                --no-parallel `
                "-Dkotlin.incremental=false"
            Assert-LastExitCode "Anubis install"
        }

        Assert-EmulatorOnline
        Write-Host "Phase: run Anubis Tunguska integration test"
        & $adb shell am instrument -w -r `
            -e class sgnv.anubis.app.TunguskaIntegrationTest `
            sgnv.anubis.app.test/androidx.test.runner.AndroidJUnitRunner
        Assert-LastExitCode "Anubis integration instrumentation"
        Assert-EmulatorOnline
    }
    finally {
        Pop-Location
    }

    & "tools\integration\pull-anubis-diagnostics.ps1"
}
finally {
    Pop-Location
}
