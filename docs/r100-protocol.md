# LG 360 VR (R100) — How it works & protocol notes

This documents what the headset is and how to drive it from a non-G5 Android
phone. Sources are reverse-engineering efforts by the community (OpenHMD's
`LG-R100` branch, the XDA threads, the NURDspace info page, and the `m-sja/r100`
repo). Everything here should be treated as "best current understanding" and
corrected as we test on real hardware.

## What the device actually is

The "LG Gear VR" is really the **LG 360 VR, model R100**. Unlike Samsung Gear VR
(where the phone *is* the display, slotted into the goggles), the R100 has its
**own two built-in displays** and connects to the phone by a **USB-C cable**.
The phone does all the compute and rendering; the headset is essentially:

- An external **DisplayPort monitor** (the two panels behind one DP link), plus
- A **USB HID device** that reports the IMU (head tracking) and the two buttons.

Both of those travel over the single USB-C cable at once using **USB-C
DisplayPort Alt Mode** (video lanes) + **USB 2.0** (HID data) on the same connector.

It was locked to the LG G5 not by deep DRM but because (a) the G5 was one of the
few phones that did DP Alt Mode video-out, and (b) LG's app sent a specific HID
command to switch the panels/backlight on. Both of those are reproducible.

## The two data paths

### 1. Video — DisplayPort Alt Mode (the hard requirement)

- The panels appear to the host as a **single DisplayPort display at 1440×960**.
- It's a side-by-side stereo layout: **720×960 per eye**.
- For an Android phone to send video, **the phone must support USB-C
  DisplayPort Alt Mode video output.** This is the make-or-break hardware gate.
  Many phones (incl. most non-flagship and many Pixels) do **not** have DP Alt
  Mode and physically cannot drive the panels — no software can fix that.
- When DP Alt Mode works, Android exposes the headset as a **secondary display**,
  which an app drives with the `Presentation` API.

### 2. Sensors & buttons — USB HID

- USB IDs: **VID `0x1004` (LG Electronics), PID `0x6374`**.
- Enumerates as **"LGE Custom Human interface"** (a vendor HID interface),
  reachable on Linux as `/dev/hidraw0`, and on Android via the **USB Host API**
  (`android.hardware.usb`, interrupt transfers — no root required).
- The host must send HID output reports to wake the panels and start the sensors,
  then read input reports for IMU + buttons.

## The magic init sequence (verbatim from OpenHMD `LG-R100`)

All commands are HID writes with **report ID `0x03`**, a length/sub-command byte,
then an ASCII payload. Exact bytes:

| Purpose      | Len | Bytes (hex)                                          | ASCII           |
|--------------|-----|------------------------------------------------------|-----------------|
| Start device | 14  | `03 0C 56 52 20 41 70 70 20 53 74 61 72 74`          | `VR App Start`  |
| Keep alive   | 15  | `03 0D 53 6C 65 65 70 20 44 69 73 61 62 6C 65`       | `Sleep Disable` |
| Start accel  | 10  | `03 08 41 63 63 65 6C 20 4F 6E`                      | `Accel On`      |
| Start gyro   | 9   | `03 07 47 79 72 6F 20 4F 6E`                         | `Gyro On`       |

- **`VR App Start`** is what the G5's app sent to switch the displays/backlight
  on. This is the key to "turning the headset on" from any host.
- **`Sleep Disable`** is the keep-alive: it tells the headset to ignore the
  proximity sensor and keep tracking. Re-send periodically so it doesn't sleep.
- **Important:** OpenHMD's working driver sends **only** `start_device` then
  `keep_alive` — the `Accel On` / `Gyro On` writes are **commented out**, so the
  IMU appears to stream automatically once the device starts. Keep those two as
  fallbacks if no sensor reports arrive.

Other firmware commands exist in the dump (brightness, backlight, LCD pattern
test, reboot, shutdown, compass/proximity, self-tests, calibration) — same
`03`-prefixed ASCII form. Calibration/shutdown ones are destructive; leave alone.

## Input reports (read from the HID interrupt-in endpoint)

`buffer[0]` is the report ID / message type:

| ID  | Meaning                                            |
|-----|----------------------------------------------------|
| 0   | NULL / empty                                       |
| 1   | unknown1 (proximity-related debug)                 |
| 2   | **buttons**                                        |
| 3,4 | debug text                                         |
| 5   | **sensors (IMU)**                                  |
| 32,33 | debug sequences                                  |
| 101,255 | unknown                                        |

