# Troubleshooting

Start here when a window misbehaves. Every section is a log line you can search for, followed by what
it means and what to do.

## Where the logs are

```
<instance>/logs/latest.log                  both windows write here (see below)
<instance>/logs/splitplay-p2-stdout.txt     console output of the second window
<instance>/logs/splitplay-p2-stderr.txt     its stack traces
<instance>/config/splitplay.json            first window's settings
<instance>/config/splitplay-p2.json         second window's settings
<game root>/splitplay-devices/<pid>.json    which window uses which devices
```

**Both windows write `logs/latest.log`.** They share the working directory on purpose: mods resolve
paths against the working directory, and giving the second window its own made FancyMenu lose its
layout and the second window show a vanilla title screen. The price is that the second process
truncates the first one's log. If you need both, start the windows one after the other and copy the
file in between, or read `splitplay-p2-stdout.txt`.

## Log lines worth knowing

| Line | Meaning |
|---|---|
| `splitplay: raw input attached to the game window` | input arrives through the game's own message loop (the healthy case) |
| `splitplay: could not attach to the game window (...)` + `using a separate sink` | fallback mode; mouse input may stop once a world grabs the mouse |
| `splitplay found N keyboard(s) and M mouse/mice` | device enumeration; `N < 2` or `M < 2` means the machine cannot host two players |
| `splitplay: input isolation on, replaying VID_x + VID_y` | this window owns those two devices and drops its native input |
| `splitplay: input isolation off, vanilla input is back` | settings saved with no devices, or devices went away |
| `splitplay: assigned <device> is not plugged in ...` | the saved device is gone (moved port, unplugged); window falls back to vanilla input |
| `splitplay: 5s: dropped N native, sent M raw, applied K move(s)` | the diagnostic summary: `sent` = events for this window, `applied` = movement actually used |
| `splitplay: the game thread fell behind, discarded N queued input event(s)` | queue overflow (512); a long freeze happened |
| `splitplay: focus is in another application, replay paused ...` | another program is in front; keys were released on purpose |
| `splitplay: cursor mode X -> Y (focused=.., screen=.., clipped=..)` | pointer policy transitions |
| `splitplay: pointer locked to this window` | clip re-asserted |
| `splitplay: reusing this window's command line (N argument(s))` | second window arguments were read correctly |
| `splitplay: starting second window in <dir> (shared game directory ...)` | the second client uses the same instance (this is what makes a pack look like a pack) |
| `splitplay: second window started, pid N` / `a window closed, taskbar restored` | lifecycle |
| `splitplay: both windows arranged (N pass(es), 0 re-discovery), taskbar hidden` | split screen applied |
| `splitplay: re-discovered the game windows after a stale handle` | a pack recreated its window; recovered |
| `splitplay: devices saved, keyboard=... mouse=... active=true` | the device picker wrote the settings |
| `splitplay: sound output for this window is '<name>'` | the window was told to use that output device (empty = the game's own choice) |
| `splitplay: opening sound device '<a>' instead of '<b>'` | `LibraryMixin` swapped the device while the sound engine was coming up |
| `splitplay: window 'P1' (pid N) already uses <device>` | cross-window claim, the other window owns it |
| `splitplay: taskbar was left hidden (marker ...), restoring it` | the taskbar was left hidden by an earlier kill; self-healed |

## Symptoms

### The second window is vanilla (no pack menu, no pack mods)

The log shows `Loading 4 mods` instead of the pack's count, and
`Completely ignored arguments: [...]`. The command line was read by splitting a string, so a version
name with spaces fell apart. Fixed in 0.1.0 (`CommandLineToArgvW`); if you see it, the jar is old.

If the mod count is right but the **title screen** is vanilla, the window is not using the game
directory as its working directory (`./config/fancymenu/customization` empty). Also fixed in 0.1.0;
verify with `splitplay: starting second window in ... (shared game directory ...)`.

### The mouse works in menus but freezes in a world

This is `GLFW_CURSOR_DISABLED` (or a mouse capture) cutting off `WM_INPUT`. Check for
`raw input attached to the game window`: if it says `using a separate sink`, the window hook never
succeeded. splitplay never sets `CURSOR_DISABLED` and cancels `MouseHandler.grabMouse`; if you are on
an old build, that is the cause.

### The camera does not move although the mouse does

Read the 5 s line:

- `sent` stays at 0: raw input stopped (hook, capture, or the device is claimed by the other window).
- `sent` grows, `applied` stays: the events are for another device than the one saved in `config/`.
  The line also reports `ignored <device> xN` when another mouse is moving.
- both grow: the camera turned, so something else eats it -- usually a pause screen. Check
  `state=world` and `screen=-` in the same line.

### The pointer escapes the window, or the desktop cursor is locked

The clip is process-wide Win32 state. If the game was killed while holding it, run this once:

```powershell
Add-Type -TypeDefinition 'using System;using System.Runtime.InteropServices;public class C{[DllImport("user32.dll")] public static extern bool ClipCursor(IntPtr r);}'
[C]::ClipCursor([IntPtr]::Zero)
```

When splitplay is running, this should not happen: the clip is re-asserted every frame and released
when another application comes to the front, plus by a 200 ms watchdog.

### The taskbar is gone

Close either window (it restores on exit), or restart the game (it detects the leftover marker file
`.splitplay-split` and restores it). Manual:

```powershell
Add-Type -TypeDefinition 'using System;using System.Runtime.InteropServices;public class T{[DllImport("user32.dll")] public static extern IntPtr FindWindow(string c,string n);[DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h,int c);}'
[T]::ShowWindow([T]::FindWindow('Shell_TrayWnd',$null), 5)
```

### A window says "not plugged in" after moving a cable

Device paths contain the USB port. Move a device back, or press `F8` and pick it again -- the config
is per window, so do it in the window that lost the device.

### A device is greyed out but nobody uses it

A stale claim file. They are pruned automatically when the owning pid is dead, but if a pid was
reused:

```powershell
Remove-Item '<game root>\splitplay-devices\*.json'
```

### The two windows fight over devices

Both windows picked the same keyboard. The picker greys out claimed devices, but the claim is only
written when you press Save. Pick one device per window and save both.

### Both windows play through the same speakers

Neither window has a sound device set, so both follow `options.txt`. Press `F8` in one of them and
click the button in the top right until it names that player's headset, save, then `F3 + T`.

### The sound device list is empty or the game goes silent

An empty list means `ALC_ENUMERATE_ALL_EXT` is missing (unusual, comes with OpenAL Soft). A name that
no longer exists is handled inside Minecraft: `Library.openDeviceOrFallback` quietly falls back to the
default device, so a stale name costs you the split, not the sound. Check
`splitplay: opening sound device '<a>' instead of '<b>'` to see what was actually requested.

### Chinese text does not type in one window

Expected: the character path is per device, the Windows IME is not. Letters and digits work in both
windows; an IME serves the focused window only.

### The game stutters for a few seconds after loading a world

Vanilla loads chunks and the queue is bounded: splitplay drops movement (and releases held keys) when
it falls behind rather than replaying stale input. Look for
`the game thread fell behind, discarded ...`; this is by design.

## Recovery checklist

1. `Get-Process java` -- are there really two clients?
2. `logs/latest.log`: `raw input attached`, `input isolation on`, `both windows arranged`.
3. `config/splitplay.json` and `splitplay-p2.json`: different devices, `enabled: true`.
4. `splitplay-devices\*.json`: one file per window, pids alive.
5. Pointer stuck? release the clip (above). Taskbar gone? restart the game.
6. No sound in one window? Check `soundDevice` in that window's config and press `F3 + T`.
7. Still stuck: send the `splitplay:` lines from `latest.log` (they are few) plus the two config files.
