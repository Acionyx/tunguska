param(
    [string]$ShareLink = "",
    [string]$ExpectedPhase = "RUNNING",
    [string]$JavaHome = "C:\Program Files\Java\jdk-24",
    [string]$AvdName = "tunguska-api36",
    [switch]$Headless,
    [string[]]$TestClasses = @(
        "io.acionyx.tunguska.app.VpnImportAndConnectTest",
        "io.acionyx.tunguska.app.ChromeIpProofTest",
        "io.acionyx.tunguska.app.SplitRoutingProofTest"
    )
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\\..")).Path
$diagnosticsRemotePath = "/sdcard/Download/tunguska-smoke"
. "$PSScriptRoot\UiAutomatorTools.ps1"

Push-Location $root
try {
    & "tools\emulator\start-emulator.ps1" -AvdName $AvdName -Headless:$Headless
    & "tools\emulator\ensure-chrome.ps1"
    Invoke-Adb -Arguments @("shell", "rm", "-rf", $diagnosticsRemotePath) | Out-Null
    Invoke-Adb -Arguments @("shell", "mkdir", "-p", $diagnosticsRemotePath) | Out-Null

    $env:JAVA_HOME = $JavaHome
    & .\gradlew.bat :trafficprobe:installDebug --no-daemon --no-build-cache --no-configuration-cache --no-parallel "-Dkotlin.incremental=false"

    if ($ShareLink) {
        $shareLinkHex = ([System.Text.Encoding]::UTF8.GetBytes($ShareLink) | ForEach-Object {
            $_.ToString("x2")
        }) -join ""
    }

    foreach ($testClass in $TestClasses) {
        $gradleArgs = @(
            ":app:connectedDebugAndroidTest",
            "--no-daemon",
            "--no-build-cache",
            "--no-configuration-cache",
            "--no-parallel",
            "-Dkotlin.incremental=false",
            "-Pandroid.testInstrumentationRunnerArguments.class=$testClass"
        )

        if ($ShareLink) {
            $gradleArgs += "-Pandroid.testInstrumentationRunnerArguments.profile_share_link_hex=$shareLinkHex"
        }

        if ($ExpectedPhase) {
            $gradleArgs += "-Pandroid.testInstrumentationRunnerArguments.expected_phase=$ExpectedPhase"
        }

        & .\gradlew.bat @gradleArgs
    }
    & "tools\emulator\pull-diagnostics.ps1"
}
finally {
    Pop-Location
}
