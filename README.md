<img src="assets/icon.svg" width="96" height="96" alt="Cascade icon">

# Cascade

**Fidget beads for Wear OS. Tilt your wrist and they pour.**

Hundreds of glossy blue beads fill your watch face and roll with real physics. Tilt, flick or shake your wrist and they slide, tumble and pile up against the bezel, with soft clicks and haptic ticks as they land.

## Features

- Real-time bead physics: gravity from the accelerometer and bead-to-bead collisions.
- Shake and flick: sudden wrist movements throw the beads around.
- Beads glow white while moving and settle back to deep blue at rest.
- Clicks are synthesised on the watch, and haptic ticks scale with how hard the beads land.
- Fits round and square watch faces.
- Tiny (~50 KB), works offline, no permissions apart from vibration, no tracking.

## Controls

| Action | What it does |
| --- | --- |
| Tilt / shake wrist | Pour the beads |
| Touch & drag | Push beads away from your finger |
| Rotate crown / bezel | More or fewer beads |
| Double-tap | Sound on/off |
| Long-press | Haptics on/off |
| Side button / back | Exit |

Swipe-to-dismiss is turned off so dragging doesn't close the app.

## Install

Requires a Wear OS 3+ watch (Pixel Watch, Galaxy Watch 4 or newer, TicWatch, etc.).
Download `Cascade-v1.0.0.apk` from the [latest release](https://github.com/david-x3d/cascade/releases/latest).

### First: enable developer options on the watch

You need this for both install methods.

1. On the watch open **Settings → System → About** (on Galaxy Watch: **Settings → About watch → Software information**).
2. Tap **Build number** 7 times until you see "You are now a developer".
3. Go back to **Settings → Developer options** and turn on **ADB debugging** and **Debug over Wi-Fi** (newer watches call it **Wireless debugging**).
4. Connect the watch to the same Wi-Fi network as your phone or PC.

### Without a PC (phone only)

Use a free sideloading app on your Android phone. **Bugjaeger** and **Wear Installer 2** both work. These steps are for Bugjaeger:

1. Download `Cascade-v1.0.0.apk` from the release page on your phone.
2. On the watch, open **Developer options → Wireless debugging** and tap **Pair new device**. It shows an IP:port and a 6-digit pairing code.
3. In Bugjaeger, tap the **plug icon → Pair**, enter the IP:port and code from the watch.
4. Connect to the IP:port shown on the main **Wireless debugging** screen (this port is different from the pairing port).
5. Open the **Packages** tab, tap **+**, and pick the downloaded APK.
6. Cascade shows up in the watch's app list.

On older Wear OS versions without pairing codes, skip step 2–3. Connect straight to the IP:port shown under **Debug over Wi-Fi** and allow the prompt on the watch.

### With a PC (adb)

1. Install the Android platform tools so you have `adb` ([download](https://developer.android.com/tools/releases/platform-tools)).
2. On the watch, open **Developer options → Wireless debugging → Pair new device**.
3. On the PC:

   ```sh
   adb pair <ip>:<pairing-port>      # enter the 6-digit code from the watch
   adb connect <ip>:<port>           # port from the main Wireless debugging screen
   adb install Cascade-v1.0.0.apk
   ```

   On older watches with **Debug over Wi-Fi**, skip `adb pair` and run `adb connect <ip>:5555`, then accept the prompt on the watch.
4. Open Cascade from the watch's app list.

Turn off ADB / Wireless debugging afterwards to save battery.

## Building from source

Requires JDK 17 and the Android SDK (platform 36).

```sh
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Release builds are signed with your local debug key (`~/.android/debug.keystore`) if it exists, so you can sideload them directly.

## How it works

- `BeadSim.kt`: Verlet integration with 4 substeps, a uniform spatial grid for collisions and a circular bowl constraint. Bead speed is capped below one radius per substep so beads can't pass through each other.
- `BeadView.kt`: draws each bead as a pre-rendered glossy sprite in one of 24 shades, chosen from its speed and a per-bead tint.
- `Feedback.kt`: synthesises four click variants into WAV files at first launch and plays them through `SoundPool`, and uses `PRIMITIVE_TICK` haptics where the watch supports them.
- `MainActivity.kt`: reads the accelerometer, handles the rotary crown and saves your settings.
