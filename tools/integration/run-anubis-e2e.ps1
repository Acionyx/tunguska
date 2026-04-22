param(
    [string]$ShareLink = "",
    [string]$JavaHome = "",
    [string]$AvdName = "tunguska-api34",
    [string]$AnubisRepo = "",
    [string[]]$RuntimeStrategies = @("XRAY_TUN2SOCKS", "SINGBOX_EMBEDDED"),
    [switch]$Headless,
    [switch]$NoHardReset,
    [switch]$SkipInstall,
    [int]$MemoryMb = 16384,
    [int]$CpuCores = 12
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$profileShareLinkSetting = "tunguska_profile_share_link"
$profileShareLinkHexSetting = "tunguska_profile_share_link_hex"
. "$PSScriptRoot\..\common\PathTools.ps1"

$androidHome = Get-AndroidSdkRoot
$adb = Get-AdbPath

if (-not $JavaHome) {
    $JavaHome = Get-DefaultJavaHome
}

if (-not $AnubisRepo) {
    $siblingAnubisRepo = Join-Path (Split-Path $root -Parent) "anubis"
    if (Test-Path $siblingAnubisRepo) {
        $AnubisRepo = (Resolve-Path $siblingAnubisRepo).Path
    }
}

if (-not $AnubisRepo -or -not (Test-Path $AnubisRepo)) {
    $expectedSibling = Join-Path (Split-Path $root -Parent) "anubis"
    throw "Anubis repo not found. Pass -AnubisRepo or clone it as a sibling repo at $expectedSibling."
}

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

function Invoke-CheckedInstrumentation {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [Parameter(Mandatory = $true)]
        [string]$Step
    )

    $stdoutPath = Join-Path $env:TEMP ("tunguska-instrument-{0}.stdout.txt" -f ([guid]::NewGuid().ToString("N")))
    $stderrPath = Join-Path $env:TEMP ("tunguska-instrument-{0}.stderr.txt" -f ([guid]::NewGuid().ToString("N")))
    try {
        $process = Start-Process `
            -FilePath $adb `
            -ArgumentList $Arguments `
            -NoNewWindow `
            -Wait `
            -PassThru `
            -RedirectStandardOutput $stdoutPath `
            -RedirectStandardError $stderrPath
        $instrumentOutput = @()
        if (Test-Path $stdoutPath) {
            $instrumentOutput += Get-Content $stdoutPath
        }
        if (Test-Path $stderrPath) {
            $instrumentOutput += Get-Content $stderrPath
        }
        $instrumentOutput | ForEach-Object { Write-Host $_ }
        if ($process.ExitCode -ne 0) {
            throw "$Step failed with exit code $($process.ExitCode)."
        }
    } finally {
        Remove-Item $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
    }
    $instrumentText = ($instrumentOutput | Out-String)
    if (
        $instrumentText -match "Process crashed" -or
        $instrumentText -match "FAILURES!!!" -or
        $instrumentText -match "INSTRUMENTATION_FAILED" -or
        $instrumentText -match "shortMsg="
    ) {
        throw "$Step reported a runtime failure despite exit code 0."
    }
}

function Get-ProfileShareLinkFixture {
    $hexValue = (& $adb shell settings get global $profileShareLinkHexSetting).Trim()
    if ($hexValue -and $hexValue -ne "null") {
        return $hexValue
    }
    $plainValue = (& $adb shell settings get global $profileShareLinkSetting).Trim()
    if ($plainValue -and $plainValue -ne "null") {
        return $plainValue
    }
    return ""
}

function Clear-ProfileShareLinkFixture {
    & $adb shell settings delete global $profileShareLinkHexSetting | Out-Null
    & $adb shell settings delete global $profileShareLinkSetting | Out-Null
}

function Set-ProfileShareLinkFixture {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ProfileShareLink
    )

    $shareLinkHex = ([System.Text.Encoding]::UTF8.GetBytes($ProfileShareLink) | ForEach-Object {
        $_.ToString("x2")
    }) -join ""

    Clear-ProfileShareLinkFixture
    & $adb shell settings put global $profileShareLinkHexSetting $shareLinkHex | Out-Null
}

function Enable-Package {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PackageName
    )

    & $adb shell pm enable --user 0 $PackageName | Out-Null
    & $adb shell cmd package enable $PackageName | Out-Null
}

function Normalize-RuntimeStrategies {
    param(
        [string[]]$Values
    )

    $normalized = @(
        $Values |
            Where-Object { $_ -and $_.Trim() } |
            ForEach-Object { $_.Trim().ToUpperInvariant() }
    )
    if (-not $normalized) {
        throw "At least one runtime strategy must be provided."
    }
    $supported = @("XRAY_TUN2SOCKS", "SINGBOX_EMBEDDED")
    $unsupported = @($normalized | Where-Object { $_ -notin $supported } | Select-Object -Unique)
    if ($unsupported) {
        throw "Unsupported runtime strategies: $($unsupported -join ', ')."
    }
    return @($normalized | Select-Object -Unique)
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
    $managingProfileFixture = $false
    if ($ShareLink) {
        Set-ProfileShareLinkFixture -ProfileShareLink $ShareLink
        $managingProfileFixture = $true
    } elseif (-not (Get-ProfileShareLinkFixture)) {
        throw "A real VLESS share link is required. Pass -ShareLink, set TUNGUSKA_REAL_SHARE_LINK, or pre-stage the emulator setting fixture."
    }
    $runtimeStrategies = Normalize-RuntimeStrategies -Values $RuntimeStrategies

    if (-not $SkipInstall) {
        Assert-EmulatorOnline
        Write-Host "Phase: install Tunguska + trafficprobe + jointtesthost"
        if ($JavaHome) {
            $env:JAVA_HOME = $JavaHome
        }
        & .\gradlew.bat `
            :app:installDebug `
            :app:installDebugAndroidTest `
            :jointtesthost:installDebug `
            :jointtesthost:installDebugAndroidTest `
            :trafficprobe:installDebug `
            --no-daemon `
            --no-build-cache `
            --no-configuration-cache `
            --no-parallel `
            "-Dkotlin.incremental=false"
        Assert-LastExitCode "Tunguska install"
    }

    Push-Location $AnubisRepo
    try {
        if ($JavaHome) {
            $env:JAVA_HOME = $JavaHome
        }
        $env:ANDROID_HOME = $androidHome
        $env:ANDROID_SDK_ROOT = $androidHome

        if (-not $SkipInstall) {
            Assert-EmulatorOnline
            Write-Host "Phase: install Anubis"
            & .\gradlew.bat `
                :app:installDebug `
                --no-daemon `
                --no-build-cache `
                --no-configuration-cache `
                --no-parallel `
                "-Dkotlin.incremental=false"
            Assert-LastExitCode "Anubis install"
        }
    }
    finally {
        Pop-Location
    }

    foreach ($runtimeStrategy in $runtimeStrategies) {
        Assert-EmulatorOnline
        Write-Host "Phase: prepare Tunguska automation fixture ($runtimeStrategy)"
        & $adb shell am force-stop sgnv.anubis.app
        & $adb shell pm clear io.acionyx.tunguska | Out-Null
        & $adb shell pm clear io.acionyx.tunguska.trafficprobe | Out-Null
        Enable-Package -PackageName "io.acionyx.tunguska"
        & $adb shell run-as io.acionyx.tunguska rm -rf files/tunguska-smoke | Out-Null
        & $adb shell run-as io.acionyx.tunguska mkdir -p files/tunguska-smoke | Out-Null
        Invoke-CheckedInstrumentation -Step "Tunguska fixture preparation ($runtimeStrategy)" -Arguments @(
            "shell", "am", "instrument", "-w", "-r",
            "-e", "class", "io.acionyx.tunguska.app.PrepareAutomationFixtureTest",
            "-e", "runtime_strategy", $runtimeStrategy,
            "io.acionyx.tunguska.test/androidx.test.runner.AndroidJUnitRunner"
        )
        Assert-EmulatorOnline

        & "tools\emulator\pull-diagnostics.ps1"
        Assert-EmulatorOnline

        Write-Host "Phase: run Tunguska Anubis joint UI test ($runtimeStrategy)"
        & $adb shell am force-stop sgnv.anubis.app
        & $adb shell pm clear sgnv.anubis.app | Out-Null
        & $adb shell pm clear io.acionyx.tunguska.jointtesthost | Out-Null
        & $adb shell pm clear io.acionyx.tunguska.trafficprobe | Out-Null
        Enable-Package -PackageName "io.acionyx.tunguska"
        Enable-Package -PackageName "sgnv.anubis.app"
        Enable-Package -PackageName "io.acionyx.tunguska.jointtesthost"
        Enable-Package -PackageName "io.acionyx.tunguska.trafficprobe"
        & $adb shell run-as io.acionyx.tunguska.jointtesthost rm -rf files/tunguska-smoke | Out-Null
        & $adb shell run-as io.acionyx.tunguska.jointtesthost mkdir -p files/tunguska-smoke | Out-Null
        Invoke-CheckedInstrumentation -Step "Tunguska Anubis joint UI instrumentation ($runtimeStrategy)" -Arguments @(
            "shell", "am", "instrument", "-w", "-r",
            "-e", "class", "io.acionyx.tunguska.trafficprobe.AnubisJointUiProofTest",
            "-e", "runtime_strategy", $runtimeStrategy,
            "io.acionyx.tunguska.jointtesthost.test/androidx.test.runner.AndroidJUnitRunner"
        )

        & "tools\emulator\pull-diagnostics.ps1" -AppPackage "io.acionyx.tunguska.jointtesthost"
    }
}
finally {
    if ($managingProfileFixture) {
        Clear-ProfileShareLinkFixture
    }
    Pop-Location
}
