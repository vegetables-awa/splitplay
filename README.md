# splitplay

Local two-player split screen for Minecraft 1.21.1 (Fabric). One client, two windows, one keyboard
and mouse per player.

Press `F7` on the title screen. A second window opens in the bottom half of the screen and the taskbar
is hidden. Each window reacts only to its own keyboard and mouse, with its own camera and its own
cursor.

## Install

Put `splitplay-0.1.0.jar` into the `mods` folder of the instance. Nothing else.

## Features

- Split screen: `F7` opens the second window, both windows become borderless and stacked, the taskbar
  is hidden. Closing either window brings the taskbar back.
- Independent input: `F8` in each window to pick that player's keyboard and mouse. A device used by
  the other window is greyed out.
- Independent cursor: each window draws its own arrow, so the two players never fight over the one
  system pointer.
- Pointer lock: the window in front keeps the pointer inside itself. Switching to another program
  releases it immediately.
- Settings per window: `config/splitplay.json` for the first, `config/splitplay-p2.json` for the
  second, so the two players can use different devices and sensitivities.
- Sensitivity sliders for camera and cursor in the device screen.
- Interface in English and Chinese, following the game language.
- Works in mod packs: the second window runs in the same instance, so configs, resource packs and
  custom menus apply to both windows.
- Joining a world: open it to LAN in one window, connect to `127.0.0.1:<port>` from the other.

Requires Windows, two keyboards, two mice, Minecraft 1.21.1, Fabric Loader 0.16+ and Java 21.

## Build

```powershell
.\gradlew.bat build
.\release.ps1
```

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): modules, input pipeline, pointer policy, mixins
- [docs/DEVELOPING.md](docs/DEVELOPING.md): build, the eleven offline check suites, release process
- [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md): log lines, symptoms, recovery steps
- [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md): tested packs, risky mods, hard limits
- [CHANGELOG.md](CHANGELOG.md)

## Licence

MIT, see [LICENSE](LICENSE).
