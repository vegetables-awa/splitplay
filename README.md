# splitplay

Local two-player split-screen Minecraft (one PC, two windows, two independent sets of
keyboard + mouse). **Fabric 1.21.1, Java 21, MIT.**

This repository is a ground-up rewrite: **one jar, no helper executable, no launcher script.**
Raw input is collected inside the game process through JNA, so nothing else has to run.

## Status

| part | state |
|---|---|
| `rawin/` - Win32 Raw Input layer (replaces the old `splitter.exe`) | done, verified in game with a real keyboard and mouse |
| `config/` - device assignment persistence | done, round-trip tested |
| Fabric mod skeleton (fabric.mod.json, client entrypoint, device log) | done, loads as `splitplay 0.1.0` |
| `inject/` - per-device input isolation and replay | done, verified in game |
| `cursor/` - virtual pointer drawn above GUI items | done, on-screen verified |
| `launch/` - one-click second instance, split windows, hide taskbar | done, verified on Windows 10 |
| `client/DeviceScreen` - pick this window's keyboard and mouse | done, verified in game |
| device claims - the other window's devices are greyed out | done, verified across two running windows |
| pointer lock - the pointer is held inside the window in front | done, verified on Windows 10 |
| frame-rate cursor and camera (not tick-rate) | done, verified in game |

## Layout

```
src/main/java/cn/splitplay/rawin/   raw input (JNA), no Minecraft dependency
src/main/java/cn/splitplay/config/  settings json
src/main/java/cn/splitplay/input/   routing: native filter + replay into vanilla handlers
src/main/java/cn/splitplay/cursor/  the per-window pointer
src/main/java/cn/splitplay/launch/  second process, window placement, taskbar
src/main/java/cn/splitplay/client/  the device assignment screen
src/main/java/cn/splitplay/mixin/   the hooks that make all of it possible
src/main/java/cn/splitplay/         mod entrypoint and client bootstrap
probe/src/cn/splitplay/probe/       standalone harness for the raw input layer
```

## How isolation works

Vanilla receives every keyboard and mouse event through two handlers. Both are intercepted:

| hook | purpose |
|---|---|
| `KeyboardHandler.keyPress` / `charTyped` | drop native keystrokes and typing |
| `MouseHandler.onMove` / `onPress` / `onScroll` | drop native movement, buttons, wheel |
| `Minecraft.tick` | replay this window's own devices on the client thread |
| `Minecraft.isWindowActive` | keep the unfocused window alive |
| `InputConstants.isKeyDown` | report replayed keys as physically down, so the F3 debug combos work |
| `MouseHandler.mouseGrabbed` (field) | let the unfocused window attack and use continuously |
| `Screen.render` / `AbstractContainerScreen.render` (tail) | draw the pointer above widgets, items and tooltips |
| `GameRenderer.render` (head) | hand queued movement to the cursor and the camera once per frame |
| `GameRenderer.render` (tail) | keep the system pointer mode in sync |
| `MouseHandler.grabMouse` | refuse `GLFW_CURSOR_DISABLED`, which would starve raw input |

Events for the assigned devices are collected through raw input and replayed by calling the very
same vanilla handlers (through mixin invokers), so GUI clicks, attack/use, hotbar, chat and camera
keep their normal behaviour. A re-entrancy flag stops the filter from swallowing its own replay.

Raw input reports **Windows** virtual key codes while vanilla expects **GLFW** key codes. Letters,
digits and space happen to share values; F1-F12, escape, tab, shift/control/alt, the arrow keys and
every punctuation key do not, so `input/KeyMap` translates them. Forgetting that table makes those
keys silently do nothing - `build.ps1 -KeyMapCheck` exists to catch exactly that regression.

A replayed key also has to look *physically* down, because vanilla asks GLFW for modifier state in
places a mixin cannot reach - the F3 debug combos are the visible case (`keyPress` reads
`isKeyDown(window, GLFW_KEY_F3)` before treating a key as a shortcut). `mixin/InputConstantsMixin`
answers that question from the router's held-key set, so F3+H, F3+G, F3+B and friends behave the same
in a split window.

Two situations can lose the "key released" event: a device is unplugged mid-session, or isolation is
switched off while something is held. Vanilla would keep walking, mining or shooting on its own, so
both cases ask the router to release every key and button it replayed, and the log says how many:
`splitplay: released 2 key(s) and 0 mouse button(s) that were still held`.

