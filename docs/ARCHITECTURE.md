# Architecture

How splitplay is put together, and why each part is where it is.

## Sound

Both windows share `options.txt`, so the vanilla "sound device" option cannot differ between them.
`LibraryMixin` therefore swaps the device name handed to `Library.init` with the window's own
`soundDevice` setting, which is what makes two headsets possible. The names come from
`ALC_ENUMERATE_ALL_EXT` (`ALUtil.getStringList(0, 0x1013)`), the same list the vanilla sound screen
shows; a stale name falls back to the default device inside Minecraft. Microphones are not handled:
the game has no voice chat, and Windows has no per-application input routing.

The swap happens while `Library.init` runs, so a change takes effect on the next resource reload
(`F3 + T`) or on the next start of that window -- nothing else has to be restarted.

## Layers

```
Windows Raw Input  (WM_INPUT from the device, per device path)
        |
        v
rawin/    RawInputService   message loop on the game window (GateWindowHook) or a sink window
        |  RawInputDevice / RawInputEvent / RawInputListener
        v
SplitPlayClient     decides what belongs to this window (config.match*) and feeds InputRouter
        |
        v
input/    EventQueue        bounded queue, merges movement, drops on overflow
        |  Isolation, HeldInput, KeyMap, KeyChars, ScreenMouse
        v
input/    InputRouter       per tick / per frame: replay to vanilla, or turn the camera
        |
        v
vanilla   KeyboardHandler.keyPress / charTyped
          MouseHandler.onMove / onPress / onScroll
          Screen.mouseMoved / mouseClicked / mouseReleased / mouseDragged / mouseScrolled

cursor/   VirtualCursor     pointer policy, clip, hide, drawn arrow (z = 800)
client/   DeviceScreen, DeviceScreenLayout, TitleButtons, KeyShortcuts, DeviceClaims,
          SoundDevices                                               
config/   SplitConfig       config/splitplay.json (P1) and config/splitplay-p2.json (P2)
launch/   SecondInstance, SplitLauncher, CommandLine, NativeCommandLine, NativeWindows,
          SplitLayout, MainWindowPicker, ModMirror
util/     DevicePath, ClaimStore, SoundDeviceNames
mixin/    14 mixins (see the table at the end)
```

## Why raw input at all

GLFW reports "the mouse moved" and "a key went down" -- it does not say which device. Everything that
happens below GLFW (focus, capture, cursor position) is per window, not per device. `Raw Input` is the
only Windows API that answers "which keyboard was that", and `RIDEV_INPUTSINK` makes it deliver those
events regardless of which window has the focus, which is exactly what two clients on one desktop need.

Consequences that shape the rest of the design:

- The native GLFW path has to be **cancelled**, otherwise every device would drive both windows.
- Because the game no longer receives GLFW input, everything has to be **replayed** through the same
  vanilla methods, in the right order, at the right time in the frame.
- Because the game no longer sees real key state, `InputConstants.isKeyDown` has to be lied to for
  composition keys such as `F3`.
- Because Windows has one pointer, each window draws **its own arrow**.

## The message source

Two modes, one of them is a fallback:

1. **Hooked window procedure** (`rawin/GateWindowHook`): `SetWindowLongPtr(hwnd, GWLP_WNDPROC, ...)`
   on the GLFW window, so `WM_INPUT` arrives in the game's own message loop. `CallWindowProc`
   forwards everything else. This is the mode the game normally ends up in.
2. **Message-only sink window** (`RawInputService.start`): used when the game window does not exist
   yet or the hook fails. It runs its own message loop on a thread.

The hook is not optional decoration: with a separate sink window, Windows stops posting mouse
`WM_INPUT` to it as soon as the game window takes the mouse capture (which the vanilla
`MouseHandler.grabMouse` does when a world loads). The symptom is "the camera works in the menu and
freezes in the world". `SplitPlayClient.ensureWindowAttached()` retries the hook from the client tick
so a window that appears late (or a pack that opens a splash screen first) still gets attached.

## Order of events inside a frame

```
game thread tick
  Minecraft.tick                 -> InputRouter.tick        buttons, keys, wheel, focus gate
  MouseHandler.handleAccumulatedMovement (HEAD)
                                 -> InputRouter.flushPendingMove   menu cursor hand-off
  GameRenderer.render (HEAD)     -> InputRouter.pumpFrame   movement, once per frame
  Screen.render / AbstractContainerScreen.render (TAIL)
                                 -> VirtualCursor.draw      arrow above items (z = 800)
```

