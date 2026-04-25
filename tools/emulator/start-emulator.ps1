param(
    [string]$AvdName = "tunguska-api34",
    [switch]$Headless,
    [switch]$ColdBoot,
    [switch]$WipeData,
    [switch]$HardReset,
    [string]$CameraBack = "emulated",
    [int]$MemoryMb = 16384,
    [int]$CpuCores = 12,
    [int]$VmHeapMb = 512,
    [decimal]$AnimatorDurationScale = 1
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\..\common\PathTools.ps1"

$sdkRoot = Get-AndroidSdkRoot
$emulator = Join-Path $sdkRoot "emulator\emulator.exe"
$adb = Get-AdbPath
$avdConfig = Join-Path $env:USERPROFILE ".android\avd\$AvdName.avd\config.ini"

if (-not (Test-Path $emulator)) {
    throw "Android Emulator is not installed at $emulator"
}

if (-not (Test-Path $avdConfig)) {
    throw "AVD config not found: $avdConfig"
}

function Set-IniValue {
    param(
        [string]$Path,
        [string]$Key,
        [string]$Value
    )

    $content = Get-Content $Path
    $updated = $false
    for ($i = 0; $i -lt $content.Length; $i++) {
        if ($content[$i] -like "$Key=*") {
            $content[$i] = "$Key=$Value"
            $updated = $true
            break
        }
    }
    if (-not $updated) {
        $content += "$Key=$Value"
    }
    Set-Content -Path $Path -Value $content -Encoding ascii
}

function Get-RunningEmulatorProcesses {
    $avdPattern = [regex]'(?:^|\s)-avd\s+(?:"([^"]+)"|(\S+))'
    $liveProcessIds = @(
        Get-Process -ErrorAction SilentlyContinue |
            ForEach-Object { $_.Id }
    )
    $processes = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object {
            ($_.Name -like "emulator*.exe" -or $_.Name -like "qemu-system*.exe") -and
                ($liveProcessIds -contains [int]$_.ProcessId)
        }

    return @(
        $processes | ForEach-Object {
            $commandLine = [string]$_.CommandLine
            $avdMatch = $avdPattern.Match($commandLine)
            $resolvedAvdName = if ($avdMatch.Success) {
                if ($avdMatch.Groups[1].Success) {
                    $avdMatch.Groups[1].Value
                } else {
                    $avdMatch.Groups[2].Value
                }
            }

            [pscustomobject]@{
                ProcessId = [int]$_.ProcessId
                ParentProcessId = [int]$_.ParentProcessId
                Name = [string]$_.Name
                AvdName = $resolvedAvdName
                Headless = $commandLine -match '(?:^|\s)-no-window(?:\s|$)'
                CommandLine = $commandLine
            }
        }
    )
}

function Get-RunningAvdProcesses {
    param([string]$TargetAvdName)

    return @(
        Get-RunningEmulatorProcesses |
            Where-Object { $_.AvdName -eq $TargetAvdName }
    )
}

function Test-AvdHasVisibleWindow {
    param([string]$TargetAvdName)

    $runningProcesses = @(Get-RunningEmulatorProcesses)
    $rootIds = @(
        $runningProcesses |
            Where-Object { $_.AvdName -eq $TargetAvdName } |
            ForEach-Object { $_.ProcessId }
    )
    if (-not $rootIds) {
        return $false
    }

    $trackedIds = New-Object 'System.Collections.Generic.HashSet[int]'
    $pendingIds = New-Object 'System.Collections.Generic.Queue[int]'
    foreach ($rootId in $rootIds) {
        $null = $trackedIds.Add($rootId)
        $pendingIds.Enqueue($rootId)
    }

    while ($pendingIds.Count -gt 0) {
        $parentId = $pendingIds.Dequeue()
        foreach ($child in $runningProcesses | Where-Object { $_.ParentProcessId -eq $parentId }) {
            if ($trackedIds.Add($child.ProcessId)) {
                $pendingIds.Enqueue($child.ProcessId)
            }
        }
    }

    return @(
        Get-Process -ErrorAction SilentlyContinue |
            Where-Object { $trackedIds.Contains($_.Id) -and $_.MainWindowHandle -ne 0 }
    ).Count -gt 0
}

function Stop-EmulatorHard {
    param([string]$AdbPath)

    try {
        & $AdbPath emu kill 2>$null | Out-Null
    } catch {
    }

    Get-Process |
        Where-Object { $_.ProcessName -like "emulator*" -or $_.ProcessName -like "qemu-system*" } |
        Stop-Process -Force -ErrorAction SilentlyContinue

    $deadline = (Get-Date).AddSeconds(20)
    do {
        if (-not (Get-RunningEmulatorProcesses)) {
            return
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)

    throw "Existing emulator processes did not exit after hard reset."
}

function Wait-ForEmulatorWindow {
    param(
        [Parameter(Mandatory = $true)]
        [System.Diagnostics.Process]$Process,
        [int]$TimeoutSeconds = 45
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        Start-Sleep -Milliseconds 500
        try {
            $null = $Process.Refresh()
        } catch {
            break
        }
        if ($Process.HasExited) {
            throw "The emulator process exited before creating a visible window."
        }
        $visibleWindow = Get-Process |
            Where-Object {
                ($_.ProcessName -like "emulator*" -or $_.ProcessName -like "qemu-system*") -and
                    $_.MainWindowHandle -ne 0
            } |
            Select-Object -First 1
        if ($visibleWindow) {
            return
        }
    } while ((Get-Date) -lt $deadline)

    $visibleWindow = Get-Process |
        Where-Object {
            ($_.ProcessName -like "emulator*" -or $_.ProcessName -like "qemu-system*") -and
                $_.MainWindowHandle -ne 0
        } |
        Select-Object -First 1
    if (-not $visibleWindow) {
        throw "The headed emulator did not create a visible window within ${TimeoutSeconds}s."
    }
}

function Resolve-EmulatorAvdName {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Serial
    )

    $consoleName = @(
        & $adb -s $Serial emu avd name 2>$null |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ -and $_ -notmatch '^OK$' }
    ) | Select-Object -Last 1
    if ($consoleName) {
        return $consoleName
    }

    foreach ($propertyName in @("ro.boot.qemu.avd_name", "persist.sys.avd_name")) {
        $propertyValue = (& $adb -s $Serial shell getprop $propertyName 2>$null).Trim()
        if ($propertyValue) {
            return $propertyValue
        }
    }

    return $null
}

