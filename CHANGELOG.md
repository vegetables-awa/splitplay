# Changelog

## Unreleased
- The second window keeps the game directory as its working directory; starting it elsewhere
  left mods like FancyMenu with an empty config, so its title screen came up vanilla.

- The second window now runs in the same game directory as the first, so it is the same mod pack
  (same configs, resource packs, menu layout) instead of a copy of the mods; it keeps its settings in
  `config/splitplay-p2.json` and its log in `splitplay-p2-logs/`.
- Arguments are read with `CommandLineToArgvW`, so a version name containing spaces no longer splits
  into several arguments and leaves the second window without the pack's mods.

- `F7` starts two-player mode and `Shift`+`F7` / `F8` open the device picker, so the mod is usable in packs that
  replace the title screen (FancyMenu) and never draw the added buttons.
- The game window hook is retried from the client tick and the raw input service falls back to a
  message-only sink when the window does not exist yet, so the mod also attaches on large packs.

- The interface is translated: the title screen buttons and the whole device screen come from
  `assets/splitplay/lang`, with English and Simplified Chinese shipped.
- The device screen has two sliders: look sensitivity and cursor speed. They are stored separately
  (`sensitivity` for the camera, `cursorSensitivity` for the pointer) and hand-edited values are
  clamped on load.

- The pointer is locked to the window that is in front (clip + hide + hold at the client centre,
  re-asserted every frame), and released by a watchdog the moment another application is focused.
- Movement is applied once per rendered frame instead of once per tick, and the cursor slows down
  while hovering an item slot.
- Vanilla no longer grabs the cursor (`GLFW_CURSOR_DISABLED`) while input is split: that mode takes
  the Win32 mouse capture, and Windows stops delivering mouse `WM_INPUT` to a window that holds it,
  which is why opening the inventory killed the mouse in both windows.
- Raw input is now read from the game window's own window procedure instead of a separate
  message-only window. The separate sink stopped receiving mouse `WM_INPUT` the moment the game
  grabbed the cursor, which is why nothing moved in the world while keys still worked.
- **Menus now get their input from the virtual cursor, not from the shared system pointer.** Movement,
  clicks, drags and the wheel are handed to the screen directly, with the press bookkeeping screens
  rely on filled in, so sliders and item dragging work while the system pointer stays captured.
- The camera is turned straight from the raw delta instead of being replayed through vanilla's
  accumulated movement, which could cancel itself out against our replayed positions.
- Focus loss pauses replay for half a second before freezing, so alt-tabbing between the two windows
  no longer stalls the camera.

All notable changes to this project. The version number in `gradle.properties` and
`fabric.mod.json` must match the newest entry here.

## 0.1.0 - first release

Everything below is in this single jar: no helper executable, no launcher script, no IPC port.

### Two players on one PC

- `Two players` button on the title screen starts a second game window from the running one, so the
  command line, class path and JVM arguments come from the instance that is already working.
- The child gets its own game directory (`<instance>/splitplay-p2`) with a copied `options.txt`, a
  generated `config/splitplay.json` and its own logs, plus the parent's mod jars. The mirror keeps both
  windows on the same set: a jar is only skipped when name, size and timestamp agree, and a jar that is
  gone from the parent is deleted from the child.
- Both windows are made borderless and stacked top/bottom (`"layout": "horizontal"` for side by side),
  the taskbar is hidden, and the first window keeps the focus. The placement is re-checked for 25
  seconds because the game resizes itself once the resource reload finishes; a pass only touches a
  window whose rectangle drifted, and never activates it.
- A hidden taskbar repairs itself: a `.splitplay-split` marker is written before hiding, and every
  start restores the taskbar if it is not visible.

### Per-device input

- Raw input through JNA (`RegisterRawInputDevices` with `RIDEV_INPUTSINK`), so the whole keyboard and
  mouse stream of this machine is visible to the mod - including input that the OS sends to another
  window.
- `Devices` screen lists every keyboard and mouse with its vendor and product id; a device that the
  other window already claimed is greyed out and cannot be picked. The claim is a small file under
  `splitplay-devices/` keyed by process id, so it disappears with the process.
