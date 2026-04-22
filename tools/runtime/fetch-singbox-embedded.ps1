param(
    [string]$SourceAarPath,
    [string]$GeoIpRuRuleSetPath,
    [string]$SingBoxRepoPath,
    [string]$SingGeoIpRepoPath,
    [string]$SingBoxRepoUrl = "https://github.com/SagerNet/sing-box.git",
    [string]$SingGeoIpRepoUrl = "https://github.com/SagerNet/sing-geoip.git",
    [string]$SingBoxRef = "99e1ffe03cc6dc18871b31e826554e10eb695515",
    [string]$GroupId = "io.acionyx.thirdparty",
    [string]$ArtifactId = "libbox-android",
    [string]$Version = "2026.04.22-99e1ffe",
    [switch]$Force
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$defaultSingBoxRepo = Join-Path $repoRoot ".tmp\sing-box-src"
$defaultSingGeoIpRepo = Join-Path $repoRoot ".tmp\sing-geoip-src"
$resolvedSingBoxRepo = if ($SingBoxRepoPath) { $SingBoxRepoPath } else { $defaultSingBoxRepo }
$resolvedSingGeoIpRepo = if ($SingGeoIpRepoPath) { $SingGeoIpRepoPath } else { $defaultSingGeoIpRepo }
$mavenRoot = Join-Path $repoRoot ".tmp\maven"
$groupPath = ($GroupId -split '\.') -join '\'
$targetDir = Join-Path $mavenRoot (Join-Path $groupPath (Join-Path $ArtifactId $Version))
$targetAar = Join-Path $targetDir "$ArtifactId-$Version.aar"
$targetPom = Join-Path $targetDir "$ArtifactId-$Version.pom"
$targetMetadata = Join-Path $targetDir "$ArtifactId-$Version.metadata.json"
$targetRuleSetDir = Join-Path $repoRoot "vpnservice\src\main\assets\singbox\rule-set"
$targetGeoIpRuRuleSet = Join-Path $targetRuleSetDir "geoip-ru.srs"

function Resolve-RequiredPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PathValue,
        [Parameter(Mandatory = $true)]
        [string]$Description
    )

    if (-not (Test-Path $PathValue)) {
        throw "Missing $Description at '$PathValue'."
    }
    return (Resolve-Path $PathValue).Path
}

function Get-RequiredCommandPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$CommandName
    )

    $command = Get-Command $CommandName -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    throw "Required command '$CommandName' is not available on PATH."
}

function Get-ExecutableName {
    param(
        [Parameter(Mandatory = $true)]
        [string]$BaseName
    )

    if ($IsWindows) {
        return "$BaseName.exe"
    }
    return $BaseName
}

function Resolve-GoExecutable {
    $command = Get-Command go -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $bundled = Join-Path $repoRoot (Join-Path ".tmp\go-toolchain\go\bin" (Get-ExecutableName -BaseName "go"))
    if (Test-Path $bundled) {
        return $bundled
    }

    throw "Go was not found on PATH and no bundled toolchain exists at '$bundled'."
}

function Get-GoEnvValue {
    param(
        [Parameter(Mandatory = $true)]
        [string]$GoExecutable,
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $value = & $GoExecutable env $Name
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to resolve 'go env $Name'."
    }
    return $value.Trim()
}

function Get-GoBinDirectory {
    param(
        [Parameter(Mandatory = $true)]
        [string]$GoExecutable
    )

    $gopath = Get-GoEnvValue -GoExecutable $GoExecutable -Name "GOPATH"
    if ([string]::IsNullOrWhiteSpace($gopath)) {
        throw "GOPATH is empty for '$GoExecutable'."
    }
    return Join-Path $gopath "bin"
}

function Resolve-JavaHome {
    $javaBinaryRelativePath = Join-Path "bin" (Get-ExecutableName -BaseName "java")
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME $javaBinaryRelativePath))) {
        return $env:JAVA_HOME
    }

    $bundledJdkRoot = Join-Path $repoRoot ".tmp\jdk17"
    if (Test-Path $bundledJdkRoot) {
        $candidate = Get-ChildItem -Path $bundledJdkRoot -Directory |
            Where-Object { Test-Path (Join-Path $_.FullName $javaBinaryRelativePath) } |
            Sort-Object LastWriteTimeUtc -Descending |
            Select-Object -First 1
        if ($candidate) {
            return $candidate.FullName
        }
    }

    return $null
}

