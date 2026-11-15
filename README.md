# splitplay

Two players, one PC, one Minecraft window each -- with **their own keyboard and mouse**.

splitplay turns a normal Minecraft install into a local two-player split-screen setup: press one key,
a second game window opens below the first one, the taskbar gets out of the way, and each window
listens to its own set of input devices. One window never reacts to the other player's mouse or
keyboard, and the mouse cursor of each window is drawn by the game itself.

The whole thing is **one jar**. No second executable, no launcher script, no companion application.

```
+--------------------------------------------------+
|  P1   keyboard VID_1C4F  +  mouse VID_10C4        |
|       own camera, own cursor, own chat box        |
+--------------------------------------------------+
|  P2   keyboard VID_2A7A  +  mouse VID_30FA        |
|       own camera, own cursor, own chat box        |
+--------------------------------------------------+
```

## Status

| Part | State |
|---|---|
| Raw input (JNA, no helper exe) | done, verified with a standalone probe |
| Per-device input isolation and replay | done |
| Virtual cursor | done, on-screen verified |
| Device picker, per-window settings | done |
| One click -> second window -> split screen -> hidden taskbar | done, verified on Windows 10 |
| Running inside a real mod pack | done (Cobblemon, 191 mods) |
| Automatic LAN hosting | not implemented (join `127.0.0.1:<port>` by hand) |
| Chinese input method in both windows | not implemented (system IME is not per-device) |
| Gamepads | not implemented |

## Requirements

- Windows 10 or 11, 64-bit (the input layer is `Raw Input` + `ClipCursor`)
- Minecraft **1.21.1** with **Fabric Loader 0.16+**, Java 21
- **Two keyboards and two mice**, as real, separate devices
- Enough memory for two clients: 8 GB is tight, 16 GB is comfortable

Two mice that share one USB receiver (a wireless combo) are two devices and work fine.

## Install

1. Put `splitplay-0.1.0.jar` into the `mods` folder of your instance -- only one instance, the second
   window is started by the mod itself. Do **not** prepare a second instance by hand.
2. Start the game, press **F7** on the title screen (or click **Two players**).
   The second window appears in the bottom half; the taskbar disappears.
3. In **each** window press **F8**, tick that player's keyboard and mouse, press **Save**.
   Devices already claimed by the other window are greyed out.
4. In one window open a world, choose **Open to LAN**, then join `127.0.0.1:<port>` from the other.

Both windows run in the **same game directory**, so a mod pack keeps its configs, its resource packs
and its custom menus in the second window too. Only the settings file differs:
`config/splitplay.json` for the first window, `config/splitplay-p2.json` for the second.

## Keys

| Key | Action |
|---|---|
| `F7` | start the two-player setup (second window, split screen, taskbar hidden) |
| `Shift` + `F7` | open the device picker |
| `F8` | open the device picker |
| `Save` / `Cancel` in the picker | write / discard the choice |

The buttons on the title screen do the same thing. Some packs (FancyMenu) replace the title screen and
never draw our buttons, which is why the keys exist.

## How it works

Minecraft reads input from GLFW, which only knows "some mouse moved". splitplay replaces that with
`Raw Input`, which reports **which** device moved, and then:

1. drops the native GLFW callbacks for keys, characters, mouse movement, buttons and scrolling;
2. replays the events of **this window's** devices through the same vanilla methods the game would
   have called (`KeyboardHandler.keyPress`, `MouseHandler.onMove`, ...) right before the game consumes
   them;
3. draws its own cursor arrow inside the window, because the operating system only has one pointer.

The details -- every hook, the pointer policy, why `GLFW_CURSOR_DISABLED` is never used, how the
second window is started -- are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Known limits

- **Windows only**, one machine, two physical keyboards and two mice.
- **The second window is a second Minecraft client**: it loads the whole mod pack again (about 40 to
  90 seconds for a large pack) and it needs its own memory.
- **LAN is manual**: no automatic world hosting yet.
- **Chinese input methods** work in one window at a time; letters, digits and punctuation work
  everywhere (the character path is per device, the IME is not).
- **Both windows write `logs/latest.log`** (they share the working directory on purpose, see
  [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)); the second process can truncate the first one's
  log. Use the console output or `config/` to debug instead of expecting two logs.
- **Moving a device to another USB port** changes its device path; the window falls back to vanilla
  input and logs a warning until you pick the device again (`F8`, one click).

## Compatibility

Verified with the **Cobblemon Official Modpack [Fabric]** (191 mods, including FancyMenu, Konkrete,
Melody, InvMove, Sodium, Iris, EMI, JEI, Xaero's maps) and with a bare 1.21.1 + Fabric instance.
Mods that rewrite the input pipeline themselves are the ones to watch. See
[docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) for what was tested and what is risky by design.

## Building

```powershell
.\gradlew.bat build                 # the mod (needs JDK 21; Loom downloads MC the first time)
.\release.ps1                       # build + 11 offline check suites + dist\splitplay-<version>.zip
```

`release.ps1` refuses to package if any check fails. The individual suites (key table, command line
rewriting, claim files, config file, jar contents, mixin targets, held keys, device picker geometry,
event queue) are documented in [docs/DEVELOPING.md](docs/DEVELOPING.md).

## Documentation

| File | Content |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | modules, input pipeline, pointer policy, window arrangement, mixins |
| [docs/DEVELOPING.md](docs/DEVELOPING.md) | toolchain, build, the 11 check suites, release process, code conventions |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | log line catalogue, symptom -> cause table, recovery steps |
| [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) | tested packs and mods, risky categories, hard limits |
| [INSTALL.txt](INSTALL.txt) | the five install steps, plain text, shipped in the zip |
| [CHANGELOG.md](CHANGELOG.md) | what changed and why |

## Licence

MIT, see [LICENSE](LICENSE).

The idea of splitting input between two windows comes from
[Mouse&mouse](https://github.com/kafei520-CN/Mouse-mouse) (GPL-3.0). splitplay is a rewrite: no code
was copied from it, which is why it can be MIT. Its approach to the camera and the cursor was studied
and reimplemented, and the differences are noted in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
