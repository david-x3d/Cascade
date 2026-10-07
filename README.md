<img src="assets/icon.svg" width="96" height="96" alt="Clickety icon">

# Clickety

**Fidget toys for your phone and your Wear OS watch.**

Clickety has two apps:

- **Phone:** four fidgets in one app (Sand, Lava, Switches and Slime).
- **Watch (Wear OS):** glossy beads that pour as you tilt your wrist.

Both are tiny, work offline, have no ads or tracking, and only ask for permission to vibrate.

## Phone fidgets

| Fidget | What it is | How to play |
| --- | --- | --- |
| **Sand** | An hourglass of sand in a walnut-and-brass frame. Each grain is simulated separately and has its own mineral colour, so streams speed up as they fall, piles keep a natural slope and the neck lets through a few grains at a time. You hear the sand trickle. | Tilt to pour, double-tap to turn it over |
| **Lava** | A lava lamp. Wax blobs heat up in the pool at the bottom, rise, cool off at the top and sink again. They merge and split as they move. | Tilt the phone, stir and warm the wax with your finger |
| **Switches** | A metal panel of controls: bat-handle toggles with LEDs, lit rocker switches, clicky mechanical keys, a knob that clicks into steps, a big red arcade button and a fader. Each has its own sound and vibration. | Flip, press, turn, slide. Several fingers work at once |
| **Slime** | A jiggly, see-through jelly with glitter and air bubbles inside. It squishes, wobbles, sparkles and slaps against the edges of the screen. | Poke it, grab it, stretch it with two fingers, fling it, tilt it. Tap the colour dots to change the colour |

## Watch: beads

Hundreds of blue beads roll around the watch face as you tilt your wrist, clicking softly and ticking on your wrist as they land.

| Action | What it does |
| --- | --- |
| Tilt / shake wrist | Pour the beads |
| Touch & drag | Push beads away from your finger |
| Rotate crown / bezel | More or fewer beads |
| Double-tap | Sound on/off |
| Long-press | Haptics on/off |
| Side button / back | Exit |

## Download

Get both APKs from the [latest release](https://github.com/david-x3d/Clickety/releases/latest):

- `Clickety-phone-v2.0.0.apk`: for Android phones running Android 11 or newer.
- `Clickety-wear-v2.0.0.apk`: for Wear OS 3 or newer watches (Pixel Watch, Galaxy Watch 4 or newer, etc.).

## Install on a phone

### Without a PC

1. Open the [latest release](https://github.com/david-x3d/Clickety/releases/latest) on your phone and tap `Clickety-phone-v2.0.0.apk` to download it.
2. Open the downloaded file, from the download notification or the **Files** app.
3. If Android blocks it, tap **Settings** and turn on **Allow from this source** for your browser or Files app, then go back.
4. Tap **Install**. If Play Protect warns about an unknown app, tap **More details → Install anyway**.

### With a PC (adb)

1. Turn on USB debugging. Go to **Settings → About phone**, tap **Build number** 7 times, then go to **Settings → System → Developer options** and turn on **USB debugging**.
2. Plug the phone in and allow the debugging prompt.
3. Run:

   ```sh
   adb install Clickety-phone-v2.0.0.apk
   ```

## Install on a watch

Watches can't install APKs on their own, so you always go through wireless debugging, either from your phone or from a PC.

### First: enable developer options on the watch

1. On the watch open **Settings → System → About** (on Galaxy Watch: **Settings → About watch → Software information**).
2. Tap **Build number** 7 times until you see "You are now a developer".
3. Go back to **Settings → Developer options** and turn on **ADB debugging** and **Wireless debugging** (older watches: **Debug over Wi-Fi**).
4. Connect the watch to the same Wi-Fi network as your phone or PC, and keep its screen on while you install. Watches turn Wi-Fi off when the screen sleeps.

The pairing code and port change every time you open **Pair new device**. That's normal, and you only need to pair once. Installing uses a **different** port: the **IP address & port** on the main **Wireless debugging** screen.

### Without a PC (phone only)

Use a free sideloading app on your Android phone. **Bugjaeger** and **Wear Installer 2** both work. These steps are for Bugjaeger:

1. Download `Clickety-wear-v2.0.0.apk` from the release page on your phone.
2. On the watch, open **Developer options → Wireless debugging → Pair new device**. It shows an IP:port and a 6-digit code.
3. In Bugjaeger, tap the **plug icon → Pair** and enter that IP:port and code.
4. Connect to the IP:port shown on the main **Wireless debugging** screen.
5. Open the **Packages** tab, tap **+** and pick the downloaded APK.
6. Clickety appears in the watch's app list.

On older watches with **Debug over Wi-Fi** instead of pairing, skip steps 2–3. Connect straight to the IP:port it shows and accept the prompt on the watch.

### With a PC (adb)

1. Install the Android platform tools so you have `adb` ([download](https://developer.android.com/tools/releases/platform-tools)).
2. On the watch, open **Developer options → Wireless debugging → Pair new device**.
3. On the PC:

   ```sh
   adb pair <ip>:<pairing-port>      # enter the 6-digit code from the watch
   adb connect <ip>:<port>           # port from the main Wireless debugging screen
   adb install Clickety-wear-v2.0.0.apk
   ```

   On older watches with **Debug over Wi-Fi**, skip `adb pair` and run `adb connect <ip>:5555`, then accept the prompt on the watch.
4. Open Clickety from the watch's app list.

Pixel Watches can't use wired adb because their chargers only carry power, so Wi-Fi is the only way.

Turn off wireless debugging afterwards to save battery.

### Upgrading from Cascade 1.0

The watch app used to be called Cascade. Clickety installs alongside it as a separate app, so uninstall Cascade from the watch's app list or run `adb uninstall dev.cascade`.

## Building from source

Requires JDK 17 and the Android SDK (platform 36).

```sh
./gradlew assembleRelease
# phone/build/outputs/apk/release/phone-release.apk
# wear/build/outputs/apk/release/wear-release.apk
```

Release builds are signed with your local debug key (`~/.android/debug.keystore`) if it exists, so you can sideload them directly.

## How it works

Everything is drawn with plain Android `Canvas` and uses no third-party libraries.

**Phone** (`phone/`)
- `SandglassView.kt`: a falling-sand simulation at about one grain per 0.2 mm. Each grain has a speed, which makes streams accelerate. A sideways-creep rule flattens piles to a natural slope. Grains are shaded by whether they face the light. The glass, walnut wood texture, brass collars and turned spindles are all drawn in code.
- `SandAudio.kt`: a live audio stream that mixes a soft hiss for moving sand with tiny clicks for individual grains landing.
- `LavaLampView.kt`: wax blobs that heat in the bottom pool, rise, cool and sink. They're rendered at low resolution through a colour lookup table, so the wax glows brighter near the bulb.
- `SwitchesView.kt`: hand-drawn metal and plastic controls with spring animations. Several fingers can use them at once.
- `SlimeView.kt`: a soft body of 96 points that pulls back towards its resting shape, keeps its volume and keeps a smooth outline. The glitter and bubbles inside squash and swirl with the body. Shadows, glows and highlights make it look see-through.
- `Feedback.kt`: synthesises every click, clack and squelch at first launch, and maps them to vibration effects where the phone supports them.

**Watch** (`wear/`)
- `BeadSim.kt`: bead physics with a spatial grid for collisions and a round bowl.
- `BeadView.kt`: draws each bead from a pre-rendered sprite, shaded by its speed.
