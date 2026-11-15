# splitplay

Local two-player split screen for Minecraft 1.21.1 (Fabric). One client, two windows, independent
input.

Press `F7` on the title screen: the second window opens in the bottom half of the screen and the taskbar
hides by itself. Each window only responds to its own keyboard and mouse, with its own camera and its
own cursor.

## Install

Put `splitplay-0.1.0.jar` into the `mods` folder of the instance.

## Features

- Starting: the title screen has two buttons in its bottom left corner, "Two players" (same as `F7`)
  and "Devices" (same as `F8`). Mod packs that customise the title screen, FancyMenu for instance, may
  cover the buttons; use the shortcuts there.
- Split screen: `F7` opens the second window, both windows become borderless and stacked, the taskbar
  hides. Close either window and the taskbar comes back.
- Independent input: press `F8` in each window to pick that player's keyboard and mouse. Devices taken
  by the other window are greyed out.
- Settings per window: `config/splitplay.json` for the first window, `config/splitplay-p2.json` for the
  second, so each player can use their own devices and their own sensitivity.
- The device screen has two sliders, for camera sensitivity and cursor speed.

Supports Minecraft 1.21.1, requires Fabric Loader 0.16+.

## Build

```powershell
.\gradlew.bat build
.\release.ps1
```

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): modules, input pipeline, pointer policy, mixin list
- [docs/DEVELOPING.md](docs/DEVELOPING.md): build, the eleven offline check suites, release process
- [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md): log lines, common symptoms, recovery steps
- [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md): tested mod packs, risky mods, hard limits
- [CHANGELOG.md](CHANGELOG.md)

## Licence

MIT, see [LICENSE](LICENSE).
