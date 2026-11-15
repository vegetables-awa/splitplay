# Changelog

All notable changes to this project. The version in `gradle.properties` and `fabric.mod.json` must match
the newest entry here.

## Unreleased

### One jar for 1.21.1 up to 1.21.5

- The mod no longer depends on `Screen.wrapScreenError`, which was removed in 1.21.2. The screen calls
  are now wrapped by a small helper of our own, so the same jar runs on 1.21.1, 1.21.2, 1.21.3, 1.21.4
  and 1.21.5. `fabric.mod.json` asks for `>=1.21.1 <1.21.6`.
- Verified by launching 1.21.1 (Fabric 0.16.9) and 1.21.5 (Fabric 0.20.1) with the same file: the mixin
  targets resolve on both, raw input attaches to the game window, the title screen gets its buttons and
  the virtual cursor draws. The release jar is built against 1.21.1, the oldest supported version, so
  every member it references exists on the newer ones too.
- 1.21.6 and later move the GUI rendering and the input classes around (`GuiGraphics.pose()` returns a
  different type, `MouseHandler.onPress` disappears, key events become objects). They need a separate
  port, not just a version range.

### The second window is the same install now

- The second window runs in the **same instance directory** with the **same working directory** as the
  first one, so mod packs keep their configs, resource packs and custom menus in both windows. Only the
  settings file is separate (`config/splitplay-p2.json`), and the price is that both processes write
  `logs/latest.log`.
- Earlier builds gave the child its own directory to keep the logs apart. Mods resolve paths against the
  working directory rather than `--gameDir` (FancyMenu reads `./config/fancymenu/customization`,
  Cobblemon writes `./showdown`), so the child came up with an empty config and a vanilla title screen.
- The child command line is read with `CommandLineToArgvW` instead of splitting `sun.java.command` on
  spaces. A pack called `Cobblemon Official Modpack [Fabric]` used to arrive as five arguments, be
  ignored, and start a bare Fabric client with four mods.

### Usable in packs that replace the title screen

- `F7` starts two-player mode; `Shift`+`F7` and `F8` open the device picker. FancyMenu never renders
  the widgets we add to `TitleScreen`, and now the buttons are re-added whenever the vanilla widget
  list is drawn, but the keys are the reliable path.
- The game window hook is retried from the client tick, and the raw input service falls back to a
  message-only sink while the window does not exist yet, so the mod also attaches on large packs that
  open a splash screen first.

### Interface

- The whole interface is translated: `assets/splitplay/lang/{en_us,zh_cn}.json`, no hard-coded Chinese.
- Two sliders on the device screen: look sensitivity and cursor speed. Stored separately
  (`sensitivity`, `cursorSensitivity`) and clamped on load, so a hand-edited file cannot make the
  camera unusable.

### Pointer

- The pointer belongs to the window that is in front: clipped to its client area, hidden, and held at
  the centre; re-asserted every frame and released by a 200 ms watchdog the moment another application
  gets the foreground.
- `GLFW_CURSOR_DISABLED` is never used, and vanilla's `MouseHandler.grabMouse` is cancelled. That mode
  takes the Win32 mouse capture, and Windows stops delivering mouse `WM_INPUT` to a window that holds
  it -- which is why opening the inventory killed the mouse in both windows.
- Which window may touch the pointer is decided by the OS foreground window's **process id**, not by
  GLFW's cached focus flag (two windows share one process).
- The clip is no longer combined with re-centring: this window never reads the system pointer position,
  so pinning it every frame only produced a storm of synthetic mouse-move messages.

### Input

- Raw input is read from the game window's own window procedure instead of a separate message-only
  window; the separate sink stopped receiving mouse `WM_INPUT` as soon as the game grabbed the cursor,
  which is why the world stood still while keys still worked.
- Menus get their input from the virtual cursor: movement, clicks, drags and the wheel are handed to
  the screen directly, with the press bookkeeping screens rely on filled in, so sliders and item
  dragging work while the system pointer stays captured.
- The camera is turned straight from the raw delta instead of being replayed through vanilla's
  accumulated movement, which could cancel itself out against replayed positions. Vanilla's own curve
  is applied: `((sensitivity * 0.6 + 0.2)^3) * 8`, `invertYMouse` respected.
- Movement is consumed once per rendered frame instead of once per tick, and the cursor slows down
  while hovering an item slot.
- Focus loss pauses replay for half a second before freezing, so alt-tabbing between the two windows no
  longer stalls the camera.