function Resolve-AndroidSdkRoot {
    if ($env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) {
        return $env:ANDROID_HOME
    }
    if ($env:ANDROID_SDK_ROOT -and (Test-Path $env:ANDROID_SDK_ROOT)) {
        return $env:ANDROID_SDK_ROOT
    }

    $localProperties = Join-Path $repoRoot "local.properties"
    if (Test-Path $localProperties) {
        $sdkLine = Get-Content $localProperties |
            Where-Object { $_ -match '^sdk\.dir=' } |
            Select-Object -First 1
        if ($sdkLine) {
            $sdkPath = $sdkLine.Substring("sdk.dir=".Length).Replace('\\:', ':').Replace('\\', '\')
            if (Test-Path $sdkPath) {
                return $sdkPath
            }
        }
    }

    $defaultSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path $defaultSdk) {
        return $defaultSdk
    }

    $linuxSdk = Join-Path $HOME "Android/Sdk"
    if (Test-Path $linuxSdk) {
        return $linuxSdk
    }

    return $null
}

function Ensure-GitRepository {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepoPath,
        [Parameter(Mandatory = $true)]
        [string]$RepoUrl,
        [string]$RepoRef
    )

    $git = Get-RequiredCommandPath "git"

    if (Test-Path (Join-Path $RepoPath ".git")) {
        $resolvedPath = (Resolve-Path $RepoPath).Path
        if (-not [string]::IsNullOrWhiteSpace($RepoRef)) {
            Push-Location $resolvedPath
            try {
                & $git fetch --depth 1 origin $RepoRef
                if ($LASTEXITCODE -ne 0) {
                    throw "Failed to fetch ref '$RepoRef' from '$RepoUrl'."
                }
                & $git checkout --detach FETCH_HEAD
                if ($LASTEXITCODE -ne 0) {
                    throw "Failed to checkout ref '$RepoRef' in '$resolvedPath'."
                }
            } finally {
                Pop-Location
            }
        }
        return $resolvedPath
    }

    if (Test-Path $RepoPath) {
        $existing = Get-ChildItem -Path $RepoPath -Force -ErrorAction SilentlyContinue
        if ($existing) {
            throw "Refusing to clone into non-empty path '$RepoPath' because it is not a Git checkout."
        }
    } else {
        New-Item -ItemType Directory -Force -Path $RepoPath | Out-Null
        Remove-Item -LiteralPath $RepoPath -Force
    }

    $parent = Split-Path -Parent $RepoPath
    if ($parent) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    & $git clone --depth 1 $RepoUrl $RepoPath
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to clone '$RepoUrl' into '$RepoPath'."
    }
    $resolvedPath = (Resolve-Path $RepoPath).Path
    if (-not [string]::IsNullOrWhiteSpace($RepoRef)) {
        Push-Location $resolvedPath
        try {
            & $git fetch --depth 1 origin $RepoRef
            if ($LASTEXITCODE -ne 0) {
                throw "Failed to fetch ref '$RepoRef' from '$RepoUrl'."
            }
            & $git checkout --detach FETCH_HEAD
            if ($LASTEXITCODE -ne 0) {
                throw "Failed to checkout ref '$RepoRef' in '$resolvedPath'."
            }
        } finally {
            Pop-Location
        }
    }
    return $resolvedPath
}

function Invoke-ProcessChecked {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [Parameter(Mandatory = $true)]
        [string]$WorkingDirectory
    )

    Push-Location $WorkingDirectory
    try {
        & $FilePath @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Command failed in '$WorkingDirectory': $FilePath $($Arguments -join ' ')"
        }
    } finally {
        Pop-Location
    }
}