## Assigning devices

Press **Devices** on the title screen, click one keyboard and one mouse, then **Save**. The
assignment applies immediately: the log switches to `splitplay: input isolation on, replaying ...`
and from that moment the window only listens to those two devices, so the other player can use the
remaining ones.

Until both devices are assigned the window behaves exactly like vanilla: input is not filtered and the
drawn arrow is not shown either, because the real system pointer is still on screen and two pointers
at once are just confusing. The arrow appears with the assignment.

Each window keeps its own `versions/<instance>/config/splitplay.json`, because both windows run the
same jar and cannot tell each other apart. The file looks like this and can also be edited by hand
(`probe-rawinput.bat --list` prints the exact paths):

```json
{
  "role": "P1",
  "keyboard": "\\\\?\\HID#VID_1C4F&PID_0002&MI_00#7&...#{884b96c3-56ef-11d1-bc8c-00a0c91405dd}",
  "mouse": "\\\\?\\HID#VID_10C4&PID_0005#6&...#{378de44c-56ef-11d1-bc8c-00a0c91405dd}",
  "sensitivity": 1.0,
  "cursorSensitivity": 1.0,
  "enabled": true,
  "layout": "vertical"
}
```

`sensitivity` scales the camera (1.0 is exactly vanilla, 0.1 to 3.0) and `cursorSensitivity` scales
how fast the drawn pointer crosses a menu (0.25 to 3.0). They are separate on purpose: slowing the
camera down should not make the pointer crawl across an inventory. Both are also editable in the
*Devices* screen with two sliders, which is what most players will use; the json values are clamped
when the file is loaded, so a typo cannot make the pointer unusable.

`layout` decides how the two windows are placed: `"vertical"` stacks them (one above the other),
`"horizontal"` puts them side by side, which is the better choice on a monitor that is wider than
16:9. It is read when *Two players* is pressed.

Both devices are required before anything is filtered: with only one of them assigned the window
stays completely vanilla instead of becoming uncontrollable.