function Wait-ForAdbEmulator {
    param(
        [Parameter(Mandatory = $true)]
        [string]$TargetAvdName,
        [int]$TimeoutSeconds = 90
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $devices = & $adb devices 2>$null
        $emulatorEntries = @(
            $devices | ForEach-Object {
                if ($_ -match '^(emulator-\d+)\s+(\S+)$') {
                    [pscustomobject]@{
                        Serial = $Matches[1]
                        State = $Matches[2]
                    }
                }
            }
        )

        foreach ($entry in $emulatorEntries) {
            if ($entry.State -ne 'device') {
                continue
            }
            $resolvedAvdName = Resolve-EmulatorAvdName -Serial $entry.Serial
            if ($resolvedAvdName -eq $TargetAvdName) {
                return $entry.Serial
            }
        }

        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    throw "AVD '$TargetAvdName' did not register with adb within ${TimeoutSeconds}s."
}

Set-IniValue -Path $avdConfig -Key "hw.cpu.ncore" -Value $CpuCores
Set-IniValue -Path $avdConfig -Key "hw.ramSize" -Value "${MemoryMb}M"
Set-IniValue -Path $avdConfig -Key "vm.heapSize" -Value $VmHeapMb
Set-IniValue -Path $avdConfig -Key "hw.gpu.enabled" -Value "yes"
Set-IniValue -Path $avdConfig -Key "hw.gpu.mode" -Value "host"
Set-IniValue -Path $avdConfig -Key "fastboot.forceFastBoot" -Value "yes"
Set-IniValue -Path $avdConfig -Key "showDeviceFrame" -Value "yes"

$effectiveColdBoot = $ColdBoot -or $HardReset
$effectiveWipeData = $WipeData -or $HardReset

if ($HardReset) {
    Stop-EmulatorHard -AdbPath $adb
}

$runningTargetAvd = @(Get-RunningAvdProcesses -TargetAvdName $AvdName)
if ($runningTargetAvd -and -not $Headless -and -not (Test-AvdHasVisibleWindow -TargetAvdName $AvdName)) {
    throw "AVD '$AvdName' is already running without a visible window. Stop the existing instance or rerun with -HardReset."
}

if (-not $runningTargetAvd) {
    $arguments = @(
        "-avd", $AvdName,
        "-no-boot-anim",
        "-no-audio",
        "-gpu", "host",
        "-memory", $MemoryMb,
        "-cores", $CpuCores,
        "-camera-back", $CameraBack
    )
    if ($Headless) {
        $arguments += "-no-window"
    }
    if ($effectiveColdBoot) {
        $arguments += "-no-snapshot-load"
    }
    if ($effectiveWipeData) {
        $arguments += "-wipe-data"
        $arguments += "-no-snapshot-save"
    }
    $startedProcess = Start-Process -FilePath $emulator -ArgumentList $arguments -PassThru
    if (-not $Headless) {
        Wait-ForEmulatorWindow -Process $startedProcess
    }
}

$serial = Wait-ForAdbEmulator -TargetAvdName $AvdName
$deadline = (Get-Date).AddMinutes(6)
do {
    Start-Sleep -Seconds 3
    $boot = (& $adb -s $serial shell getprop sys.boot_completed 2>$null).Trim()
} while ((Get-Date) -lt $deadline -and $boot -ne "1")

if ($boot -ne "1") {
    throw "Emulator did not finish booting in time."
}

& $adb -s $serial shell settings put global window_animation_scale 0 | Out-Null
& $adb -s $serial shell settings put global transition_animation_scale 0 | Out-Null
& $adb -s $serial shell settings put global animator_duration_scale $AnimatorDurationScale | Out-Null

Write-Host "Emulator ready (headed=$(!$Headless), hardReset=$HardReset, memoryMb=$MemoryMb, cpuCores=$CpuCores, animatorDurationScale=$AnimatorDurationScale)."
