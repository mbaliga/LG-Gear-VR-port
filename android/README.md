# R100 VR — Android app (target: RedMagic 11 Pro)

Goal: use the LG 360 VR (R100) with a regular Android phone that has USB-C
DisplayPort Alt Mode (the RedMagic 11 Pro does).

## Phases

- **Phase 1 (this build):** USB Host handshake + live tracking.
  - Detects the R100 (`1004:6374`) over the USB Host API.
  - Sends `VR App Start` (lights the panels) + `Sleep Disable` keep-alive,
    enables accel/gyro — the same sequence as `tools/r100_wake.py`.
  - Reads IMU (report id 5) and buttons (report id 2) and shows them on the
    phone screen. No root.
- **Phase 2 (next):** render a side-by-side stereo image onto the headset's
  DisplayPort output via the `Presentation` API (1440×960, per-eye 90° rotation,
  lens distortion), driven by head tracking — for big-screen flat media.

## Build

```bash
cd android
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

Requires Android SDK platform 34 + build-tools 34.0.0 (JDK 17+).

## Install / test on the phone

1. Enable installing unknown apps for your file manager/browser, then open the
   APK to sideload it.
2. Plug the R100 **directly** into the phone's USB-C port.
3. The app auto-launches (or open "R100 VR"). Grant the USB permission prompt.
4. The headset **backlight should turn on**, and the screen should show live
   `gyro`/`accel` values that change as you move the headset, plus button events.

Protocol details: `../docs/r100-protocol.md`.

## Status / known limitation (RedMagic 11 Pro)

- **Phase 1 works great:** wake (backlight on), head tracking (IMU verified
  against gravity), and both buttons are solid and stable over USB.
- **Phase 2 (display) is blocked by the phone, not the app.** The R100 enumerates
  as a 1440×960 DisplayPort display and the Presentation renderer *does* start on
  it ("VR view started on: HDMI Screen"), but the RedMagic 11 Pro will not **hold**
  the DP Alt Mode link — it drops within seconds and the USB-C re-enumerates in a
  loop. Ruled out in testing:
  - re-sending `VR App Start` (added a re-wake cooldown — still drops)
  - screen-sleep (forced `FLAG_KEEP_SCREEN_ON` — still drops)
  - app grabbing the display late (auto-grab on display-add — still drops)
  - cable (the R100's cable is captive; can't swap)
  - **Decisive:** the RedMagic's *own* screen-projection to this display also
    drops "after a bit", with our app closed — so it's the phone's DP/USB-C
    behavior, most likely **insufficient power** for the headset's twin displays.
- **The same code path is expected to work on a phone with a stable, higher-power
  DP Alt Mode implementation.** On the Steam Deck the DP link is rock-solid
  (`docs/steamvr-on-deck.md`), which is the recommended host for actual viewing.
