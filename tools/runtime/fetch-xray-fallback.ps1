param(
    [string[]]$Abis = @("x86_64", "arm64-v8a")
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\\..")
$jniLibsRoot = Join-Path $repoRoot "vpnservice\\src\\main\\jniLibs"
$cppSource = Join-Path $repoRoot "vpnservice\\src\\main\\cpp\\vpn_helper.cpp"
$ndkBin = Join-Path $env:LOCALAPPDATA "Android\\Sdk\\ndk\\28.0.13004108\\toolchains\\llvm\\prebuilt\\windows-x86_64\\bin"
$tmpRoot = Join-Path $repoRoot ".tmp\\xray-fallback"

New-Item -ItemType Directory -Force -Path $jniLibsRoot | Out-Null
New-Item -ItemType Directory -Force -Path $tmpRoot | Out-Null

function Get-XrayAssetName([string]$abi) {
    # The Linux Xray binaries run correctly inside the Android app sandbox in the
    # current MVP lane. Recent upstream reports show the Android-flavoured builds
    # can reach RUNNING while failing to carry real traffic on Android devices.
    switch ($abi) {
        "x86_64" { return "Xray-linux-64.zip" }
        "arm64-v8a" { return "Xray-linux-arm64-v8a.zip" }
        default { throw "Unsupported ABI for xray: $abi" }
    }
}

function Get-Tun2SocksAssetName([string]$abi) {
    switch ($abi) {
        "x86_64" { return "tun2socks-linux-amd64.zip" }
        "arm64-v8a" { return "tun2socks-linux-arm64.zip" }
        default { throw "Unsupported ABI for tun2socks: $abi" }
    }
}

function Get-ClangPath([string]$abi) {
    switch ($abi) {
        "x86_64" { return Join-Path $ndkBin "x86_64-linux-android26-clang++.cmd" }
        "arm64-v8a" { return Join-Path $ndkBin "aarch64-linux-android26-clang++.cmd" }
        default { throw "Unsupported ABI for vpnhelper: $abi" }
    }
}

foreach ($abi in $Abis) {
    $abiDir = Join-Path $jniLibsRoot $abi
    $abiTmp = Join-Path $tmpRoot $abi
    New-Item -ItemType Directory -Force -Path $abiDir | Out-Null
    New-Item -ItemType Directory -Force -Path $abiTmp | Out-Null

    $xrayZip = Join-Path $abiTmp "xray.zip"
    $tunZip = Join-Path $abiTmp "tun2socks.zip"
    $xrayAsset = Get-XrayAssetName $abi
    $tunAsset = Get-Tun2SocksAssetName $abi

    Invoke-WebRequest -Uri "https://github.com/XTLS/Xray-core/releases/latest/download/$xrayAsset" -OutFile $xrayZip
    Expand-Archive -Path $xrayZip -DestinationPath (Join-Path $abiTmp "xray") -Force
    $xrayBinary = Get-ChildItem -Path (Join-Path $abiTmp "xray") -Recurse -File | Where-Object { $_.Name -eq "xray" } | Select-Object -First 1
    if (-not $xrayBinary) {
        throw "Unable to locate xray binary for $abi"
    }
    Copy-Item -Force $xrayBinary.FullName (Join-Path $abiDir "libxray.so")

    Invoke-WebRequest -Uri "https://github.com/xjasonlyu/tun2socks/releases/latest/download/$tunAsset" -OutFile $tunZip
    Expand-Archive -Path $tunZip -DestinationPath (Join-Path $abiTmp "tun2socks") -Force
    $tunBinary = Get-ChildItem -Path (Join-Path $abiTmp "tun2socks") -Recurse -File | Where-Object { $_.Name -like "tun2socks*" } | Select-Object -First 1
    if (-not $tunBinary) {
        throw "Unable to locate tun2socks binary for $abi"
    }
    Copy-Item -Force $tunBinary.FullName (Join-Path $abiDir "libtun2socks.so")

    $clang = Get-ClangPath $abi
    & $clang -shared -fPIC -static-libstdc++ $cppSource -o (Join-Path $abiDir "libvpnhelper.so") -llog
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to compile libvpnhelper.so for $abi"
    }
}
