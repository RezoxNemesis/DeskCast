# DeskCast

Your Android phone as a desktop on any screen.

- **Desktop on the big screen** — plug a USB‑C → HDMI / DisplayPort adapter or dock into the phone and DeskCast opens a desktop there: wallpaper, pinned apps, taskbar, start menu with search, clock and battery.
- **Every app as a desktop window** — apps open as movable, resizable windows (or maximized / full screen / phone‑shaped), chosen per app. Games default to full screen.
- **Mirror mode for games** — shows the phone screen on the external display, scaled up, composited on the GPU with nothing encoded, so there is no added lag.
- **Stream to a PC over USB** — for phones without video out: hardware H.264 (no B‑frames, realtime priority) sent over the USB cable, typically 30–60 ms.
- **Mouse & keyboard** — USB/Bluetooth mouse and keyboard work natively. DeskCast adds Win‑key start menu, Win+D, Alt+Tab, Alt+F4, a phone‑as‑touchpad with a cursor on the big screen, and **keyboard → touch mapping for games** (tap keys and WASD/arrow joysticks).

## Install

Download the APK from the [latest release](../../releases/latest) on your phone and install it (allow "install unknown apps" for your browser if asked). Every push to `main` builds a new release automatically.

## One‑time setup

1. **Turn on DeskCast control** — DeskCast → Setup → *Turn on DeskCast control* → find **DeskCast control** in Accessibility and switch it on. This powers the cursor, touchpad, shortcuts, key mapping and auto‑opening the desktop.
2. **Enable desktop windows** (needed once for movable windows). On a computer with [platform‑tools](https://developer.android.com/tools/releases/platform-tools), enable USB debugging on the phone and run:

   ```
   adb shell pm grant com.rezox.deskcast android.permission.WRITE_SECURE_SETTINGS
   ```

   Then in DeskCast tap **Enable desktop windows** and **restart the phone once**. This switches on Android's own freeform‑window and desktop‑on‑external‑display modes. Without it, apps still open on the big screen, but full screen.

## Using it

| You want | Do this |
|---|---|
| Desktop on a monitor/TV | Plug in the adapter. The desktop opens by itself (or tap *Open desktop on big screen*). |
| Open an app | Win key or ⊞ → click it, or just start typing to search. |
| Choose how an app opens | Right‑click (or long‑press) its icon → *Open as window / maximized / full screen / phone‑shaped*, or *Always open as…* to save it. |
| Pin to desktop | Right‑click an app → *Pin to desktop*. |
| No mouse | DeskCast → *Use phone as touchpad*: move, tap = click, double‑tap‑hold = drag, two fingers = scroll, two‑finger tap = back. |
| Play a phone game on the TV | *Mirror phone screen*, or open the game on the desktop in full screen. |
| Keyboard controls in a game | Right‑click the game → *Keyboard mapping…* → *Open game* → *Capture* → *+ Key* / *+ Joystick* → tap the spot → press the key → *Save*. F12 pauses mapping while you type in chat. |
| Phone has no video out | DeskCast → *Start stream*, then on the PC run `pc/deskcast-view.bat` (Windows) or `pc/deskcast-view.sh` (macOS/Linux). Needs `adb` and `ffplay`. |

### Shortcuts

| Keys | Action |
|---|---|
| Win | Start menu |
| Win + D | Show desktop |
| Win + N | Notifications |
| Alt + Tab | Switch apps |
| Alt + F4 | Back / close |
| F12 or Ctrl + Shift + K | Pause game key mapping |

## What the hardware has to support

- **Video out over USB‑C** is a hardware feature (DisplayPort Alt Mode). Many flagships and gaming phones have it (e.g. Samsung Galaxy S/Note/Tab S, ASUS ROG, many Motorola and high‑end Xiaomi/OnePlus models, newer Pixels on recent Android versions); many budget phones don't — check your model's spec sheet for "DisplayPort" or "video out". No app can add it. DeskCast tells you on the main screen whether a display was detected; if not, use **Stream to a PC**.
- **OTG**: a powered USB‑C hub/dock lets you connect HDMI, a mouse, a keyboard and charging at the same time.
- Android 11 or newer.

## Known limits

- If an app is already open on the phone, Android may keep it there; close it on the phone first, then open it from the desktop.
- Some apps refuse to be resized or to run on a second screen; they open full screen or stay on the phone.
- The mouse pointer: on Android 15+ and Samsung DeX the system pointer moves onto the external screen. On other phones it can stay on the phone screen — use the touchpad, or *Mouse* on the touchpad screen to drive DeskCast's own cursor (experimental; the capture is released when you click into another app).
- The PC stream is video only; sound plays from the phone.
- Key mapping uses Android accessibility gestures, which add a few milliseconds compared with a real touch; fine for most games, not for frame‑perfect competitive play.

## Build

GitHub Actions builds `app-release.apk` on every push (`.github/workflows/build.yml`). Locally: Android SDK 35 + Gradle 8.11, `gradle :app:assembleRelease`.