The same rule applies when an assigned device is not plugged in: isolation stays off and the window
keeps working with whatever is attached, with a warning in the log. That matters because moving a
USB device to another port changes the instance part of its path
(`#7&1288512d&0&0000` becomes `#7&3f9c1a04&0&0000`), so a saved assignment can stop matching. Device
paths are therefore compared **exactly** (after normalising case and the `\\?\` prefix); fuzzy
matching was tried and removed, because two *identical* keyboards differ in exactly the same way as
a re-plugged one, and guessing would hand one player the other player's keyboard. If a device was
moved, pick the devices again - that is one click per window.

Both windows show the **Devices** button (only the *Two players* button is hidden in the second
window). Devices that the other running window already took are disabled and labelled
`in use by P1`, so the two players cannot accidentally end up on the same keyboard. The claim lives
in `<.minecraft>/splitplay-devices/<pid>.json` and disappears when that window stops or clears its
assignment; a stale file from a crashed run is ignored and deleted on the next start.

The log then shows what happened:

```
splitplay: input isolation on, replaying VID_2A7A&PID_8A57 + VID_30FA&PID_0400
splitplay: dropped 37 native input event(s) in the last 5s
```

The second line only appears when the native filter actually caught something, which is the quick
way to tell whether isolation is doing its job: type on the *other* keyboard and watch that number
grow while the game ignores the keys.

If the assigned hardware disappears, the log says so and the window goes back to vanilla input:

```
splitplay: assigned keyboard is not plugged in, this window keeps vanilla input until it is back
splitplay: assigned devices are plugged in again, input routing resumes
```

## One click, two windows

The title screen gets a **Two players** button. Pressing it starts a second Minecraft process using
*this* window's own command line, with only the per player values replaced (name, uuid, game
directory), then puts the two windows in the top and bottom half of the screen and hides the
taskbar. Closing either window brings the taskbar back.

The child gets its own game directory (`<instance>/splitplay-p2`) so that its `options.txt`,
config and logs are separate, with the mod jars copied in and a starting config that says
`"role": "P2"`. The second window does not offer the button itself.

Two things about that child process are worth knowing, because both cost a debugging round:

- Its stdout must **not** be a pipe that nobody drains. The game writes its log to the console as
  well, and once the pipe buffer fills the child freezes mid startup - it looks exactly like a
  window that never opens. Output goes to `splitplay-p2/logs/splitplay-stdout.txt` instead.
- The game rewrites its own window geometry once the resource reload finishes, so the placement is
  re-checked every second for 25 seconds after both windows appear. A pass only moves a window whose
  rectangle actually drifted, and those passes do not activate windows - otherwise somebody who had
  already started playing would lose keyboard focus once per second. The very first window is focused
  once at the end.
- A killed process never runs its shutdown hook, so a hidden taskbar would stay hidden. Two safety
  nets: the launcher drops a `.splitplay-split` marker *before* hiding the taskbar, and every start
  checks whether the taskbar is visible at all. If it is not, it is put back - so even a hard kill of
  both windows leaves a desktop that repairs itself the next time the game starts.

## Install

1. Minecraft **1.21.1** with **Fabric Loader 0.16+** and **Java 21**.
2. Drop `splitplay-0.1.0.jar` into `mods/` of one instance. That is the whole installation - the
   second window gets the same jar copied into its own folder automatically, and jars you removed from
   the first window are removed there too, so both windows always load the same set.
3. Start the game, press **Two players**, then press **Devices** in each window and pick that
   player's keyboard and mouse.

No helper executable, no launcher script, no second instance to prepare by hand.

### The pointer

While a split window is in front, the pointer is *locked* to it: `ClipCursor` keeps it inside the
client area, `GLFW_CURSOR_HIDDEN` hides it, and it is held at the client centre every frame, so a
click cannot land on whatever happens to sit behind the window. The lock is re-asserted every frame
(another process calling `ClipCursor(NULL)` clears it) and dropped the moment the foreground window
belongs to another process - a 200 ms watchdog does that with Win32 calls only, so it still works
while the game is not rendering.

Pointer mode is deliberately never `GLFW_CURSOR_DISABLED`: that mode makes GLFW take the Win32 mouse
capture, and Windows stops delivering mouse `WM_INPUT` to the captured window. That single fact made
the mouse die in every window the moment the inventory was opened, twice.

Movement is applied **per frame**, not per tick: a raw mouse reports at 125-1000 Hz, and applying it
20 times a second reads as lag. The camera keeps vanilla's own pacing by scaling the same deltas, so
the sensitivity does not change. A cursor hovering an item slot moves at 55% speed with a soft
transition, which is what makes clicking a 16x16 slot feel steady.

## Language

The interface follows the game's language: `assets/splitplay/lang/en_us.json` and `zh_cn.json` ship in
the jar, so a Chinese client shows `双人同屏` / `设备…` and English clients keep the English labels.
Adding a language is one json file with the same keys; the `-JarCheck` suite fails if the two shipped
languages ever disagree on the key set.

## Known limits

- **Two keyboards and two mice are required.** Nothing in software can invent a second set.
- **Both players must join the same world by hand**: open the world, "Open to LAN", then the second
  window connects to `127.0.0.1:<port>`. Automatic LAN hosting is the one thing not implemented.
- **Chinese/IME input only works in one window.** Typing is replayed as characters, and an IME's
  candidate window belongs to the focused window only.
- **Assign different devices to the two windows.** The device screen greys out what the other window
  already claimed, but two windows pointed at the same keyboard by hand-edited json both react to it;
  there is no arbitration.
- Windows only (raw input, `user32`, junction-free mod copying).
- **The pointer is locked only while the window is in front.** Switching to another
  application releases it immediately; that is intended, but it does mean the two windows
  cannot both have a locked pointer at the same time (one system pointer).

## Roadmap

- automatic LAN host + auto-connect for the second window
- IME-aware typing
- gamepad support (the keyboard path already accepts any device the OS exposes)

## Building

```powershell
# the mod (Fabric Loom; downloads Minecraft, mojmap mappings and loader the first time)
.\gradlew.bat build

# the standalone raw-input probe (plain javac, no Minecraft, no Gradle)
.\build.ps1

# the key table check (fast, no game, no window)
.\build.ps1 -KeyMapCheck

# the second-window command line check (fast, no game, no window)
.\build.ps1 -CommandLineCheck

# the pointer geometry and device path checks (fast, no game, no window)
.\build.ps1 -LogicCheck

# inspect the built jar the way a user's game will (needs .\gradlew.bat build first)
.\build.ps1 -JarCheck

# the device claim files (creates and deletes a temp directory, no game needed)
.\build.ps1 -ClaimCheck

# the config file: defaults, clamping, round trip and broken files (needs gson + slf4j from .minecraft)
.\build.ps1 -ConfigCheck

# the second window's mods directory (creates and deletes a temp directory, no game needed)
.\build.ps1 -MirrorCheck

# every mixin target: does the class and the member exist in this Minecraft version, and is the mixin
# registered in splitplay.mixins.json (reads the game jar with javap, no game started)
.\build.ps1 -MixinCheck

# the key/button bookkeeping that decides what vanilla is told (press, repeat, release, modifiers)
.\build.ps1 -HeldCheck

# where the device picker puts its rows and buttons (fits, overflow, click hits)
.\build.ps1 -ScreenCheck

# the queue between the raw input thread and the game thread (move merging, overflow)
.\build.ps1 -QueueCheck
```

The checks cover the parts of the mod that run without a game: the Windows VK to GLFW key table, the
argument rewriting for the second process, the pointer geometry plus path comparison, the claim files
on disk, the config file, the mirroring of the mods directory, the contents of the shipped jar, the
mixin targets, the held-input bookkeeping, the device picker layout and the input queue. All of them
have broken silently before and all of them are cheap to test:

```
keymap: pass=33 fail=0
commandline: pass=26 fail=0
logic: pass=69 fail=0
claims: pass=31 fail=0
config: pass=50 fail=0
mirror: pass=30 fail=0
jarcheck: pass=28 fail=0 (splitplay-0.1.0.jar)
mixincheck: pass=35 fail=0 (12 class(es), client-1.21.1-20240808.144430-srg.jar)
heldinput: pass=44 fail=0
devicescreen: pass=49 fail=0
eventqueue: pass=49 fail=0
```

`-QueueCheck` covers the hand-off from the raw input thread to the game thread. A 1000 Hz mouse sends
far more events than one tick can use, so consecutive moves of the same mouse are merged (never across
a click, which would let the pointer arrive before a press that happened after it). And a stalled game
thread - opening a world takes seconds - would otherwise let a backlog build up and be replayed in one
tick, which looks like the pointer jumping and the character walking on its own; past 512 events the
queue is dropped instead, the count is logged once a second, and every held key is released.

`-ScreenCheck` covers the one screen the player has to use before anything works. Its risk is silent:
a row drawn past the bottom edge cannot be clicked, and a row that reaches into the Save/Cancel strip
looks clickable but the button swallows the click, so the player sees "I picked my mouse" and gets a
saved config with nothing in it. The split layout makes this concrete rather than theoretical, because
1920x540 at GUI scale 2 is a 960x270 GUI, which fits seven rows and no more — and a keyboard with
several HID interfaces can exceed that on its own. The check pins the row arithmetic at three real
window sizes, the hit test at the row borders, the overflow count, and six properties that have to hold
at all sixty sizes in the matrix (columns never overlap, never leave the screen, rows never reach the
buttons, and Save/Cancel stay inside the window).

`-LogicCheck` covers the pointer geometry and the screen split. The split is worth testing on odd
dimensions: taking the integer half twice leaves a one pixel strip of desktop between the two players,
so the second rectangle takes the remainder instead. `"layout": "horizontal"` is exercised there too,
which is otherwise a config value nobody tries.

`-MirrorCheck` pins the rules for the second window's `mods` directory: both windows must load the
same jars, a copy is only skipped when name, size *and* timestamp agree (two builds can share a size),
and a jar the first window no longer has is deleted rather than left for the second window to load.
Two windows on different versions of this mod produce symptoms that point nowhere near the cause.

`-ConfigCheck` is the one that guards the file users edit by hand: it pins the defaults, the clamping
of an out-of-range `sensitivity`, the `&` in device paths surviving the round trip (gson escapes it to
`\u0026` by default), and that a truncated or wrong-shaped file falls back to defaults instead of
stopping the game. It also pins that device paths are compared exactly, so a re-plugged device stops
matching on purpose.

`-JarCheck` is the release gate. It verifies the metadata, the icon, the license, that every mixin
listed in `splitplay.mixins.json` is actually packaged with a refmap entry, and — most importantly —
that **no readable Mojang name such as `net/minecraft/client/Minecraft` is left inside the jar**. The
upstream mod this project replaces shipped exactly that and died with
`NoClassDefFoundError: net/minecraft/network/chat/Component`; pointing this check at that old jar
reports the same name, so the gate does catch the failure it was written for.

`-MixinCheck` is the other half of that gate. `-JarCheck` proves a mixin class was *packaged*; this one
proves the class and member it names actually *exist* in the Minecraft version being built against. It
reads the mixin sources for `@Mixin(X.class)` plus every `@Inject(method = …)`, `@Invoker(…)` and
`@Accessor(…)`, then asks `javap` about the real game jar. A wrong member name here is not a subtle bug:
the mixin fails to apply and the player gets a crash before the title screen, with a log nobody will
read. It also fails a mixin file that is missing from `splitplay.mixins.json` — that mixin would be
silently dead, which looks exactly like a feature that does nothing. Both cases are covered by the
negative control used while writing it: renaming `onScroll` to `onScrollX` and renaming a mixin class
produced `MISSING MouseHandler.onScrollX` and `UNREGISTERED ScreenCursorMoved`, with exit code 1.

The one thing it cannot judge is a wrong parameter list on `@Inject` — the descriptor is compared only
by member name and arity of the name match, so a changed signature still has to be caught by starting
the game.

`-HeldCheck` pins the bookkeeping that decides what vanilla is told for each physical event. Three
separate bugs came from this one small state machine and none of them could be seen without a game: a
key that stayed pressed after its device was unplugged (the player kept walking), a device that reported
one click twice (one click attacked twice), and a held key that produced a single character because only
the first press was replayed (a held backspace deleted one letter and stopped). It also pins that the
press of Shift itself already carries the Shift bit, since that press arrives before Shift is recorded
as held — otherwise the first event of every chord loses the modifier.

`build\libs\splitplay-0.1.0.jar` is the remapped mod jar. Crafting it through Loom is not
cosmetic: Loom rewrites the mixin targets into intermediary names, which is the step that the old
`Mouse&mouse` Fabric release was missing (its jar shipped with mojmap names and crashed with
`NoClassDefFoundError: net/minecraft/network/chat/Component`).

JNA comes with Minecraft, so it is `compileOnly` here and nothing is bundled.

## Releasing

```powershell
.\release.ps1
# bump mod_version in gradle.properties and the version in fabric.mod.json first
```

`release.ps1` builds the jar, runs all seven offline checks, refuses to continue if one of them fails,
and assembles `dist\splitplay-<version>.zip` with the jar, `LICENSE`, `README.md`, `CHANGELOG.md` and
`INSTALL.txt`; it prints the jar size and sha256 to paste into the release notes. `-SkipChecks` skips
the test suites, `-JavaHome` overrides `JAVA_HOME`. It only accepts a `JAVA_HOME` that actually holds
`bin\javac.exe`, so a leftover `JAVA_HOME` from another JDK (or another drive) falls back to the next
candidate instead of failing with Gradle's "invalid directory". That zip is the release artifact -
`dist/` and `build/` are not tracked, so a repository only needs sources, `gradlew` and the wrapper jar.

The mod must stay MIT and the source must ship with it: the input replay relies on hooks whose
upstream design came from the GPL-3.0 `Mouse&mouse` project, so this rewrite keeps no copied code
but does keep the same idea visible in the open.

## The probe

`probe-rawinput.bat --list` prints every keyboard and mouse the system exposes, with the device
interface path. That path is what identifies "player 1's keyboard" across processes, so it is the
key the configuration is built on.

`probe-rawinput.bat` then prints every event:

```
  KEY vk=0x41 sc=0x1e down dev=VID_1C4F&PID_0002
  BUTTON b=0 down dev=VID_10C4&PID_0005
  MOVE d=(-3,1) dev=VID_10C4&PID_0005
```

Two notes:

- Injected input (`SendInput`, `keybd_event`, auto-clickers) never shows up here: raw input only
  reports real hardware.
- Holding a key produces a stream of `down` events at the keyboard repeat rate; that is normal
  hardware behaviour, not a bug.

## Licence

MIT. See `LICENSE`.

## Key shortcuts

Some mod packs draw their own main menu and never render the vanilla widget list, so the
`Two players` and `Devices` buttons can be invisible there even though they exist. Two keys work in
every case:

| Key | What it does |
| --- | --- |
| `F7` | starts two-player mode (same as the `Two players` button) |
| `Shift`+`F7` | opens the device picker |
| `F8` | opens the device picker (works in-game too, e.g. from the pause menu) |
