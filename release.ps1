# Builds splitplay, runs every offline check, and assembles dist\splitplay-<version>.zip.
#
# Everything here is ASCII on purpose: Windows PowerShell 5.1 reads a script without a BOM as
# ANSI, so non-ASCII text turns into mojibake and the parser dies on the first line after it.
#
#   .\release.ps1                  build + check + zip
#   .\release.ps1 -SkipChecks      just build and zip
#   .\release.ps1 -JavaHome 'C:\path\to\jdk21'
param(
    [string] $JavaHome = '',
    [switch] $SkipChecks
)

$ErrorActionPreference = 'Stop'
$root = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
Push-Location $root
try {
    # An ambient JAVA_HOME is often left over from another JDK (or another drive) and Gradle then fails
    # with "JAVA_HOME is set to an invalid directory", which says nothing about what to fix. Only accept
    # a directory that actually holds a compiler.
    $candidates = @()
    if ($JavaHome -ne '') { $candidates += $JavaHome }
    if ($env:JAVA_HOME -ne '') { $candidates += $env:JAVA_HOME }
    $candidates += 'E:\Re.224315\Java 21'
    $candidates += @(Get-ChildItem 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -ExpandProperty FullName)
    $resolved = ''
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path (Join-Path $candidate 'bin\javac.exe'))) {
            $resolved = $candidate
            break
        }
    }
    if ($resolved -eq '') {
        throw "no JDK found; pass -JavaHome <path to a JDK 21> (tried: $($candidates -join ', '))"
    }
    $env:JAVA_HOME = $resolved
    Write-Host "== jdk: $resolved ==" -ForegroundColor DarkGray

    Write-Host "== build ==" -ForegroundColor Cyan
    & "$root\gradlew.bat" build --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "gradle build failed with exit code $LASTEXITCODE"
    }

    $jar = Get-ChildItem "$root\build\libs\splitplay-*.jar" |
        Where-Object { $_.Name -notlike '*-sources.jar' } |
        Select-Object -First 1
    if ($null -eq $jar) {
        throw 'no splitplay jar in build\libs'
    }
    $version = $jar.BaseName -replace '^splitplay-', ''
    Write-Host "== jar $($jar.Name) ($($jar.Length) bytes) ==" -ForegroundColor Cyan

    $checks = @('-LogicCheck', '-KeyMapCheck', '-CommandLineCheck', '-ClaimCheck', '-ConfigCheck', '-MirrorCheck', '-JarCheck', '-MixinCheck', '-HeldCheck', '-ScreenCheck', '-QueueCheck')
    if (-not $SkipChecks) {
        Write-Host "== checks ==" -ForegroundColor Cyan
        foreach ($check in $checks) {
            $output = & powershell -NoProfile -ExecutionPolicy Bypass -File "$root\build.ps1" $check 2>&1
            $line = $output | Select-String -Pattern 'pass=' | Select-Object -First 1
            if ($null -eq $line -or $line -notmatch 'fail=0') {
                $output | ForEach-Object { Write-Host $_ }
                throw "check $check failed"
            }
            Write-Host "  $check -> $line"
        }
    }

    Write-Host "== dist ==" -ForegroundColor Cyan
    $dist = Join-Path $root 'dist'
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    Get-ChildItem $dist -File |
        Where-Object { $_.Name -like 'splitplay-*.zip' -or $_.Name -like 'splitplay-*.jar' } |
        Remove-Item -Force
    Copy-Item $jar.FullName (Join-Path $dist $jar.Name) -Force
    # A release without its documents is not a release; fail loudly instead of shipping a bare jar.
    foreach ($doc in @('LICENSE', 'README.md', 'CHANGELOG.md', 'INSTALL.txt')) {
        $source = Join-Path $root $doc
        if (-not (Test-Path $source)) { throw "missing release document: $doc" }
        Copy-Item $source $dist -Force
    }

    $zip = Join-Path $dist "splitplay-$version.zip"
    Compress-Archive -Path (Join-Path $dist '*') -DestinationPath $zip -Force

    $hash = (Get-FileHash $jar.FullName -Algorithm SHA256).Hash
    Write-Host ''
    Write-Host "jar    : $($jar.Name) ($($jar.Length) bytes)"
    Write-Host "sha256 : $hash"
    Write-Host "zip    : $zip ($((Get-Item $zip).Length) bytes)"
    Write-Host ''
    Write-Host 'Upload the zip to GitHub releases. The repository itself needs no build output:' -ForegroundColor DarkGray
    Write-Host 'dist/ and build/ are ignored, so commit sources, gradlew and the wrapper jar.' -ForegroundColor DarkGray
}
finally {
    Pop-Location
}