Movement is consumed **once per frame** and cameras are turned **immediately** when the event is
drained, which is what Mouse&mouse does (`WorldMouseService.handleRelativeLook` on every packet). An
earlier version only flushed at the end of the tick, which cost one tick of latency (about 50 ms) and
felt laggy.

## Replay, not simulation

Replayed input goes through the vanilla entry points:

| Event | Vanilla method used |
|---|---|
| key press / release | `KeyboardHandler.keyPress(window, glfwKey, scancode, action, modifiers)` |
| typed character | `KeyboardHandler.charTyped(window, codePoint, modifiers)` |
| camera | `Entity.turn(dx * factor, dy * factor)` plus `Minecraft.getTutorial().onMouse(...)` |
| menu cursor | `Screen.mouseMoved` / `mouseDragged` / `afterMouseMove` |
| menu click | `MouseHandler.onPress` **and** `Screen.mouseClicked` / `mouseReleased` / `afterMouseAction` |
| wheel | `MouseHandler.onScroll` and `Screen.mouseScrolled` |

splitplay never calls `SendInput` or `mouse_event`: synthesised system input would land on whatever
window is in front and would look like an input injection to anti-cheat or accessibility software.

`Isolation.replaying` is a re-entrancy flag: the mixins cancel native events only when it is clear, so
our own calls are not cancelled.

VK to GLFW translation lives in `input/KeyMap` (`VK 0x72` is `F3`; GLFW calls it 292). Characters are
produced from the VK in `input/KeyChars`, US layout only -- the operating system's IME is not per
device, so an IME can only serve the focused window.

## Pointer policy

Windows gives every desktop one pointer, so a window can either own it or ignore it:

| State | Clip to window | Hidden | `mouseGrabbed` |
|---|---|---|---|
| isolation off | no | no | vanilla |
| window is not in front | no | no | yes while in a world |
| in front, menu open | yes | yes | no |
| in front, in a world | yes | yes | yes |

`cursor/PointerPolicy.decide` is a pure function of (active, focused, inMenu) and is unit tested.

`GLFW_CURSOR_DISABLED` is **never** used (see `docs/TROUBLESHOOTING.md`): it makes GLFW capture the
mouse, and the captured window stops receiving mouse `WM_INPUT` -- the input source dies. Instead the
pointer is clipped to the client area and pushed back to the client centre every frame, which keeps it
out of the way without touching the capture state. `MouseHandler.grabMouse` is cancelled for the same
reason; only the logical "grabbed" flag is kept because continuous attack/use reads it.

Since the clip is a process-wide Win32 state, it is re-asserted every frame and released when the
window loses the foreground (`NativeWindows.ownsForeground`) and by a 200 ms watchdog thread, which
also works when rendering has stopped.

## The arrow

`cursor/VirtualCursor` draws a 16-row arrow from `CursorGeometry` (8 neighbouring rectangles for the
outline, then the fill), at `z = 800`. Vanilla draws items at `z = 100..200` and tooltips at `z = 400`
inside an ortho projection whose near plane is 1000 and whose model view is shifted by -11000, so a
larger `z` is closer to the camera. Without the offset the arrow is drawn but hidden behind item
icons -- that was a real bug report.

The cursor is only drawn once devices are assigned (`Isolation.isActive()`), so a fresh install looks
exactly like vanilla.

## The second window

`launch/SecondInstance`:

1. reads **this** process' command line with `CommandLineToArgvW` (`rawin`-era fallback: `sun.java.command`,
   which loses quoting -- a pack called `Cobblemon Official Modpack [Fabric]` arrives as five arguments
   and the game silently ignores them, starting a bare Fabric client);
2. rewrites only `--username`, `--uuid`, `--accessToken`, `--clientId`, `--xuid`;
3. starts `java` with the same arguments, the **same working directory** and `SPLITPLAY_CHILD=1`;
4. redirects output to `logs/splitplay-p2-stdout.txt` and `-stderr.txt` (never to a pipe: an unread
   pipe fills up and the child freezes inside `FileOutputStream.writeBytes`).

The working directory must stay the game directory: mods resolve paths against it rather than against
`--gameDir` (FancyMenu reads `./config/fancymenu/customization`, Cobblemon writes `./showdown`). A
child started elsewhere comes up with an empty config and a vanilla title screen.

