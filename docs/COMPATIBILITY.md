# Compatibility

What was actually tested, what is expected to work, and what cannot work.

## Verified environments

| Environment | Mods | Result |
|---|---|---|
| **Cobblemon Official Modpack [Fabric]** (1.21.1, loader 0.20.1) | 191 (incl. nested) | both windows load the pack, FancyMenu draws its custom title screen in both, the device picker and virtual cursor work, split screen and taskbar behave |
| 1.21.5 + Fabric 0.20.1 (same jar, seen 2026-11-15) | 1 (splitplay) | mod loads, mixins apply, raw input attaches, title screen buttons and virtual cursor work; hand-verified with a plain 1.21.5 + Fabric API install. One jar covers 1.21.1 through 1.21.5 |
| 1.21.1 + Fabric, sound split across two output devices | 1 (splitplay) | each window opens the device named in its own config (`OpenAL initialized on device <name>`); a stale name falls back to the default device |
| clean 1.21.1 + Fabric 0.20.1 | 1 (splitplay) | reference environment for every feature |
| 1.21.1 + Fabric + Sodium/Lithium/FerriteCore/ImmediatelyFast/EntityCulling/ModernFix | 8 | no interaction observed; the cursor is drawn through `GuiGraphics`, which the rendering mods do not touch |
| 1.21.1 + NeoForge 21.1.264 (the mod's earlier life) | - | not supported any more: the published jar is a Fabric mod |

Hardware used: Windows 10 22H2, i5-12490F, 16 GB RAM, 1920x1080 as two stacked 1920x540 halves,
two keyboards (VID_1C4F, VID_2A7A) and two mice (VID_10C4, VID_30FA).

## Mods in the tested pack that touch areas splitplay also touches

| Mod | What it does | Outcome |
|---|---|---|
| **FancyMenu** + Konkrete + Melody | replaces the title screen, never renders our widgets | works; use `F7` / `F8`, the mod also re-adds its buttons when the vanilla widget list is drawn |
| **InvMove** | walking inside GUIs | not specifically tested; it reads `KeyMapping` state, which splitplay still drives |
| **Sodium**, **Iris** | rendering rewrite | works |
| **EMI**, **JEI**, **ModMenu**, **Cloth Config** | screens | works, the arrow is drawn above their widgets |
| **Xaero's Minimap / World Map** | HUD | works |
| **CraftPresence**, **UniLib** | network / update checks | unrelated, its own errors are visible in the log |

## Categories to watch

- **Anything that hooks `KeyboardHandler` / `MouseHandler` itself** (macro mods, auto-clickers, input
  recorders, "sprint toggle" mods that reimplement keys). While isolation is active splitplay cancels
  the native callbacks, so such a mod sees nothing from the player whose input it does not own.
- **Anything that reads the OS cursor position** (screenshot tools, overlay apps, streaming overlays
  that highlight the pointer). The pointer is clipped and hidden; those will show it at the window
  centre or not at all.
- **Anything that opens its own window and expects focus** (chat overlays, second-screen mods).
  splitplay pauses replay when another process takes the foreground, which is what stops the camera
  from turning behind a browser.
- **Two input stacks in one pack** (two mods both grabbing raw input) can double events. Only one mod
  should own input.

## What cannot work by design

| Limit | Why |
|---|---|
| Windows only | `Raw Input`, `ClipCursor`, `SetWindowLongPtr`, `SetWindowPos` |
| two keyboards **and** two mice | the device picker assigns one of each to a window |
| one machine | there is no networking between windows |
| automatic LAN | not implemented; open the world to LAN and join `127.0.0.1:<port>` |
| IME (Chinese/Japanese/Korean) in both windows | the IME is a system-wide input method, not a device |
| microphones per window | Minecraft has no voice chat, and Windows has no per-application input routing |
| more than one output device per window | one OpenAL device per client; pick speakers or a headset, not both |
| gamepads | not implemented |
| a second *account* | offline names are used (`P2`), a LAN world does not check them |
| macOS / Linux | none of the input layer exists there |

## Memory and start-up cost

The second window is a full second client: the pack's mod list, its configs, and its own heap. For the
Cobblemon pack (191 mods) the second window took 40 to 90 seconds to reach the title screen and used
roughly the same memory as the first. Two clients of a large pack on 8 GB will struggle; 16 GB is
comfortable.

## Testing a new pack

1. install the mod in **one** instance (the one you start), start the game;
2. press `F7`, wait for the second window -- check its title bar shows the **pack** name, not
   `Minecraft*`;
3. look for `splitplay: starting second window in ... (shared game directory ...)` and
   `Loading <N> mods` in the log, where `N` matches the first window;
4. in each window press `F8` and assign a device pair (the other window's devices are greyed out);
5. check the pointer: in front it is hidden and clipped, behind another application it is released;
6. open a world, walk, look around, open the inventory, type in chat;
7. optional sound check: in one window's device screen pick a different output device, save, `F3 + T`,
   and confirm the log says `splitplay: opening sound device '<that name>'`;
8. optionally: `-JarCheck` against the pack's other jars only inspects splitplay's own jar, so it is
   not a pack validator.

If something fails, the useful evidence is: the `splitplay:` lines from `logs/latest.log`, both
`config/splitplay*.json` files, the pack name and mod count, and whether the second window's title bar
showed the pack name.
