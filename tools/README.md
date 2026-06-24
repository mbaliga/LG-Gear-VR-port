# Phase 0 — prove the headset works (on the Steam Deck)

Goal: confirm the R100 enumerates, switch its panels on, and read tracking —
on a known-good Linux + DisplayPort-Alt-Mode machine before we touch Android.

## On the Steam Deck

1. Switch to **Desktop Mode** (Steam button → Power → Switch to Desktop).
2. If you've never set a sudo password: open **Konsole** and run `passwd`, set one.
3. Plug the headset into the Deck's **USB-C port** with its cable.
4. Check it enumerates:
   ```bash
   lsusb | grep -i 1004
   ```
   You want a line with `1004:6374` (LG Electronics). Also worth a look:
   ```bash
   sudo dmesg | tail -30      # watch for a new DisplayPort/DRM connector + the USB HID device
   ```
5. Grab this script onto the Deck (clone the repo, or copy `r100_wake.py` over) and run:
   ```bash
   sudo python3 r100_wake.py
   ```

## What to look for

- **Panels light up** when "VR App Start" is sent → Phase 0 win. 🎉
- The script then prints **button** presses (OK / Back) and **IMU** gyro/accel.
- Move the headset around: the gyro/accel numbers should react.

## If something's off, tell me:

- `lsusb` output (does `1004:6374` appear?).
- Whether `dmesg` shows a **new display/DRM connector** appearing when you plug in
  (this confirms the DisplayPort-Alt-Mode video path is alive).
- The script's output — especially any `[new report type N]` lines and whether
  IMU/button lines appear.
- Whether the panels physically turned on / showed anything.

No dependencies to install — it talks to `/dev/hidrawN` directly. If auto-detect
fails but `lsusb` shows the device, find the node and pass it explicitly:
```bash
ls -l /dev/hidraw*        # then:
sudo python3 r100_wake.py /dev/hidraw3
```
