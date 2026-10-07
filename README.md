<p align="center"><img src="assets/icon.svg" width="112" alt="Cascade icon"></p>
<h1 align="center">Cascade</h1>
<p align="center">Glassy fidget beads for Wear OS and Android. Tilt and they pour.</p>

Hundreds of blue beads in a dark, shallow tray. Tilt, shake, stir and throw them with your fingers. Inspired by the feel of real glass beads and Beadfall on Apple Watch, built independently for Android with Kotlin and the platform SDK.

- Shared impulse physics with varied sphere sizes and masses, friction, spin, rolling resistance and sleeping.
- Baked glass lighting: azure gradients, sharp highlights, translucent rim light, soft shadows and rotating internal swirls. Fast beads brighten smoothly.
- Round watch tray or rounded rectangular phone tray; edge-to-edge phone rendering with inset-aware controls.
- 250–450 beads on the watch; 600–1,200 on the phone. Phone colours: Azure, Pearl, Amber and Emerald.
- Synthesized glass sounds with limited polyphony; impact-only haptics with a 120 ms minimum interval and a replenishing energy budget. Sound and haptics have independent saved switches.
- No accounts, ads, network permission or third-party runtime libraries.

## Download

**[Release v2.0.0](https://github.com/david-x3d/Cascade/releases/tag/v2.0.0)**

| Device | APK | Requirement |
|---|---|---|
| Android phone | [Cascade-phone-v2.0.0.apk](https://github.com/david-x3d/Cascade/releases/download/v2.0.0/Cascade-phone-v2.0.0.apk) | Android 11+ |
| Wear OS watch | [Cascade-wear-v2.0.0.apk](https://github.com/david-x3d/Cascade/releases/download/v2.0.0/Cascade-wear-v2.0.0.apk) | Wear OS 3+ |

Both variants use `dev.cascade`, version **2.0.0 (2)**. Install the correct APK on each device. The release uses the developer's local Android debug signing key for sideloading. An older Cascade install signed by another key must be uninstalled before installing this build. Clickety (`dev.clickety`) is a separate app and may be removed manually.

## Controls

### Watch

| Action | Control |
|---|---|
| Pour / shake the beads | Tilt / move your wrist |
| Stir and throw | Drag or flick a finger |
| Change bead count | Turn the crown |
| Toggle sound | Double-tap |
| Toggle haptics | Long-press |
| Leave the app | Side button / system Home button |

Swipe-to-dismiss is disabled so dragging does not dismiss the tray. The app pauses when it leaves the foreground; it does not force the screen to stay awake.

### Phone

| Action | Control |
|---|---|
| Pour / shake | Tilt / move the phone |
| Stir and throw | Drag or flick, with multiple fingers |
| Sound and haptics | **•••** → individual switches |
| Change bead count | **•••** → slider |
| Change colour | **•••** → colour selector |

Portrait orientation is locked. A brief first-launch hint fades away automatically. Settings are saved locally.

## Install on a phone

### Without a PC

1. Download the **phone APK** above on your Android phone.
2. Open it from the browser's downloads or Files.
3. If prompted, open Settings and enable **Allow from this source** for that browser or file manager, then return to the installer.
4. Review the installation prompt and install Cascade. Google Play Protect may scan the APK or display a warning because it is distributed outside Google Play; review the source and warning before deciding to proceed. You do not need to disable Play Protect globally.
5. You can turn off **Allow from this source** again after installation.

### With a PC

Install [Android SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools), enable developer options and USB debugging on the phone, connect it by USB and accept its authorization prompt:

```sh
adb devices
adb -s PHONE_SERIAL install -r Cascade-phone-v2.0.0.apk
```

Replace `PHONE_SERIAL` with the phone's entry in `adb devices` (or omit `-s PHONE_SERIAL` when only one device is connected).

## Install on a watch

Use the **wear APK**. Connect the watch and the installing phone or computer to the same Wi-Fi network. Enable developer options by tapping the watch's build number seven times, then enable ADB debugging and Wireless debugging. Exact menu labels vary by watch.

### Without a PC

1. Download the wear APK onto your Android phone.
2. Install [Bugjaeger](https://sisik.eu/bugjaeger) or [Wear Installer 2](https://play.google.com/store/apps/details?id=org.freepoc.wearinstaller2) on the phone.
3. On the watch, open **Developer options → Wireless debugging → Pair new device**.
4. In the installer app's wireless pairing screen, enter the watch IP, the **pairing port** and the six-digit pairing code shown on the watch.
5. After pairing, return to the watch's main **Wireless debugging** screen. Connect the installer to the IP and **connection port** shown there.
6. In Bugjaeger, open Packages and choose the APK installation action; in Wear Installer 2, select the downloaded APK through its custom APK option. Choose `Cascade-wear-v2.0.0.apk` and install.

See [Bugjaeger's watch pairing guide](https://sisik.eu/blog/android/bugjaeger/connect-watch) and [Wear Installer 2 help](https://freepoc.org/wear-installer-2-help-page/) for current app-specific screens.

### With a PC

Install [Android SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools). With **Pair new device** open on the watch:

```sh
adb pair WATCH_IP:PAIRING_PORT
# Enter the six-digit code displayed on the watch.
```

Return to the main **Wireless debugging** screen, then use its other port:

```sh
adb connect WATCH_IP:CONNECTION_PORT
adb -s WATCH_IP:CONNECTION_PORT install -r Cascade-wear-v2.0.0.apk
```

**Pairing details matter:**

- The pairing code and pairing port change each time **Pair new device** is opened. Always read the current values.
- Installation uses the **other port on the main Wireless debugging screen**, not the pairing port. The connection port can also change after reconnecting.
- Keep the watch screen on during pairing and installation; watch power management can suspend Wi-Fi when the screen sleeps.
- Use wireless ADB for Pixel Watch. Its standard charging cable is not a normal USB ADB installation connection.
- Disable wireless debugging after installation if you no longer need it.

Reference: [Android's Wear OS Wi-Fi debugging instructions](https://developer.android.com/training/wearables/get-started/debug-wifi).

## Building from source

Modules: `wear/` and `phone/` are thin launchers; `core/` is the Android library containing simulation, sprite rendering, sensors, input and feedback.

- JDK 17, Android SDK 36; minSdk 30 and targetSdk 36.
- Gradle wrapper 9.5.0, AGP 9.3.0 with built-in Kotlin.
- Root buildscript pins `org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10` for the existing offline cache.
- No additional application dependencies. Android library support comes from the same cached AGP distribution.
- Set `sdk.dir=/path/to/Android/Sdk` in the untracked `local.properties`.
- Release signing expects `~/.android/debug.keystore`, alias `androiddebugkey`, store/key password `android`.

With the Android SDK and Gradle dependencies already cached:

```sh
./gradlew --offline assembleRelease
```

Outputs:

```text
phone/build/outputs/apk/release/phone-release.apk
wear/build/outputs/apk/release/wear-release.apk
```

Both release variants enable R8 minification and resource shrinking. No emulator is required for building.

## How the physics works

The tray is a two-dimensional projection of solid spheres. Radii vary ±10%; mass scales with radius cubed, and rotational inertia is `2/5 mr²`. A fixed 240 Hz step (four substeps at 60 Hz) and a frame accumulator separate physics timing from display refresh rate. A uniform grid finds neighbouring beads without an all-pairs scan; contact arrays are preallocated.

Each substep performs 16 sequential impulse iterations. Restitution is 0.38 for distinct impacts; Coulomb friction couples tangential velocity and spin. Contact rolling resistance dissipates motion. Separate positional correction removes overlap without adding bounce. A conservative per-substep travel cap is an emergency guard against tunnelling, not full swept continuous collision detection. Quiet contacting beads sleep; meaningful contact motion, changes in tilt and touch wake them.

A low-pass gravity estimate provides tilt; the residual accelerometer signal produces shake inertia. Fingers are velocity-carrying kinematic circles. Phone corner radii come from `RoundedCorner` on API 31+ with a fallback radius. Per-sphere baked lighting stays fixed while its subtle internal texture rotates.

Feedback uses impact impulses rather than contact counts. Static support forces do not generate clicks. Sound events are rate-limited and small impacts use a softer, longer-decaying sample. Haptics additionally require a strong wall impact or throw impact, with a global cooldown and budget.

This is a stylized shallow-tray model, not a full 3D granular solver. Device feel, maximum-count performance and haptic strength should be evaluated on real hardware; the release build does not imply device testing.