function Resolve-GomobileVersion {
    param(
        [Parameter(Mandatory = $true)]
        [string]$GoExecutable,
        [Parameter(Mandatory = $true)]
        [string]$RepoPath
    )

    Push-Location $RepoPath
    try {
        $version = & $GoExecutable list -m -f "{{.Version}}" github.com/sagernet/gomobile
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to resolve github.com/sagernet/gomobile version from '$RepoPath'."
        }
        $resolved = $version.Trim()
        if ([string]::IsNullOrWhiteSpace($resolved)) {
            return "latest"
        }
        return $resolved
    } finally {
        Pop-Location
    }
}

function Ensure-GomobileTools {
    param(
        [Parameter(Mandatory = $true)]
        [string]$GoExecutable,
        [Parameter(Mandatory = $true)]
        [string]$RepoPath
    )

    $goBin = Get-GoBinDirectory -GoExecutable $GoExecutable
    $gomobile = Join-Path $goBin (Get-ExecutableName -BaseName "gomobile")
    $gobind = Join-Path $goBin (Get-ExecutableName -BaseName "gobind")
    $version = Resolve-GomobileVersion -GoExecutable $GoExecutable -RepoPath $RepoPath

    if (-not (Test-Path $gomobile)) {
        Invoke-ProcessChecked -FilePath $GoExecutable -Arguments @("install", "github.com/sagernet/gomobile/cmd/gomobile@$version") -WorkingDirectory $RepoPath
    }
    if (-not (Test-Path $gobind)) {
        Invoke-ProcessChecked -FilePath $GoExecutable -Arguments @("install", "github.com/sagernet/gomobile/cmd/gobind@$version") -WorkingDirectory $RepoPath
    }

    Invoke-ProcessChecked -FilePath $gomobile -Arguments @("init") -WorkingDirectory $RepoPath
}

function Write-LibboxPom {
    param(
        [string]$PomPath,
        [string]$PomGroupId,
        [string]$PomArtifactId,
        [string]$PomVersion
    )

    @"
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>$PomGroupId</groupId>
  <artifactId>$PomArtifactId</artifactId>
  <version>$PomVersion</version>
  <packaging>aar</packaging>
  <name>$PomArtifactId</name>
  <description>Locally published sing-box libbox runtime for Tunguska.</description>
</project>
"@ | Set-Content -Path $PomPath -Encoding UTF8
}

function Build-LibboxAar {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepoPath,
        [Parameter(Mandatory = $true)]
        [string]$RepoUrl,
        [string]$RepoRef
    )

    $resolvedRepo = Ensure-GitRepository -RepoPath $RepoPath -RepoUrl $RepoUrl -RepoRef $RepoRef
    $go = Resolve-GoExecutable
    $goExeDir = Split-Path -Parent $go
    $goBinDir = Get-GoBinDirectory -GoExecutable $go
    $javaHome = Resolve-JavaHome
    $androidSdkRoot = Resolve-AndroidSdkRoot

    if (-not $javaHome) {
        throw "JAVA_HOME is not configured and no bundled JDK was found under '$repoRoot\.tmp\jdk17'."
    }
    if (-not $androidSdkRoot) {
        throw "Android SDK was not found. Set ANDROID_HOME/ANDROID_SDK_ROOT or create local.properties with sdk.dir."
    }

    $env:JAVA_HOME = $javaHome
    $env:ANDROID_HOME = $androidSdkRoot
    $env:ANDROID_SDK_ROOT = $androidSdkRoot
    $pathSeparator = [System.IO.Path]::PathSeparator
    $javaBinDir = Join-Path $env:JAVA_HOME "bin"
    $env:PATH = "$goExeDir$pathSeparator$goBinDir$pathSeparator$javaBinDir$pathSeparator$env:PATH"

    Ensure-GomobileTools -GoExecutable $go -RepoPath $resolvedRepo
    Invoke-ProcessChecked -FilePath $go -Arguments @("run", "./cmd/internal/build_libbox", "-target", "android") -WorkingDirectory $resolvedRepo

    $aar = Join-Path $resolvedRepo "libbox.aar"
    return Resolve-RequiredPath -PathValue $aar -Description "built libbox AAR"
}