`launch/SplitLauncher` then waits for both windows (`MainWindowPicker` picks the largest visible titled
window per pid), hides the taskbar, and re-applies `SplitLayout` about once a second for 25 seconds --
Minecraft resizes its own window again after loading, and a single placement is not enough. Placement
uses `SWP_NOACTIVATE` and only moves windows that actually drifted, because stealing focus 25 times in
a row made the game unplayable.

If a handle goes stale (a pack recreating its window), the launcher re-discovers windows by pid.

## Claims: who uses which device

Two windows cannot talk to each other, so they agree through files:
`<game root>/splitplay-devices/<pid>.json`, written by the window that owns devices and read by the
other one, which then greys those rows out in the device picker. Dead pids are pruned on read, so a
crash does not block a device forever.

Device paths are compared after `DevicePath.normalise` (strip the `\\?\` prefix, lower case) and then **exactly**,
or by a suffix of at least 8 characters. Fuzzy matching was tried and removed: after a replug the path
differs only in its instance segment, which looks exactly like "the same model of keyboard plugged in
twice", and guessing there hands one player the other's keyboard.

## Modules and responsibilities

| Path | Responsibility |
|---|---|
| `rawin/RawInputApi` | JNA declarations for `RegisterRawInputDevices`, `GetRawInputData`, `GetRawInputDeviceInfoW` |
| `rawin/RawInputService` | device registration, message loop, `WM_INPUT` parsing, device-change events |
| `rawin/GateWindowHook` | window procedure hook so `WM_INPUT` arrives in the game's own loop |
| `input/InputRouter` | per tick and per frame dispatch, focus gate, 5 s diagnostic line |
| `input/EventQueue` | bounded queue, movement coalescing, overflow policy |
| `input/HeldInput` | press/repeat/release bookkeeping, modifiers, release-all |
| `input/Isolation` | "is this window taking over input", re-entrancy flag, blocked counter |
| `input/ScreenMouse` | menu cursor movement, clicks, drags, wheel, hover slowdown |
| `input/KeyMap`, `KeyChars` | VK -> GLFW key, VK -> character |
| `cursor/VirtualCursor`, `PointerPolicy`, `CursorGeometry` | pointer policy, clip/hide, arrow |
| `client/DeviceScreen`, `DeviceScreenLayout` | device picker, its geometry, the two sliders and the sound picker |
| `client/DeviceClaims`, `util/ClaimStore` | cross-window device ownership through files |
| `client/TitleButtons`, `KeyShortcuts` | title screen buttons and `F7` / `Shift+F7` / `F8` |
| `client/SoundDevices`, `util/SoundDeviceNames` | output devices from `ALC_ENUMERATE_ALL_EXT` |
| `launch/*` | command line reuse, process start, window arrangement, taskbar, window discovery |
| `config/SplitConfig` | settings file, defaults, clamping |
| `util/DevicePath` | device path normalisation and matching |

## Mixins

| Mixin | Target | Why |
|---|---|---|
| `KeyboardHandlerMixin` | `KeyboardHandler.keyPress`, `charTyped` | cancel native keyboard input while isolating |
| `MouseHandlerMixin` | `MouseHandler.onMove`, `onPress`, `onScroll`, `handleAccumulatedMovement`, `grabMouse` | cancel native mouse input, hand off queued movement, keep the pointer out of GLFW's capture |
| `InputConstantsMixin` | `InputConstants.isKeyDown` | composition keys (`F3` + ...) see our held keys |
| `MinecraftIsWindowActiveMixin` | `Minecraft.isWindowActive` | an unfocused window must keep playing |
| `MinecraftTickMixin` | `Minecraft.tick` | drive the router, retry the window hook |
| `ScreenCursorMixin` | `Screen.render` | draw the arrow in menus |
| `ContainerCursorMixin` | `AbstractContainerScreen.render` | draw the arrow above item icons |
| `GameRendererMixin` | `GameRenderer.render` | per-frame movement hand-off, pointer mode |
| `TitleScreenMixin` | `TitleScreen.init` | add the two buttons |
| `LibraryMixin` | `Library.init` | open the output device this window was given |
| `KeyboardHandlerAccessor`, `MouseHandlerAccessor`, `ScreenAccessor`, `ContainerScreenAccessor` | invokers and field accessors | call vanilla internals without reflection |

`build.ps1 -MixinCheck` verifies every target class and member against the game jar, and that every
mixin is listed in `splitplay.mixins.json` -- an unregistered mixin silently does nothing.
