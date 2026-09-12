param(
    [string]$NdkRoot = "C:\Users\lijil\Documents\SDKs\android\ndk\28.2.13676358",
    [string]$OutputRoot = "build\libtailscale",
    [switch]$AllowNetwork
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$sourceRoot = Join-Path $repoRoot 'third_party\libtailscale'
$commit = '59d4bb82744915815178e0f0776d60026a397ee7'
$expectedGo = 'go1.25.5'
$expectedNdk = '28.2.13676358'

function Require-Command([string]$name) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
        throw "Missing required command: $name"
    }
}

Require-Command git
Require-Command go
$goVersion = (go version).Split(' ')[2]
if ($goVersion -ne $expectedGo) {
    $env:GOTOOLCHAIN = $expectedGo
    $goVersion = (go version).Split(' ')[2]
}
if ($goVersion -ne $expectedGo) {
    throw "Expected $expectedGo, found $goVersion"
}
if ((Split-Path -Leaf $NdkRoot) -ne $expectedNdk -or -not (Test-Path $NdkRoot)) {
    throw "Expected Android NDK $expectedNdk at `$NdkRoot"
}
$toolchain = Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin'
if (-not (Test-Path $toolchain)) { throw "Android LLVM toolchain is unavailable" }

if (-not (Test-Path $sourceRoot)) {
    if (-not $AllowNetwork) {
        throw "libtailscale source is absent; rerun with -AllowNetwork to clone the pinned commit"
    }
    git clone https://github.com/tailscale/libtailscale.git $sourceRoot
}
$actualCommit = (git -C $sourceRoot rev-parse HEAD).Trim()
if ($actualCommit -ne $commit) {
    if (-not $AllowNetwork) {
        throw "libtailscale must be checked out at $commit; found $actualCommit"
    }
    git -C $sourceRoot fetch --depth 1 origin $commit
    git -C $sourceRoot checkout --detach $commit
}

$outputRoot = Join-Path $repoRoot $OutputRoot
if (Test-Path $outputRoot) {
    Remove-Item -Recurse -Force $outputRoot
}
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
$targets = @(
    @{ Abi = 'arm64-v8a'; Arch = 'arm64'; Cc = 'aarch64-linux-android26-clang' },
    @{ Abi = 'armeabi-v7a'; Arch = 'arm'; Cc = 'armv7a-linux-androideabi26-clang' },
    @{ Abi = 'x86_64'; Arch = 'amd64'; Cc = 'x86_64-linux-android26-clang' }
)
foreach ($target in $targets) {
    $destination = Join-Path $outputRoot $target.Abi
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    Push-Location $sourceRoot
    try {
        $env:CGO_ENABLED = '1'
        $env:GOOS = 'android'
        $env:GOARCH = $target.Arch
        $env:CC = Join-Path $toolchain ($target.Cc + '.cmd')
        go build -trimpath -buildvcs=false -buildmode=c-shared `
            -ldflags '-extldflags=-Wl,-soname,libtailscale.so' `
            -o (Join-Path $destination 'libtailscale.so') .
        if ($LASTEXITCODE -ne 0) {
            throw "libtailscale Android build failed for $($target.Abi)"
        }
        Copy-Item tailscale.h (Join-Path $destination 'tailscale.h') -Force
    } finally {
        Pop-Location
    }
}
Get-ChildItem $outputRoot -Recurse -File | Get-FileHash -Algorithm SHA256 |
    Sort-Object Path | Format-Table Algorithm, Hash, Path -AutoSize