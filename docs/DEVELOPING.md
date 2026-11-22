# Developing

Everything needed to build, test and release splitplay without starting Minecraft.

## Toolchain

- JDK 21 (tested with BellSoft 21.0.4 and Temurin 21)
- Fabric Loom 1.7.x, Gradle 8.8 (the wrapper is committed)
- Minecraft 1.21.1, Fabric Loader 0.16.9+ (`gradle.properties`)
- Windows PowerShell 5.1 for the scripts

Gradle needs network access the first time (Minecraft, mojmap mappings, loader). Everything after that
is offline.

## Build

```powershell
.\gradlew.bat build
```

Output: `build\libs\splitplay-0.1.0.jar` (remapped by `remapJar` -- the jar in `build\libs` is already
the one a user needs) and `-sources.jar`.

```powershell
.\build.ps1                 # standalone raw-input probe -> build\splitplay-probe.jar
.\build.ps1 -KeyMapCheck    # ... one of the 11 suites, see below
.\build.ps1 -Jar -path <jar>  # inspect some other jar
```

`build.ps1` is deliberately plain `javac` with an argument file: it compiles the pure-JVM parts
(`rawin/`, `input/HeldInput`, `input/KeyMap`, `launch/CommandLine`, `launch/MainWindowPicker`,
`launch/SplitLayout`, `cursor/CursorGeometry`, `cursor/PointerPolicy`, `config/`, `util/`, ...) and the
suite that is asked for, with no Gradle, no Minecraft and no window. That is what makes the checks fast
(1-3 s each) and usable from `release.ps1`.

## The checks

`release.ps1` runs every suite and refuses to package if any of them fails. Current totals: **525
assertions**.

| Suite | Assertions | What it proves |
|---|---|---|
| `-LogicCheck` | 91 | cursor geometry and clamping, window picking, pointer policy table, `SplitLayout` tiling (both window geometries, odd resolutions, degenerate sizes) |
| `-KeyMapCheck` | 33 | Windows VK -> GLFW key translation for every key a player uses (`Esc`, `F1..F12`, arrows, modifiers, numpad, punctuation) |
| `-CommandLineCheck` | 26 | argument rewriting, `--gameDir` sharing, offline UUID (fixed to `89a1a08d-...` for `SP1`), empty-quote tokenising |
| `-ClaimCheck` | 31 | device claim files: write/read/remove, dead pids, own pid, path normalisation, broken files |
| `-ConfigCheck` | 61 | defaults, clamping (`sensitivity`, `cursorSensitivity`, `layout`), `soundDevice` defaulting and trimming, round trip, every kind of broken json |
| `-MirrorCheck` | 30 | mod jar mirroring rules (copy, keep, prune, no subdirectories, no non-jars) |
| `-JarCheck` | 37 | the built jar: metadata, `LICENSE`, icon, language files and their key parity, no readable Mojang names, no leftovers of the old mod |
| `-MixinCheck` | 46 | every mixin target class and member exists in the game jar, and every mixin is registered |
| `-HeldCheck` | 44 | press/repeat/release bookkeeping, double clicks, modifier calculation, release-all |
| `-ScreenCheck` | 69 | device picker geometry and title buttons: rows, overflow, sliders, hit testing, 77 window sizes with invariants |
| `-QueueCheck` | 57 | event queue: movement coalescing, overflow policy, capacity clamping, two-thread stress test |

Some checks need jars from a real installation (gson, slf4j, Minecraft, LWJGL). Point them somewhere
else with:

```powershell
.\build.ps1 -JarCheck -McDir 'D:\games\.minecraft' -Jar 'build\libs\splitplay-0.1.0.jar'
```

`-JarCheck` is also a useful diagnostic for other mods: pointing it at a broken jar reports what it
finds (it was written after a release that shipped unremapped Mojang names and crashed at startup).

## Adding a check

1. put the logic in a class that does not import Minecraft or LWJGL (`HeldInput`, `KeyMap`,
   `CommandLine`, `PointerPolicy`, `DevicePath`, `EventQueue` are the existing examples);
2. add a `probe/src/cn/splitplay/probe/<Name>Check.java` with a `main` that prints
   `name: pass=N fail=M` and exits non-zero on `M > 0`;
3. add a `-<Name>Check` switch to `build.ps1` (compile list + classpath + run);
4. add the switch to the `$checks` array in `release.ps1`.

Exit code matters: `release.ps1` looks for `fail=0` in the last line and also honours the exit code.

## Releasing

```powershell
.\release.ps1
```

It builds, runs the 11 suites, copies the jar plus `LICENSE`, `README.md`, `README.zh-CN.md`,
`CHANGELOG.md`, `INSTALL.txt` and `docs\` into `dist\`, and writes `dist\splitplay-<version>.zip`
with the sha256 printed. `dist/` and `build/` are ignored by git: a release is a GitHub release
artifact, not a commit.

Bump the version in **two** places before releasing: `mod_version` in `gradle.properties` and
`version` in `src/main/resources/fabric.mod.json` (and add a `CHANGELOG.md` section).

## Conventions

These are not style preferences, they are things that cost hours once:

- **Scripts are ASCII only.** Windows PowerShell 5.1 reads a BOM-less script as ANSI, so a Chinese
  comment turns into mojibake and the parser dies on the next line. `build.ps1`, `release.ps1` and
  `probe-rawinput.bat` are ASCII for that reason.
- **Never read a UTF-8 text file with PowerShell and write it back.** `Get-Content -Raw` decodes as
  ANSI and the file is destroyed. Use an editor, or write with an explicit
  `UTF8Encoding(false)`/`write` tool. This already destroyed a 245 line guide once.
- **`javac` argument files are ASCII and use forward slashes.** A backslash is an escape character in
  an argument file (`D:\a\b` becomes `D:ab`), and a BOM makes `javac` fail with
  `invalid flag: ?-encoding`.
- **`jar --create` without `--manifest`.** Passing a manifest path makes `jar` exit with code 1;
  it picks up `META-INF/MANIFEST.MF` inside the staged directory anyway.
- **Do not redirect a child process' output to a pipe.** The child blocks in
  `FileOutputStream.writeBytes` when the buffer fills and never finishes starting. Write to a file.
- **Do not give the second window its own working directory** (see `docs/ARCHITECTURE.md`).
- **`Stop-Process java` kills every client, including the one a human is using.** Ask first.

## Debugging in a running game

The mod logs under the `splitplay` logger. Useful switches are just log lines -- there is no debug
flag yet: `InputRouter` prints a 5 s summary while isolating, `RawInputService` prints the first events
it sees, `VirtualCursor` prints every cursor mode change.

To read the mojmap names of the game (what the source uses) without a decompiler:

```powershell
javap -p -cp "<.minecraft>\libraries\net\minecraft\client\1.21.1-20240808.144430\client-1.21.1-20240808.144430-srg.jar" net.minecraft.client.MouseHandler
```

That jar holds the same classes the mod is compiled against, which is why `-MixinCheck` can verify
targets offline.
