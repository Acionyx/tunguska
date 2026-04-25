param(
    [string]$ShareLink = "",
    [string]$ExpectedPhase = "RUNNING",
    [string]$JavaHome = "",
    [string]$AvdName = "tunguska-api34",
    [switch]$Headless,
    [switch]$NoHardReset,
    [switch]$SkipInstall,
    [int]$MemoryMb = 16384,
    [int]$CpuCores = 12,
    [string[]]$TestClasses = @(
        "io.acionyx.tunguska.app.VpnImportAndConnectTest",
        "io.acionyx.tunguska.app.ChromeIpProofTest",
        "io.acionyx.tunguska.app.RegionalBypassProofTest",
        "io.acionyx.tunguska.app.AutomationRelayProofTest",
        "io.acionyx.tunguska.app.FullTunnelProofTest",
        "io.acionyx.tunguska.app.FullTunnelLiteralIpProbeTest",
        "io.acionyx.tunguska.app.DenylistRoutingProofTest",
        "io.acionyx.tunguska.app.AllowlistRoutingProofTest",
        "io.acionyx.tunguska.app.SingboxEmbeddedProofTest",
        "io.acionyx.tunguska.app.SingboxChromeIpProofTest"
    )
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\\..")).Path
$diagnosticsRemotePath = "files/tunguska-smoke"
$profileShareLinkSetting = "tunguska_profile_share_link"
$profileShareLinkHexSetting = "tunguska_profile_share_link_hex"
. "$PSScriptRoot\UiAutomatorTools.ps1"
$adb = Get-AdbPath

if (-not $JavaHome) {
    $JavaHome = Get-DefaultJavaHome
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

function Wait-ForTunguskaReset {
    param([int]$TimeoutSeconds = 15)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $ipAddr = & $adb shell ip addr
        $processList = & $adb shell ps
        $vpnAddressPresent = ($ipAddr | Select-String -Pattern "172\.19\.0\.1|fdfe:dcba:9876::1" -Quiet)
        $tunguskaProcessesPresent = ($processList | Select-String -Pattern "io\.acionyx\.tunguska(?::vpn)?|io\.acionyx\.tunguska\.trafficprobe(?::probe)?" -Quiet)
        if (-not $vpnAddressPresent -and -not $tunguskaProcessesPresent) {
            return
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    throw "Tunguska processes or VPN addresses did not fully reset within ${TimeoutSeconds}s."
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
        $instrumentText = ($instrumentOutput | Out-String)
        if (
            $instrumentText -match "Process crashed" -or
            $instrumentText -match "FAILURES!!!" -or
            $instrumentText -match "INSTRUMENTATION_FAILED" -or
            $instrumentText -match "shortMsg="
        ) {
            throw "$Step reported a runtime failure despite exit code 0."
        }
    } finally {
        Remove-Item $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
    }
}

function Clear-ProfileShareLinkFixture {
    Invoke-Adb -Arguments @("shell", "settings", "delete", "global", $profileShareLinkHexSetting) | Out-Null
    Invoke-Adb -Arguments @("shell", "settings", "delete", "global", $profileShareLinkSetting) | Out-Null
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
    Invoke-Adb -Arguments @("shell", "settings", "put", "global", $profileShareLinkHexSetting, $shareLinkHex) | Out-Null
}

function Enable-Package {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PackageName
    )

    Invoke-Adb -Arguments @("shell", "pm", "enable", "--user", "0", $PackageName) | Out-Null
    Invoke-Adb -Arguments @("shell", "cmd", "package", "enable", $PackageName) | Out-Null
}

function Reset-InterferingPackages {
    $packages = @(
        "sgnv.anubis.app",
        "io.acionyx.tunguska.jointtesthost"
    )

    foreach ($packageName in $packages) {
        Invoke-Adb -Arguments @("shell", "am", "force-stop", $packageName) | Out-Null
    }
    Invoke-Adb -Arguments @("shell", "input", "keyevent", "KEYCODE_HOME") | Out-Null
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
    Reset-InterferingPackages

    if (-not $SkipInstall) {
        Assert-EmulatorOnline
        Write-Host "Phase: install Tunguska + tests + trafficprobe"
        if ($JavaHome) {
            $env:JAVA_HOME = $JavaHome
        }
        & .\gradlew.bat `
            :app:installDebug `
            :app:installDebugAndroidTest `
            :trafficprobe:installDebug `
            --no-daemon `
            --no-build-cache `
            --no-configuration-cache `
            --no-parallel `
            "-Dkotlin.incremental=false"
        Assert-LastExitCode "Tunguska smoke install"
    }

    Invoke-Adb -Arguments @("shell", "run-as", "io.acionyx.tunguska", "rm", "-rf", $diagnosticsRemotePath) | Out-Null
    Invoke-Adb -Arguments @("shell", "run-as", "io.acionyx.tunguska", "mkdir", "-p", $diagnosticsRemotePath) | Out-Null
    Enable-Package -PackageName "io.acionyx.tunguska"

    if (-not $ShareLink -and $env:TUNGUSKA_REAL_SHARE_LINK) {
        $ShareLink = $env:TUNGUSKA_REAL_SHARE_LINK
    }

    $managingProfileFixture = $false
    try {
        if ($ShareLink) {
            Set-ProfileShareLinkFixture -ProfileShareLink $ShareLink
            $managingProfileFixture = $true
        }

        foreach ($testClass in $TestClasses) {
            Assert-EmulatorOnline
            Write-Host "Phase: run $testClass"
            & $adb shell am force-stop io.acionyx.tunguska
            & $adb shell am force-stop io.acionyx.tunguska.trafficprobe
            & $adb shell am force-stop com.android.chrome
            Wait-ForTunguskaReset
            $instrumentArgs = @(
                "shell", "am", "instrument", "-w", "-r",
                "-e", "class", $testClass
            )
            if ($ExpectedPhase) {
                $instrumentArgs += @("-e", "expected_phase", $ExpectedPhase)
            }
            $instrumentArgs += @("io.acionyx.tunguska.test/androidx.test.runner.AndroidJUnitRunner")
            Invoke-CheckedInstrumentation -Step "Instrumentation $testClass" -Arguments $instrumentArgs
            Assert-EmulatorOnline
        }
        & "tools\emulator\pull-diagnostics.ps1"
    }
    catch {
        Write-Warning "Smoke run failed: $($_.Exception.Message)"
        try {
            & "tools\emulator\pull-diagnostics.ps1"
        } catch {
            Write-Warning "Failed to pull smoke diagnostics after failure: $($_.Exception.Message)"
        }
        throw
    }
    finally {
        if ($managingProfileFixture) {
            Clear-ProfileShareLinkFixture
        }
    }
}
finally {
    Pop-Location
}