function Build-GeoIpRuleSet {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepoPath,
        [Parameter(Mandatory = $true)]
        [string]$RepoUrl
    )

    $resolvedRepo = Ensure-GitRepository -RepoPath $RepoPath -RepoUrl $RepoUrl
    $go = Resolve-GoExecutable
    Invoke-ProcessChecked -FilePath $go -Arguments @("run", ".") -WorkingDirectory $resolvedRepo

    $ruleSetPath = Join-Path $resolvedRepo (Join-Path "rule-set" "geoip-ru.srs")
    return Resolve-RequiredPath -PathValue $ruleSetPath -Description "generated geoip-ru rule-set"
}

function Publish-LibboxAar {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ResolvedSourceAar,
        [Parameter(Mandatory = $true)]
        [string]$MetadataSource
    )

    Copy-Item -Path $ResolvedSourceAar -Destination $targetAar -Force

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($targetAar)
    try {
        foreach ($abi in @("arm64-v8a", "x86_64")) {
            $entryName = "jni/$abi/libbox.so"
            $entry = $archive.Entries | Where-Object { $_.FullName -eq $entryName } | Select-Object -First 1
            if (-not $entry) {
                throw "Published AAR is missing $entryName"
            }
        }
    } finally {
        $archive.Dispose()
    }

    Write-LibboxPom -PomPath $targetPom -PomGroupId $GroupId -PomArtifactId $ArtifactId -PomVersion $Version
    @"
{
  "groupId": "$GroupId",
  "artifactId": "$ArtifactId",
  "version": "$Version",
  "source": "$MetadataSource",
  "publishedAtUtc": "$([DateTime]::UtcNow.ToString("o"))"
}
"@ | Set-Content -Path $targetMetadata -Encoding UTF8

    Write-Host "Published libbox AAR to $targetAar"
    Write-Host "Published libbox POM to $targetPom"
}

New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
New-Item -ItemType Directory -Force -Path $targetRuleSetDir | Out-Null

if ((Test-Path $targetAar) -and (Test-Path $targetPom) -and (Test-Path $targetGeoIpRuRuleSet) -and -not $Force) {
    Write-Host "Sing-box embedded artifacts already exist in .tmp/maven and assets. Re-run with -Force to rebuild and overwrite them."
    exit 0
}

$resolvedSource = if ($SourceAarPath) {
    Resolve-RequiredPath -PathValue $SourceAarPath -Description "libbox AAR"
} else {
    Build-LibboxAar -RepoPath $resolvedSingBoxRepo -RepoUrl $SingBoxRepoUrl -RepoRef $SingBoxRef
}

$resolvedGeoIpRuRuleSet = if ($GeoIpRuRuleSetPath) {
    Resolve-RequiredPath -PathValue $GeoIpRuRuleSetPath -Description "geoip-ru.srs"
} elseif ((Test-Path $targetGeoIpRuRuleSet) -and -not $Force) {
    Resolve-RequiredPath -PathValue $targetGeoIpRuRuleSet -Description "staged geoip-ru.srs"
} else {
    Build-GeoIpRuleSet -RepoPath $resolvedSingGeoIpRepo -RepoUrl $SingGeoIpRepoUrl
}

if ((-not (Test-Path $targetAar)) -or (-not (Test-Path $targetPom)) -or $Force) {
    $metadataSource = if ($SourceAarPath) {
        "aar:$resolvedSource"
    } else {
        "repo:$resolvedSingBoxRepo"
    }
    Publish-LibboxAar -ResolvedSourceAar $resolvedSource -MetadataSource $metadataSource
}

if ((-not (Test-Path $targetGeoIpRuRuleSet)) -or $Force) {
    if ([System.StringComparer]::OrdinalIgnoreCase.Equals($resolvedGeoIpRuRuleSet, $targetGeoIpRuRuleSet)) {
        Write-Host "Reusing existing sing-box GeoIP rule-set at $targetGeoIpRuRuleSet"
    } else {
        Copy-Item -Path $resolvedGeoIpRuRuleSet -Destination $targetGeoIpRuRuleSet -Force
    }
    $ruleSetFile = Get-Item $targetGeoIpRuRuleSet
    if ($ruleSetFile.Length -le 0) {
        throw "Generated geoip-ru.srs at '$targetGeoIpRuRuleSet' is empty."
    }
    Write-Host "Staged sing-box GeoIP rule-set to $targetGeoIpRuRuleSet"
}
