# DeskCast

Your Android phone as a desktop on any screen.

- **Desktop on the big screen** — plug a USB‑C → HDMI / DisplayPort adapter or dock into the phone and DeskCast opens a desktop there: wallpaper, pinned apps, taskbar, start menu with search, clock and battery.
- **Every app as a desktop window** — apps open as movable, resizable windows (or maximized / full screen / phone‑shaped), chosen per app. Games default to full screen.
- **Phone desktop (no monitor)** — the phone's own screen becomes the desktop: landscape, taskbar, start menu, apps in movable windows. Starts automatically when you plug in a mouse or keyboard with no monitor connected (or from the app). Mouse, keyboard, shortcuts and game key mapping work exactly as on a monitor. Exit from the start menu.
- **Mirror mode for games** — shows the phone screen on the external display, scaled up, composited on the GPU with nothing encoded, so there is no added lag.
- **Stream to a PC over USB** — for phones without video out: hardware H.264 (no B‑frames, realtime priority) sent over the USB cable, typically 30–60 ms.
- **Mouse & keyboard** — USB/Bluetooth mouse and keyboard work natively. DeskCast adds Win‑key start menu, Win+D, Alt+Tab, Alt+F4, a phone‑as‑touchpad with a cursor on the big screen, and **keyboard → touch mapping for games** (tap keys and WASD/arrow joysticks).

## Install

Download the APK from the [latest release](../../releases/latest) on your phone and install it (allow "install unknown apps" for your browser if asked). Every push to `main` builds a new release automatically.

## One‑time setup (no computer needed)

Open DeskCast — the **Setup** card at the top has three steps and tells you which one is next.

1. **Unlock DeskCast** — install the free app **Shizuku**, open it and start it with *Wireless debugging* (Shizuku shows each step; Wi‑Fi must be on and Developer options enabled: Settings → About phone → tap *Build number* 7 times). Back in DeskCast tap **Unlock now** → **Allow**.
   *Have a computer?* Tap *I have a computer instead* and run the one ADB command it shows.
2. **DeskCast control** turns itself on after step 1 (no hunting in Accessibility, no "restricted setting" errors).
3. **Desktop windows** turn themselves on. Restart the phone once when DeskCast asks.

After that, Shizuku isn't needed any more.

## Using it

| You want | Do this |
|---|---|
| Desktop on a monitor/TV | Plug in the adapter. The desktop opens by itself (or tap *Open desktop on big screen*). |
| Desktop with no monitor | Plug in a mouse/keyboard (USB OTG or Bluetooth) — the phone desktop starts by itself — or tap *Start phone desktop*. Optional: *Choose Home app* → "DeskCast Phone Desktop" so Home returns to the desktop. Start menu → *Exit desktop* to leave. |
| Open an app | Win key or ⊞ → click it, or just start typing to search. |
| Choose how an app opens | Right‑click (or long‑press) its icon → *Open as window / maximized / full screen / phone‑shaped*, or *Always open as…* to save it. |
| Pin to desktop | Right‑click an app → *Pin to desktop*. |
| No mouse | DeskCast → *Use phone as touchpad*: move, tap = click, double‑tap‑hold = drag, two fingers = scroll, two‑finger tap = back. |
| Play a phone game on the TV | *Mirror phone screen*, or open the game on the desktop in full screen. |
| Keyboard in a game | Works straight away: WASD moves, arrows swipe (Subway Surfers), Space jumps; Free Fire / PUBG / CODM get their own layouts. Press **F10** in the game to drag keys onto its buttons, then Save. F12 pauses keys while you type in chat. |
| Mouse in a game (Android 14+) | Click = tap, hold = drag, wheel = swipe, right click = long press (or the scope in shooters). Press **`** or middle‑click for mouse aim in shooters; again for the cursor. Ctrl+Alt+M switches back to a normal mouse. |
| Phone has no video out | DeskCast → *Start stream*, then on the PC run `pc/deskcast-view.bat` (Windows) or `pc/deskcast-view.sh` (macOS/Linux). Needs `adb` and `ffplay`. |

### Shortcuts

| Keys | Action |
|---|---|
| Win | Start menu |
| Win + D | Show desktop |
| Win + N | Notifications |
| Alt + Tab | Switch apps |
| Alt + F4 | Back / close |
| F10 | Edit game keys on top of the game |
| F12 or Ctrl + Shift + K | Pause game keys |
| ` or middle click | Mouse aim on/off |
| Ctrl + Alt + M | Normal mouse everywhere on/off |

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


## HDMI connection detector & video player

Open **DeskCast → Start → HDMI detector, screen test & videos**. It is also linked from **Connect a monitor**.

- Live scan of Android external displays and USB host accessories. **USB attached is not equivalent to HDMI video connected.** Detects USB DisplayLink vendor IDs and USB Billboard mode failures without claiming the TV is receiving a signal.
- A built-in offline animated color/motion test, usable on the phone or on a detected Android external display.
- Sample **online** videos (MDN CC0 flower, a 10-second Big Buck Bunny sample, the W3C Sintel trailer), plus a video picker for local phone files and direct HTTPS MP4 URLs. Samples may require internet and third-party sites to remain available.
- Play/pause/seek, screen selection and automatic transfer to/from an external presentation display when Android exposes it. The app keeps the phone as the controller while video is shown on the external display.
- Links to Android Cast settings and the official DisplayLink Presenter app.

**Hardware restriction:** The app cannot create DisplayPort Alt Mode or a physical HDMI video signal on an unsupported USB-C port. An ordinary HDMI hub is not the same as a USB graphics adapter; a compatible DisplayLink dock requires its vendor's app. Android may report wireless or virtual external displays as well as wired HDMI; their transport cannot always be identified from the public API. Protected DRM video may not play on external/virtual displays.

All diagnosis and offline animation runs on device, and the app does not upload personal videos. Video samples load from public HTTPS hosts when chosen.
