# Build script for the standalone raw-input probe and the small offline checks.
# ASCII only on purpose: PowerShell 5.1 misreads UTF-8 without BOM when the file is loaded by -File.
#
# The mod itself is built by Gradle (Loom remaps the mixin targets); see README.
#
# Usage:
#   .\build.ps1                # build\splitplay-probe.jar (no Minecraft needed, except lwjgl-glfw)
#   .\build.ps1 -KeyMapCheck   # compile and run the Windows VK -> GLFW key table test
#   .\build.ps1 -MirrorCheck   # compile and run the second-window mods mirror test
#   .\build.ps1 -JnaDir <dir>  # directory holding jna-5*.jar / jna-platform-5*.jar
#   .\build.ps1 -McDir <dir>   # the .minecraft directory (for jna and lwjgl jars)

[CmdletBinding()]
param(
    [string]$JnaDir = '',
    [string]$JavaHome = '',
    [string]$OutDir = '',
    [string]$McDir = '',
    [switch]$KeyMapCheck,
    [switch]$CommandLineCheck,
    [switch]$LogicCheck,
    [switch]$JarCheck,
    [switch]$ClaimCheck,
    [switch]$ConfigCheck,
    [switch]$MirrorCheck,
    [switch]$MixinCheck,
    [switch]$HeldCheck,
    [switch]$ScreenCheck,
    [switch]$QueueCheck,
    [string]$MixinDir = '',
    [string]$Jar = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $OutDir) { $OutDir = Join-Path $root 'build' }
if (-not $McDir) {
    foreach ($c in @('E:\Re.224315\.minecraft', (Join-Path $env:APPDATA '.minecraft'))) {
        if ($c -and (Test-Path $c)) { $McDir = $c; break }
    }
}
if (-not $JavaHome) {
    foreach ($c in @('E:\Re.224315\Java 21', "$env:ProgramFiles\Java\jdk-21", $env:JAVA_HOME)) {
        if ($c -and (Test-Path (Join-Path $c 'bin\javac.exe'))) { $JavaHome = $c; break }
    }
}
if (-not $JavaHome) { throw 'no JDK found; pass -JavaHome <jdk 21>' }

function Find-Jars {
    param([string]$Dir, [string]$Pattern)
    if (-not $Dir -or -not (Test-Path $Dir)) { return @() }
    return @(Get-ChildItem -Path $Dir -Recurse -File -Filter $Pattern | Sort-Object FullName |
        ForEach-Object { $_.FullName })
}

if (-not $JnaDir) {
    foreach ($c in @(
        (Join-Path $McDir 'libraries\net\java\dev\jna'),
        (Join-Path $env:APPDATA '.minecraft\libraries\net\java\dev\jna')
    )) {
        if ($c -and (Test-Path $c)) { $JnaDir = $c; break }
    }
}
if (-not $JnaDir) { throw 'JNA not found; pass -JnaDir <dir containing jna-*.jar>' }

$jnaCore = @(Find-Jars $JnaDir 'jna-5*.jar' | Where-Object { $_ -notmatch 'jna-platform' })
$jnaPlatform = @(Find-Jars $JnaDir 'jna-platform-5*.jar')
if ($jnaCore.Count -eq 0 -or $jnaPlatform.Count -eq 0) {
    throw "expected jna-5*.jar and jna-platform-5*.jar under $JnaDir"
}
$jnaCp = @($jnaCore[0], $jnaPlatform[0])

$glfw = @(Find-Jars (Join-Path $McDir 'libraries\org\lwjgl\lwjgl-glfw') 'lwjgl-glfw-3*.jar' |
    Where-Object { $_ -notmatch 'natives|sources' })
if ($glfw.Count -eq 0) { throw 'lwjgl-glfw jar not found under <McDir>\libraries; pass -McDir' }

$gson = @(Find-Jars (Join-Path $McDir 'libraries\com\google\code\gson') 'gson-2*.jar' |
    Where-Object { $_ -notmatch 'sources' })

