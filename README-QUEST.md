# Fate of the Galaxy VR (Meta Quest and PICO)

**Download, install guide, controls and pictures: [portroyale.online/fotg.html](https://portroyale.online/fotg.html)**

[Fate of the Galaxy](https://www.moddb.com/games/fate-of-the-galaxy), the free
Star Wars Original Trilogy total conversion, running natively on the Meta Quest 3,
built on the [FreeSpace Open](https://github.com/scp-fs2open/fs2open.github.com)
engine with OpenXR on OpenGL ES. Branch: `fotg-vr`. FreeSpace 2 is not needed.

The APK contains only the engine. The game data (free, about 2.6 GB download,
8.7 GB unpacked) comes from the Fate of the Galaxy team's Nebula/Knossos release
and goes on the headset.

## Install

1. Install `FotG-VR-<version>.apk` with SideQuest or `adb install -r -g`.
2. Get the game data into `/sdcard/FotG/FotG_R` on the headset (so that the
   folder `/sdcard/FotG/FotG_R/data` holds `tables`, `models`, `maps`, ...):
   - With Python 3, 7-Zip and the Quest connected by USB:
     `python3 tools/quest/get_fotg_data.py --push`
     (downloads the newest release from Nebula, checks it, checks the free space on
     the headset and copies it over).
   - Or install Fate of the Galaxy with Knossos.NET on a PC and copy the
     contents of its `FotG_R-<version>` folder to `/sdcard/FotG/FotG_R` with
     SideQuest.
3. Start **Fate of the Galaxy VR** from *Unknown Sources* and allow
   "access to all files" when asked.

### PICO (untested)

The same APK also runs on PICO 4, PICO 4 Ultra and PICO Neo 3 (PICO OS 5.9.0 or
newer); the PICO controllers use the same button layout as the Touch controllers
below. PICO support is not tested on PICO hardware yet.

1. Install with `adb install -r -g FotG-VR-<version>.apk` (SideQuest is a Quest tool).
2. Copy the game data the same way: `python3 tools/quest/get_fotg_data.py --push`
   (add `--serial <id>` if `adb devices` lists more than one device), or copy the
   `data` folder to `/sdcard/FotG/FotG_R` with any file manager / adb.
3. Start the game and allow storage access when it asks; it restarts by itself.

The rendering resolution is picked for the headset's chip: 1125 x 1125 per eye on
XR2 Gen 2 (Quest 3/3S, PICO 4 Ultra), 750 x 750 on XR2 Gen 1 (Quest 2/Pro, PICO 4,
Neo 3). Change it in Options > Detail > Game VR Resolution (restart needed). If the
game doesn't start or the controllers do nothing,
`adb logcat -s FotGVR:* QuestVR:*` shows how far it gets.

## Controls

![Controller mapping](docs/controls.webp)

Menus, briefings and cutscenes are shown on a large screen in front of you.

| Input | Action |
|---|---|
| Laser (either controller) + trigger | Click |
| A | Enter |
| B / left Menu button | Escape (back / pause) |
| A, B, Menu or trigger during a cutscene | Skip it |
| X / Y | F11 / Space: fleet information and its next page (Galactic Civil War campaign) |

Text fields (pilot callsign, chat): a keyboard appears below the menu screen while the
field is active; point at a key and pull the trigger. Hold Back to keep deleting.

Flight (the Touch controllers act as one gamepad; every binding can be changed
in Options > Controls, "Reset to default" restores this layout):

| Input | Action |
|---|---|
| Left stick | Pitch and turn |
| Right stick left/right | Roll |
| Right stick up/down | Throttle (stays where you leave it) |
| Right stick click | Match target speed |
| Left stick click (tap) | Laser fire mode (single / linked) |
| Left stick click (hold 1 s) | Jump to hyperspace (end mission) |
| Right trigger | Lasers |
| Left trigger | Missiles / torpedoes |
| Right grip | S-foils open / closed |
| A | Target nearest enemy (next ship if there is no enemy) |
| B | Target ship in crosshair |
| X | Next missile type |
| Y | Next gun type |
| Left Menu button | Pause menu |
| Left grip + A | Next target |
| Left grip + B | Target your target's attacker |
| Left grip + X | Next subsystem on target |
| Left grip + Y | Comms menu |
| Left grip + right grip | Targeting computer on / off |
| Left grip + left stick up / down / right / left | More power to lasers / shields / engines; balance power |
| Left grip + right stick right / left | Shields to the front / rear |
| Left grip + right stick up / down | Full speed / full stop |
| Left grip + left stick click | Look behind |
| Left grip + right stick click | Chase view on / off |

While the left grip is held, the sticks don't steer, so a flick only changes power or shields.

Comms menu open: left stick up/down picks an order, A or a trigger confirms,
Y closes the menu.

## Building

Linux host with the Android SDK/NDK: `tools/quest/build_apk.sh --install`.
`tools/quest/run.sh` launches it on a connected headset and
`tools/quest/mission_test.sh` flies a Rebel mission and prints the frame rate.

## License

The FreeSpace Open engine is released under the Volition source code license
(non-commercial use only); see `Copying.md`. Fate of the Galaxy is a fan
project by the Fate of the Galaxy Dev Team, not associated with Lucasfilm or
Disney; its data is not included in the APK.
