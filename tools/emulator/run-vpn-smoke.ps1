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
        "io.acionyx.tunguska.app.DenylistRoutingProofTest",
        "io.acionyx.tunguska.app.AllowlistRoutingProofTest"
    )
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\\..")).Path
$diagnosticsRemotePath = "files/tunguska-smoke"
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
    Invoke-Adb -Arguments @("shell", "cmd", "package", "enable", "io.acionyx.tunguska") | Out-Null

    if (-not $ShareLink -and $env:TUNGUSKA_REAL_SHARE_LINK) {
        $ShareLink = $env:TUNGUSKA_REAL_SHARE_LINK
    }

    if ($ShareLink) {
        $shareLinkHex = ([System.Text.Encoding]::UTF8.GetBytes($ShareLink) | ForEach-Object {
            $_.ToString("x2")
        }) -join ""
    }

    foreach ($testClass in $TestClasses) {
        Assert-EmulatorOnline
        Write-Host "Phase: run $testClass"
        $instrumentArgs = @(
            "shell", "am", "instrument", "-w", "-r",
            "-e", "class", $testClass
        )
        if ($ShareLink) {
            $instrumentArgs += @("-e", "profile_share_link_hex", $shareLinkHex)
        }
        if ($ExpectedPhase) {
            $instrumentArgs += @("-e", "expected_phase", $ExpectedPhase)
        }
        $instrumentArgs += @("io.acionyx.tunguska.test/androidx.test.runner.AndroidJUnitRunner")
        Invoke-CheckedInstrumentation -Step "Instrumentation $testClass" -Arguments $instrumentArgs
        Assert-EmulatorOnline
    }
    & "tools\emulator\pull-diagnostics.ps1"
}
finally {
    Pop-Location
}