- The list is laid out from a window-size calculation instead of fixed offsets: a split window at GUI
  scale 2 is only seven rows tall, so extra devices are reported as hidden instead of being drawn on
  top of Save and Cancel (where the click would have gone to the button).
- Assigned devices are replayed through vanilla's own `KeyboardHandler` and `MouseHandler`; everything
  else is dropped. Windows virtual key codes are translated to GLFW key codes, and typed characters
  are synthesised per device for chat and sign editing.
- Replayed keys also answer `InputConstants.isKeyDown`, so the F3 debug combos (F3+H, F3+G, F3+B, ...)
  work in a split window even though the event never passed through GLFW.
- Releases that never arrive - device unplugged mid-session, isolation switched off while a key is
  held - are replayed on purpose, so nobody keeps walking, mining or shooting on their own.
- If an assigned device is missing, isolation stays off and the window keeps working as vanilla, with
  a warning in the log; routing resumes when the device is back. Device paths are compared exactly,
  because a re-plugged device is indistinguishable from an identical second device.

### Pointer

- Each window draws its own arrow cursor at `z=800`, above widgets, item icons and tooltips. It is
  drawn from `Screen.render` / `AbstractContainerScreen.render` because a `GuiGraphics` created at the
  end of `GameRenderer.render` never reaches the screen. The arrow only appears once a device pair is
  assigned - before that the window keeps vanilla behaviour and the real system pointer.
- The unfocused window keeps `mouseGrabbed` set so attack and use still work while the other player
  has the system pointer; the focused window hides the system pointer in menus and disables it in the
  world.
- Hiding the pointer is not enough: Windows still moves the invisible pointer with the physical mouse,
  so moving the mouse inside the game dragged the real pointer over the desktop and clicks landed on
  whatever was behind the window. The focused window now clips the pointer to its own client area
  (`ClipCursor`) and pulls it back to the centre when the physical mouse has moved it away.
- Which window may touch the pointer is decided by the OS: the foreground window's process id has to
  be the game's own (or its sibling window's), not GLFW's cached focus flag. A 5 Hz watchdog thread
  releases the clip whenever no game window is in front, so a stalled render loop can never leave the
  desktop with a pointer that cannot be moved.
- The clip is not combined with re-centring any more: this window never reads the system pointer
  position, so pinning it every frame only produced a storm of synthetic mouse-move messages.
- Camera turning no longer goes through `MouseHandler.onMove`: vanilla accumulates `x - xpos` in
  absolute window pixels, and replayed positions are not the real pointer's, so a stale `xpos` could
  make the deltas cancel out and leave the camera frozen while the menu cursor kept working. The
  delta is now applied on arrival with vanilla's own sensitivity curve
  (`((sensitivity * 0.6 + 0.2)^3) * 8`, `invertYMouse` respected), which also removes the one-tick
  delay that made turning feel laggy.

### Known limits

- Requires two keyboards and two mice; a touchpad or a second virtual pointer cannot stand in for one.
- Joining the same world is still the vanilla LAN flow: open to LAN in one window, connect to
  `127.0.0.1:<port>` in the other.
- Input method (IME) composition cannot be split per device, so only one window can type Chinese.
- Windows 10/11 only, Minecraft 1.21.1 + Fabric only.

### Checks

`.\release.ps1` builds the jar and runs eleven offline suites (444 assertions total):
`-LogicCheck` (pointer geometry, screen split, window discovery, device path matching), `-KeyMapCheck`
(virtual key to GLFW key), `-CommandLineCheck` (child command line rewriting and offline uuid),
`-ClaimCheck` (device claim store), `-ConfigCheck` (config defaults, clamping, round trip, broken
files), `-MirrorCheck` (the second window's mods directory), `-JarCheck` (package contents, no readable
Mojang names, no leftover upstream classes), `-MixinCheck` (every mixin target class and member exists
in this Minecraft version, and every mixin file is registered), `-HeldCheck` (press/repeat/release
classification, modifier bits, release snapshots), `-ScreenCheck` (device picker row and button
geometry, overflow counting, click hit testing, layout invariants across 60 window sizes),
`-QueueCheck` (move merging, no merging across a click, overflow discarding, capacity clamping,
producer/consumer safety).