- Keys replayed through the mod also answer `InputConstants.isKeyDown`, so the `F3` debug combos work.
- Held keys are tracked in one place (`HeldInput`), so a repeated press cannot fire twice, a released
  key cannot stay stuck, and modifiers are correct including the shift that is being pressed.
- Replayed characters are produced on `GLFW_REPEAT` too, so holding a key in chat repeats like vanilla.

### Documentation

- Rewritten `README.md` with a documentation index, plus `README.zh-CN.md`.
- New documents: `docs/ARCHITECTURE.md`, `docs/DEVELOPING.md`, `docs/TROUBLESHOOTING.md`,
  `docs/COMPATIBILITY.md`; the release zip ships them.

## 0.1.0 - first release

Everything below is in one jar: no helper executable, no launcher script, no IPC port.

### Two players on one PC

- `Two players` starts a second game window from the running one, so command line, class path and JVM
  arguments come from the instance that already works.
- Both windows become borderless and are stacked top/bottom (`"layout": "horizontal"` for side by
  side), the taskbar is hidden, and the first window keeps the focus. Placement is re-checked for 25
  seconds because the game resizes itself once the resource reload finishes; a pass only moves a window
  whose rectangle drifted, and never activates it.
- If a window handle goes stale (a pack recreating its window), the windows are re-discovered by pid.
- A hidden taskbar repairs itself: `.splitplay-split` is written before hiding, and every start
  restores the taskbar if it is not visible.

### Per-device input

- Raw input through JNA (`RegisterRawInputDevices` with `RIDEV_INPUTSINK`), so the whole keyboard and
  mouse stream of the machine is visible, including input the OS sends to another window.
- `Devices` lists every keyboard and mouse with vendor and product id; a device the other window
  claimed is greyed out. Claims are small files under `splitplay-devices/` keyed by process id, pruned
  when the process is gone.
- The list is laid out from the window size instead of fixed offsets: a split window at GUI scale 2 is
  only seven rows tall, so extra devices are reported as hidden instead of being drawn on top of Save
  and Cancel.
- Assigned devices are replayed through vanilla's own `KeyboardHandler` / `MouseHandler`; everything
  else is dropped. Windows virtual key codes are translated to GLFW key codes, characters are
  synthesised per device for chat and signs.
- Releases that never arrive (device unplugged mid-session, isolation switched off while a key is held)
  are replayed on purpose, so nobody keeps walking or mining on their own.
- If an assigned device is missing, isolation stays off and the window behaves like vanilla with a
  warning in the log; routing resumes when the device is back. Paths are compared exactly, because a
  re-plugged device is indistinguishable from an identical second device.

### Pointer

- Each window draws its own arrow at `z=800`, above widgets, item icons and tooltips, from
  `Screen.render` / `AbstractContainerScreen.render` (a `GuiGraphics` created at the end of
  `GameRenderer.render` never reaches the screen). The arrow only appears after a device pair is
  assigned.
- The unfocused window keeps `mouseGrabbed` set, so attack and use keep working while the other player
  owns the system pointer.
- Hiding the pointer is not enough: Windows still moves the invisible pointer with the physical mouse,
  so moving the mouse in the game dragged the real pointer across the desktop. The focused window clips
  the pointer to its own client area and pulls it back to the centre.
- Assigned keys, buttons and the wheel go to the window that was actually assigned the device, not to
  the OS pointer.

### Checks

`./release.ps1` builds the jar and runs eleven offline suites (498 assertions):
`-LogicCheck` (pointer geometry, screen split, window discovery, device path matching),
`-KeyMapCheck` (virtual key to GLFW key), `-CommandLineCheck` (child command line rewriting and offline
uuid), `-ClaimCheck` (device claim store), `-ConfigCheck` (config defaults, clamping, round trip,
broken files), `-MirrorCheck` (mod jar mirroring), `-JarCheck` (package contents, no readable Mojang
names, no leftover upstream classes), `-MixinCheck` (every mixin target exists in this Minecraft version
and every mixin is registered), `-HeldCheck` (press/repeat/release classification, modifier bits,
release snapshots), `-ScreenCheck` (device picker geometry, overflow counting, hit testing, layout
invariants across 60 window sizes), `-QueueCheck` (move merging, overflow discarding, capacity clamping,
producer/consumer safety).

### Known limits

- Requires two keyboards and two mice; a touchpad or a virtual pointer cannot stand in for one.
- Joining the same world is the vanilla LAN flow: open to LAN in one window, connect to
  `127.0.0.1:<port>` in the other.
- An input method (IME) cannot be split per device, so only one window can type Chinese.
- Windows 10/11 only, Minecraft 1.21.1 + Fabric only.