$javac = Join-Path $JavaHome 'bin\javac.exe'
$java = Join-Path $JavaHome 'bin\java.exe'
$jarTool = Join-Path $JavaHome 'bin\jar.exe'

$keyMapFile = Join-Path $root 'src\main\java\cn\splitplay\input\KeyMap.java'
$commandLineFile = Join-Path $root 'src\main\java\cn\splitplay\launch\CommandLine.java'

function Compile {
    param(
        [string[]]$SourceRoots = @(),
        [string[]]$Files = @(),
        [string]$Dest,
        [string]$ArgTag,
        [string[]]$ClassPath
    )
    if (Test-Path $Dest) { Remove-Item -Recurse -Force $Dest }
    New-Item -ItemType Directory -Force -Path $Dest | Out-Null
    $sources = @($Files)
    foreach ($r in $SourceRoots) {
        if (-not (Test-Path $r)) { continue }
        $sources += @(Get-ChildItem -Path $r -Recurse -File -Filter *.java | ForEach-Object { $_.FullName })
    }
    if ($sources.Count -eq 0) { throw 'no java sources' }

    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $argFile = Join-Path $OutDir ("javac-$ArgTag.txt")
    $lines = @()
    $lines += '-encoding', 'UTF-8'
    $lines += '-proc:none', '-nowarn', '-Xlint:none'
    $lines += '-d', ($Dest.Replace('\', '/'))
    $lines += '-classpath', ('"' + (($ClassPath -join ';').Replace('\', '/')) + '"')
    foreach ($f in $sources) { $lines += ('"' + $f.Replace('\', '/') + '"') }
    # ASCII without BOM: javac treats a leading BOM in an argfile as a bad token
    [System.IO.File]::WriteAllLines($argFile, $lines, (New-Object System.Text.ASCIIEncoding))
    & $javac "@$argFile"
    if ($LASTEXITCODE -ne 0) { throw "javac failed for $ArgTag (exit $LASTEXITCODE)" }
    Write-Host ("compiled {0} classes -> {1}" -f @(Get-ChildItem -Path $Dest -Recurse -File -Filter *.class).Count, $Dest)
}

if ($KeyMapCheck) {
    $dest = Join-Path $OutDir 'keymap-classes'
    Compile -Files @($keyMapFile, (Join-Path $root 'probe\src\cn\splitplay\probe\KeyMapCheck.java')) `
        -Dest $dest -ArgTag 'keymap' -ClassPath @($glfw[0])
    & $java -cp "$dest;$($glfw[0])" cn.splitplay.probe.KeyMapCheck
    if ($LASTEXITCODE -ne 0) { throw 'key map check failed' }
    exit 0
}

if ($CommandLineCheck) {
    $dest = Join-Path $OutDir 'cmdline-classes'
    Compile -Files @($commandLineFile, (Join-Path $root 'probe\src\cn\splitplay\probe\CommandLineCheck.java')) `
        -Dest $dest -ArgTag 'cmdline' -ClassPath @()
    & $java -cp $dest cn.splitplay.probe.CommandLineCheck
    if ($LASTEXITCODE -ne 0) { throw 'command line check failed' }
    exit 0
}

if ($LogicCheck) {
    $dest = Join-Path $OutDir 'logic-classes'
    Compile -Files @(
        (Join-Path $root 'src\main\java\cn\splitplay\cursor\CursorGeometry.java'),
        (Join-Path $root 'src\main\java\cn\splitplay\cursor\PointerPolicy.java'),
        (Join-Path $root 'src\main\java\cn\splitplay\launch\MainWindowPicker.java'),
        (Join-Path $root 'src\main\java\cn\splitplay\launch\SplitLayout.java'),
        (Join-Path $root 'src\main\java\cn\splitplay\util\DevicePath.java'),
        (Join-Path $root 'probe\src\cn\splitplay\probe\LogicCheck.java')
    ) -Dest $dest -ArgTag 'logic' -ClassPath @()
    & $java -cp $dest cn.splitplay.probe.LogicCheck
    if ($LASTEXITCODE -ne 0) { throw 'logic check failed' }
    exit 0
}

if ($HeldCheck) {
    $dest = Join-Path $OutDir 'held-classes'
    Compile -Files @(
        (Join-Path $root 'src\main\java\cn\splitplay\input\HeldInput.java'),
        (Join-Path $root 'probe\src\cn\splitplay\probe\HeldInputCheck.java')
    ) -Dest $dest -ArgTag 'held' -ClassPath @($glfw[0])
    & $java -cp "$dest;$($glfw[0])" cn.splitplay.probe.HeldInputCheck
    if ($LASTEXITCODE -ne 0) { throw 'held input check failed' }
    exit 0
}

if ($ScreenCheck) {
    $dest = Join-Path $OutDir 'screen-classes'
    Compile -Files @(
        (Join-Path $root 'src\main\java\cn\splitplay\client\DeviceScreenLayout.java'),
        (Join-Path $root 'probe\src\cn\splitplay\probe\DeviceScreenCheck.java')
    ) -Dest $dest -ArgTag 'screen' -ClassPath @()
    & $java -cp $dest cn.splitplay.probe.DeviceScreenCheck
    if ($LASTEXITCODE -ne 0) { throw 'device screen check failed' }
    exit 0
}

if ($QueueCheck) {
    $dest = Join-Path $OutDir 'queue-classes'
    Compile -Files @(
        (Join-Path $root 'src\main\java\cn\splitplay\rawin\RawInputEvent.java'),
        (Join-Path $root 'src\main\java\cn\splitplay\rawin\RawInputSupport.java'),
        (Join-Path $root 'src\main\java\cn\splitplay\input\EventQueue.java'),
        (Join-Path $root 'probe\src\cn\splitplay\probe\EventQueueCheck.java')
    ) -Dest $dest -ArgTag 'queue' -ClassPath @()
    & $java -cp $dest cn.splitplay.probe.EventQueueCheck
    if ($LASTEXITCODE -ne 0) { throw 'event queue check failed' }
    exit 0
}
if ($ClaimCheck) {
    if ($gson.Count -eq 0) { throw 'gson jar not found under <McDir>\libraries; pass -McDir' }
    $dest = Join-Path $OutDir 'claim-classes'
    Compile -Files @(
        (Join-Path $root 'src\main\java\cn\splitplay\util\DevicePath.java'),
        (Join-Path $root 'src\main\java\cn\splitplay\util\ClaimStore.java'),
        (Join-Path $root 'probe\src\cn\splitplay\probe\ClaimCheck.java')
    ) -Dest $dest -ArgTag 'claims' -ClassPath @($gson[0])
    & $java -cp "$dest;$($gson[0])" cn.splitplay.probe.ClaimCheck
    if ($LASTEXITCODE -ne 0) { throw 'claim check failed' }
    exit 0
}

if ($ConfigCheck) {
    if ($gson.Count -eq 0) { throw 'gson jar not found under <McDir>\libraries; pass -McDir' }
    $slf4j = @(Find-Jars (Join-Path $McDir 'libraries\org\slf4j\slf4j-api') 'slf4j-api-2*.jar' |
        Where-Object { $_ -notmatch 'sources' })
    if ($slf4j.Count -eq 0) { throw 'slf4j-api jar not found under <McDir>\libraries; pass -McDir' }
    $dest = Join-Path $OutDir 'config-classes'
    Compile -Files @(
        (Join-Path $root 'src\main\java\cn\splitplay\util\DevicePath.java'),
        (Join-Path $root 'src\main\java\cn\splitplay\config\SplitConfig.java'),
        (Join-Path $root 'probe\src\cn\splitplay\probe\ConfigCheck.java')
    ) -Dest $dest -ArgTag 'config' -ClassPath @($gson[0], $slf4j[0])
    # slf4j has no provider on this class path and prints a warning to stderr; the check judges
    # nothing by stderr, and PowerShell would turn that warning into a red error record.
    & $java -cp "$dest;$($gson[0]);$($slf4j[0])" cn.splitplay.probe.ConfigCheck 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'config check failed' }
    exit 0
}

if ($MirrorCheck) {
    $dest = Join-Path $OutDir 'mirror-classes'
    Compile -Files @(
        (Join-Path $root 'src\main\java\cn\splitplay\launch\ModMirror.java'),
        (Join-Path $root 'probe\src\cn\splitplay\probe\ModMirrorCheck.java')
    ) -Dest $dest -ArgTag 'mirror' -ClassPath @()
    & $java -cp $dest cn.splitplay.probe.ModMirrorCheck
    if ($LASTEXITCODE -ne 0) { throw 'mirror check failed' }
    exit 0
}

if ($JarCheck) {
    $target = $Jar
    if (-not $target) {
        $candidate = Get-ChildItem (Join-Path $root 'build\libs') -Filter 'splitplay-*.jar' -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notlike '*sources*' } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if (-not $candidate) { throw 'no built jar found; run .\gradlew.bat build first' }
        $target = $candidate.FullName
    }
    $dest = Join-Path $OutDir 'jarcheck-classes'
    Compile -Files @((Join-Path $root 'probe\src\cn\splitplay\probe\JarCheck.java')) `
        -Dest $dest -ArgTag 'jarcheck' -ClassPath @()
    & $java -cp $dest cn.splitplay.probe.JarCheck $target
    if ($LASTEXITCODE -ne 0) { throw 'jar check failed' }
    exit 0
}

$javap = Join-Path $JavaHome 'bin\javap.exe'

if ($MixinCheck) {
    # Every mixin names a target class and the members it injects into / exposes. Those names are
    # written in Mojang mappings, and Minecraft's own jar under <McDir>\libraries is in those names, so
    # javap can tell us whether a member really exists in this version. A typo here is a startup crash for
    # the player, which is the worst possible way to find out.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $mixinDir = $MixinDir
    if (-not $mixinDir) { $mixinDir = Join-Path $root 'src\main\java\cn\splitplay\mixin' }
    if (-not (Test-Path $mixinDir)) { throw "mixin source directory not found: $mixinDir" }

    $srg = @(Get-ChildItem (Join-Path $McDir 'libraries\net\minecraft\client') -Recurse -File -Filter '*-srg.jar' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending)
    if ($srg.Count -eq 0) { throw 'no *-srg.jar under <McDir>\libraries\net\minecraft\client; pass -McDir' }
    $srgJar = $srg[0].FullName

    $mixinSources = @(Get-ChildItem -Path $mixinDir -File -Filter *.java)
    if ($mixinSources.Count -eq 0) { throw "no mixin sources in $mixinDir" }

    # Simple name -> fully qualified names, taken from the game jar so no package table can go stale.
    $zip = [System.IO.Compression.ZipFile]::OpenRead($srgJar)
    try {
        $classNames = @($zip.Entries | Where-Object { $_.FullName.EndsWith('.class') } | ForEach-Object {
            $_.FullName.Substring(0, $_.FullName.Length - 6).Replace('/', '.')
        })
    } finally {
        $zip.Dispose()
    }

    $targets = @()
    foreach ($file in $mixinSources) {
        $text = Get-Content -Raw -LiteralPath $file.FullName
        $m = [regex]::Match($text, '@Mixin\(\s*([\w.$]+)\.class')
        if (-not $m.Success) { continue }
        $target = $m.Groups[1].Value
        $members = @()
        foreach ($x in [regex]::Matches($text, '@(?:Inject|Redirect|ModifyArg|ModifyVariable|WrapOperation)\(\s*method\s*=\s*"([^"]+)"')) {
            $members += [pscustomobject]@{ Name = $x.Groups[1].Value; Kind = 'method' }
        }
        foreach ($x in [regex]::Matches($text, '@Invoker\("([^"]+)"\)')) {
            $members += [pscustomobject]@{ Name = $x.Groups[1].Value; Kind = 'method' }
        }
        foreach ($x in [regex]::Matches($text, '@Accessor\("([^"]+)"\)')) {
            $members += [pscustomobject]@{ Name = $x.Groups[1].Value; Kind = 'either' }
        }
        $targets += [pscustomobject]@{
            Mixin  = $file.BaseName
            Target = $target
            Members = $members
        }
    }
    if ($targets.Count -eq 0) { throw "no @Mixin targets found in $mixinDir" }

    # A mixin that is not registered in the mixin config is silently dead, which is just as invisible as a
    # wrong target name, so check both directions.
    $configPath = Join-Path $root 'src\main\resources\splitplay.mixins.json'
    $registered = @()
    if (Test-Path $configPath) {
        $configText = Get-Content -Raw -LiteralPath $configPath
        $registered = @([regex]::Matches($configText, '"([A-Za-z0-9_$]+)"') | ForEach-Object { $_.Groups[1].Value })
    }

    $pass = 0
    $fail = 0
    foreach ($entry in $targets) {
        $full = @($classNames | Where-Object { $_ -eq $entry.Target -or $_.EndsWith('.' + $entry.Target) })
        if ($full.Count -eq 0) {
            $fail++
            Write-Host ("  MISSING class {0} (@Mixin target of {1})" -f $entry.Target, $entry.Mixin) -ForegroundColor Red
            continue
        }
        # Prefer an exact match, then the shortest name (the game's own package, not a nested helper).
        $fqcn = ($full | Sort-Object Length | Select-Object -First 1)
        $listing = & $javap -p -cp $srgJar $fqcn 2>$null
        if (-not $listing) {
            Write-Host ("  MISSING javap output for {0}" -f $fqcn) -ForegroundColor Red
            $fail++
            continue
        }
        $joined = ($listing -join "`n")
        foreach ($member in $entry.Members) {
            $asMethod = [regex]::IsMatch($joined, ('(?m)\b' + [regex]::Escape($member.Name) + '\s*\('))
            $asField = [regex]::IsMatch($joined, ('(?m)\b' + [regex]::Escape($member.Name) + '\s*;'))
            $ok = if ($member.Kind -eq 'either') { $asMethod -or $asField } else { $asMethod }
            if ($ok) {
                $pass++
                Write-Host ("  ok  {0}.{1}" -f $fqcn, $member.Name)
            } else {
                $fail++
                Write-Host ("  MISSING {0}.{1} (needed by {2})" -f $entry.Target, $member.Name, $entry.Mixin) -ForegroundColor Red
            }
        }
        if ($registered -notcontains $entry.Mixin) {
            $fail++
            Write-Host ("  UNREGISTERED {0} is not listed in splitplay.mixins.json" -f $entry.Mixin) -ForegroundColor Red
        } else {
            $pass++
        }
    }

    # The reference jar last time had readable Mojang names in it; if this jar does not, the check is
    # looking at the wrong file and its silence would be meaningless.
    if ($joined -notmatch 'net\.minecraft\.') {
        Write-Host '  WARNING: javap output has no net.minecraft types; is this the deobfuscated jar?' -ForegroundColor Yellow
    }

    Write-Host ("mixincheck: pass={0} fail={1} ({2} class(es), {3})" -f $pass, $fail, $targets.Count, (Split-Path -Leaf $srgJar))
    if ($fail -gt 0) { throw 'mixin check failed' }
    exit 0
}


$probeSrc = Join-Path $root 'probe\src'
$probeClasses = Join-Path $OutDir 'probe-classes'
Compile -SourceRoots @($rawinSrc, $probeSrc) -Files @($keyMapFile) -Dest $probeClasses -ArgTag 'probe' `
    -ClassPath (@($jnaCp) + @($glfw[0]))
$probeJar = Join-Path $OutDir 'splitplay-probe.jar'
if (Test-Path $probeJar) { Remove-Item -Force $probeJar }
Push-Location $probeClasses
try {
    & $jarTool --create --file $probeJar -C . .
    if ($LASTEXITCODE -ne 0) { throw "jar failed (exit $LASTEXITCODE)" }
} finally {
    Pop-Location
}
Write-Host ("packed {0} ({1:N0} bytes)" -f $probeJar, (Get-Item $probeJar).Length)
Write-Host 'done.'
