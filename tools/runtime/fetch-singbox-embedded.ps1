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
    [ValidateSet("LocalMaven", "GitHubPackages", "Both")]
    [string]$PublishTarget = "LocalMaven",
    [string]$GitHubPackagesOwner = "Acionyx",
    [string]$GitHubPackagesRepository = "tunguska",
    [string]$GitHubPackagesUser,
    [string]$GitHubPackagesToken,
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
$groupPathUrl = ($GroupId -split '\.') -join '/'
$targetDir = Join-Path $mavenRoot (Join-Path $groupPath (Join-Path $ArtifactId $Version))
$targetAar = Join-Path $targetDir "$ArtifactId-$Version.aar"
$targetPom = Join-Path $targetDir "$ArtifactId-$Version.pom"
$targetMetadata = Join-Path $targetDir "$ArtifactId-$Version.metadata.json"
$targetRuleSetDir = Join-Path $repoRoot "vpnservice\src\main\assets\singbox\rule-set"
$targetGeoIpRuRuleSet = Join-Path $targetRuleSetDir "geoip-ru.srs"
$publisherProjectDir = Join-Path $repoRoot "tools\runtime\libbox-publisher"
$githubPackagesRepositoryUrl = "https://maven.pkg.github.com/$GitHubPackagesOwner/$GitHubPackagesRepository"
$githubPackagesPomUrl = "$githubPackagesRepositoryUrl/$groupPathUrl/$ArtifactId/$Version/$ArtifactId-$Version.pom"
$publishLocalMaven = $PublishTarget -eq "LocalMaven" -or $PublishTarget -eq "Both"
$publishGitHubPackages = $PublishTarget -eq "GitHubPackages" -or $PublishTarget -eq "Both"

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

    if ($env:OS -eq "Windows_NT") {
        return "$BaseName.exe"
    }
    return $BaseName
}

function Get-GradleWrapperPath {
    $wrapperName = if ($env:OS -eq "Windows_NT") { "gradlew.bat" } else { "gradlew" }
    return Resolve-RequiredPath -PathValue (Join-Path $repoRoot $wrapperName) -Description "Gradle wrapper"
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

function Assert-LibboxAarContents {
    param(
        [Parameter(Mandatory = $true)]
        [string]$AarPath
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($AarPath)
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
}

function Resolve-GitHubPackagesCredentials {
    param(
        [string]$User,
        [string]$Token
    )

    $resolvedUser = if (-not [string]::IsNullOrWhiteSpace($User)) {
        $User
    } elseif (-not [string]::IsNullOrWhiteSpace($env:GITHUB_PACKAGES_USER)) {
        $env:GITHUB_PACKAGES_USER
    } elseif (-not [string]::IsNullOrWhiteSpace($env:GITHUB_ACTOR)) {
        $env:GITHUB_ACTOR
    } else {
        $null
    }

    $resolvedToken = if (-not [string]::IsNullOrWhiteSpace($Token)) {
        $Token
    } elseif (-not [string]::IsNullOrWhiteSpace($env:GITHUB_PACKAGES_TOKEN)) {
        $env:GITHUB_PACKAGES_TOKEN
    } elseif (-not [string]::IsNullOrWhiteSpace($env:GITHUB_TOKEN)) {
        $env:GITHUB_TOKEN
    } else {
        $null
    }

    if ([string]::IsNullOrWhiteSpace($resolvedUser) -or [string]::IsNullOrWhiteSpace($resolvedToken)) {
        throw (
            "GitHub Packages publishing requires credentials. Set GitHubPackagesUser/GitHubPackagesToken, " +
            "or GITHUB_PACKAGES_USER/GITHUB_PACKAGES_TOKEN to a classic PAT with write:packages."
        )
    }

    return @{
        User = $resolvedUser
        Token = $resolvedToken
    }
}

function Test-RemoteMavenArtifactExists {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PomUrl,
        [Parameter(Mandatory = $true)]
        [string]$User,
        [Parameter(Mandatory = $true)]
        [string]$Token
    )

    $authBytes = [System.Text.Encoding]::ASCII.GetBytes("${User}:${Token}")
    $headers = @{
        Authorization = "Basic $([Convert]::ToBase64String($authBytes))"
    }

    try {
        $response = Invoke-WebRequest -Uri $PomUrl -Method Head -Headers $headers
        return $response.StatusCode -eq 200
    } catch [System.Net.WebException] {
        $statusCode = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { $null }
        if ($statusCode -eq 404) {
            return $false
        }
        if ($statusCode -in @(401, 403)) {
            throw (
                "GitHub Packages rejected the credential for '$PomUrl'. " +
                "Use a classic PAT with read:packages to query existing versions and write:packages to publish."
            )
        }
        throw
    }
}

function Invoke-LibboxPublisher {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ResolvedSourceAar,
        [Parameter(Mandatory = $true)]
        [string]$RepositoryUrl,
        [string]$RepositoryUser,
        [string]$RepositoryPassword
    )

    Assert-LibboxAarContents -AarPath $ResolvedSourceAar

    $gradleWrapper = Get-GradleWrapperPath
    $args = @(
        "-p", $publisherProjectDir,
        "publishLibboxPublicationToTargetRepository",
        "--no-configuration-cache",
        "-PlibboxAarPath=$ResolvedSourceAar",
        "-PlibboxGroupId=$GroupId",
        "-PlibboxArtifactId=$ArtifactId",
        "-PlibboxVersion=$Version",
        "-PlibboxPomName=$ArtifactId",
        "-PlibboxPomDescription=Pinned sing-box libbox Android runtime for Tunguska.",
        "-PpublishRepositoryUrl=$RepositoryUrl"
    )

    if (-not [string]::IsNullOrWhiteSpace($RepositoryUser)) {
        if ([string]::IsNullOrWhiteSpace($RepositoryPassword)) {
            throw "RepositoryPassword is required when RepositoryUser is set."
        }
        $args += "-PpublishRepositoryUser=$RepositoryUser"
        $args += "-PpublishRepositoryPassword=$RepositoryPassword"
    }

    Invoke-ProcessChecked -FilePath $gradleWrapper -Arguments $args -WorkingDirectory $repoRoot
}