### Buttons (report ID 2), value in `buffer[1]`

- **OK / "A" button:** `0x01` = pressed, `0x04` = released
- **Back / "B" button:** `0x02` = pressed, `0x03` = released

### IMU (report ID 5) — exact layout

Packet is **31 or 32 bytes**. After the 1-byte report ID, skip **1 more byte**,
then read **six little-endian IEEE-754 floats** (4 bytes each):

```
offset 0      : report ID = 5
offset 1      : skipped (unknown)
offset 2..13  : gyro  x, y, z   (3x float32 LE)
offset 14..25 : accel x, y, z   (3x float32 LE)
offset 26..   : unknown byte + counter + unknown (unused)
```

Then OpenHMD applies these corrections before sensor fusion:

```
accel:  x = +x,      y = +y,      z = -z
gyro:   x = x*4.0,   y = y*4.0,   z = -(z*4.0)
```

The tick/dt handling in OpenHMD's branch is a rough WIP (it doesn't actually read
a tick out of the packet — `tick_delta` is hardcoded to ~200). Orientation was
flagged as needing calibration/drift correction. Expect to tune fusion ourselves;
a fixed sample dt (~5 ms / 200 Hz) is a reasonable starting assumption.

## Display geometry (OpenHMD's measured/approx values)

- hres 1440 × vres 960 (720×960 per eye, side-by-side)
- FOV ≈ 80°, lens separation ≈ 63.5 mm, lens vertical pos ≈ 20 mm
- Panels are physically rotated — OpenHMD multiplies the eye projection matrices
  by a 90° flip. Our renderer will need an equivalent rotation.

## Plan to run on a regular Android phone

1. **Gate check — DP Alt Mode.** Confirm the phone can output video over USB-C
   (test with any USB-C→HDMI adapter or monitor). If not, this phone can't drive
   the panels. *Everything downstream depends on this.*
2. **USB HID handshake app.** Use the USB Host API to open VID/PID `1004:6374`,
   claim the HID interface, send `VR App Start` + start the keep-alive loop +
   enable accel/gyro. Displays should light up.
3. **Read tracking.** Poll input reports; parse IMU (orientation via sensor
   fusion) and the two buttons.
4. **Stereo renderer.** Open a `Presentation` on the headset's secondary display
   and render a 1440×960 side-by-side 360 view that responds to head orientation.

## Hardware gotchas learned on real hardware

- **DisplayPort Alt Mode does NOT pass through USB hubs.** The DP lanes are
  point-to-point on the USB-C connector; a hub only carries USB data. The headset
  must be plugged **directly into the host's USB-C port** for the panels to get
  video. Through a hub you still get the USB HID interface (sensors/buttons), but
  the displays will never light. (Confirmed on Steam Deck via two chained hubs.)
- **Always let `r100_wake.py` auto-detect the node** (run with no argument) or it
  may be pointed at the wrong HID device. On a Steam Deck the headset showed up as
  `hidraw7` ("LGE Custom Human interface", `1004:6374`), while `hidraw3` was the
  Deck's own FTS3528 touchscreen. Writing the wake command to the wrong node looks
  like it succeeds but does nothing.

## Open questions / TODO (to resolve on real hardware)

- [ ] Confirm whether the HID handshake must happen **before** DP Alt Mode video
      is accepted, or the panels light regardless once `VR App Start` is sent.
- [ ] Confirm the keep-alive interval (start by re-sending `Sleep Disable` every
      ~1 s and back off once we know the real timeout).
- [ ] Confirm target phone supports DP Alt Mode (the hard gate — see step 1).
- [ ] Verify IMU float endianness/units on-device and tune sensor fusion.
- [ ] Confirm whether `Accel On` / `Gyro On` are needed (OpenHMD didn't send them).

## Sources

- OpenHMD `LG-R100` branch: https://github.com/OpenHMD/OpenHMD/tree/LG-R100/src/drv_lgr100
- OpenHMD journey thread (XDA): https://xdaforums.com/t/lg-360-vr-openhmd-journey-linux-mac-windows-bsd-support-and-steamvr.3810873/
- LG 360 VR PC adapter thread (XDA): https://xdaforums.com/t/lg-360-vr-pc-adapter.3743642/
- NURDspace info collection: https://nurdspace.nl/LG_R100_info_collection
- m-sja/r100 captures: https://github.com/m-sja/r100
