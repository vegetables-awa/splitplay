# splitplay

Local two-player split screen for Minecraft 1.21.1 to 1.21.5 (Fabric). One client, two windows, independent
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
- Sound per window: the top right of the device screen picks an output device for this window, so the
  two players can use two headsets. "Default" keeps whatever the game is already using.

Supports Minecraft 1.21.1 to 1.21.5 with the same jar, requires Fabric Loader 0.16+.

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

## Disclaimer

- This project was written entirely by **deepseek-v4.1-Flash**. The author has not read the code line by
  line, so no guarantee is given about its correctness, stability or safety.
- The idea comes from [Mouse&mouse](https://github.com/kafei520-CN/Mouse-mouse) (GPL-3.0). This project
  is an independent rewrite from scratch: **no code was copied from it**.

## Licence

MIT, see [LICENSE](LICENSE).