function Write-LibboxLocalMetadata {
    param(
        [Parameter(Mandatory = $true)]
        [string]$MetadataSource
    )

    @"
{
  "groupId": "$GroupId",
  "artifactId": "$ArtifactId",
  "version": "$Version",
  "source": "$MetadataSource",
  "publishedAtUtc": "$([DateTime]::UtcNow.ToString("o"))"
}
"@ | Set-Content -Path $targetMetadata -Encoding UTF8
}

function Publish-LibboxToLocalMaven {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ResolvedSourceAar,
        [Parameter(Mandatory = $true)]
        [string]$MetadataSource
    )

    New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
    Invoke-LibboxPublisher -ResolvedSourceAar $ResolvedSourceAar -RepositoryUrl $mavenRoot
    Write-LibboxLocalMetadata -MetadataSource $MetadataSource

    $publishedAar = Resolve-RequiredPath -PathValue $targetAar -Description "published local libbox AAR"
    Resolve-RequiredPath -PathValue $targetPom -Description "published local libbox POM" | Out-Null
    Assert-LibboxAarContents -AarPath $publishedAar

    Write-Host "Published libbox AAR to $targetAar"
    Write-Host "Published libbox POM to $targetPom"
}

function Publish-LibboxToGitHubPackages {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ResolvedSourceAar,
        [Parameter(Mandatory = $true)]
        [hashtable]$Credentials
    )

    Invoke-LibboxPublisher `
        -ResolvedSourceAar $ResolvedSourceAar `
        -RepositoryUrl $githubPackagesRepositoryUrl `
        -RepositoryUser $Credentials.User `
        -RepositoryPassword $Credentials.Token

    Write-Host "Published ${GroupId}:${ArtifactId}:${Version} to $githubPackagesRepositoryUrl"
}

$githubCredentials = $null
$remotePackageReady = $false
if ($publishGitHubPackages) {
    $githubCredentials = Resolve-GitHubPackagesCredentials -User $GitHubPackagesUser -Token $GitHubPackagesToken
    if (-not $Force) {
        $remotePackageReady = Test-RemoteMavenArtifactExists `
            -PomUrl $githubPackagesPomUrl `
            -User $githubCredentials.User `
            -Token $githubCredentials.Token
    }
}

New-Item -ItemType Directory -Force -Path $targetRuleSetDir | Out-Null

$localPackageReady = (Test-Path $targetAar) -and (Test-Path $targetPom)
$ruleSetReady = Test-Path $targetGeoIpRuRuleSet

if (
    -not $Force -and
    (-not $publishLocalMaven -or $localPackageReady) -and
    (-not $publishGitHubPackages -or $remotePackageReady) -and
    $ruleSetReady
) {
    Write-Host "Sing-box embedded artifacts already satisfy '$PublishTarget'. Re-run with -Force to rebuild and overwrite them."
    exit 0
}

$resolvedSource = if ($SourceAarPath) {
    Resolve-RequiredPath -PathValue $SourceAarPath -Description "libbox AAR"
} elseif ((Test-Path $targetAar) -and -not $Force) {
    Resolve-RequiredPath -PathValue $targetAar -Description "cached local libbox AAR"
} else {
    Build-LibboxAar -RepoPath $resolvedSingBoxRepo -RepoUrl $SingBoxRepoUrl -RepoRef $SingBoxRef
}

$metadataSource = if ($SourceAarPath) {
    "aar:$resolvedSource"
} elseif ([System.StringComparer]::OrdinalIgnoreCase.Equals($resolvedSource, $targetAar)) {
    "local:$resolvedSource"
} else {
    "repo:$resolvedSingBoxRepo@$SingBoxRef"
}

$resolvedGeoIpRuRuleSet = if ($GeoIpRuRuleSetPath) {
    Resolve-RequiredPath -PathValue $GeoIpRuRuleSetPath -Description "geoip-ru.srs"
} elseif ((Test-Path $targetGeoIpRuRuleSet) -and -not $Force) {
    Resolve-RequiredPath -PathValue $targetGeoIpRuRuleSet -Description "staged geoip-ru.srs"
} else {
    Build-GeoIpRuleSet -RepoPath $resolvedSingGeoIpRepo -RepoUrl $SingGeoIpRepoUrl
}

if ($publishLocalMaven) {
    if ($localPackageReady -and -not $Force) {
        Write-Host "Reusing existing local libbox Maven package at $targetDir"
    } else {
        Publish-LibboxToLocalMaven -ResolvedSourceAar $resolvedSource -MetadataSource $metadataSource
    }
}

if ($publishGitHubPackages) {
    if ($remotePackageReady -and -not $Force) {
        Write-Host "GitHub Packages already contains ${GroupId}:${ArtifactId}:${Version}"
    } else {
        Publish-LibboxToGitHubPackages -ResolvedSourceAar $resolvedSource -Credentials $githubCredentials
    }
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
