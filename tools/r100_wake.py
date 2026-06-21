#!/usr/bin/env python3
"""
r100_wake.py - Wake up an LG 360 VR (R100) and read its sensors/buttons.

Phase 0 hardware test. Pure standard-library Python (no pip installs), talks to
the headset's HID interface directly via /dev/hidrawN. Meant to run on the
Steam Deck (Desktop Mode terminal) or any Linux box whose USB-C does
DisplayPort Alt Mode.

What it does:
  1. Finds the hidraw node for the R100 (USB 1004:6374).
  2. Sends "VR App Start" -> this is what the LG G5's app sent to switch the
     two panels + backlight on.
  3. Keeps re-sending "Sleep Disable" (keep-alive) so it doesn't doze off.
  4. Reads input reports and prints buttons (OK / Back) and decoded IMU data.

Usage:
    sudo python3 r100_wake.py            # auto-detect the headset
    sudo python3 r100_wake.py /dev/hidraw3   # or point it at a node

(Need sudo because /dev/hidraw* is root-owned. On the Steam Deck, set a
password first with `passwd` if you haven't.)

If the panels light up, Phase 0 is a success. Ctrl-C to quit.
"""

import os
import sys
import glob
import time
import select
import struct

VID = 0x1004  # LG Electronics
PID = 0x6374  # LGE Custom Human interface (the R100)

# HID output reports, verbatim from OpenHMD's LG-R100 driver.
# Format: report-id 0x03, length byte, then an ASCII command.
START_DEVICE = bytes([0x03, 0x0C]) + b"VR App Start"    # turn the headset on
KEEP_ALIVE   = bytes([0x03, 0x0D]) + b"Sleep Disable"   # ignore proximity, stay awake
START_ACCEL  = bytes([0x03, 0x08]) + b"Accel On"        # fallback if no IMU stream
START_GYRO   = bytes([0x03, 0x07]) + b"Gyro On"         # fallback if no IMU stream

KEEP_ALIVE_INTERVAL = 1.0  # seconds; tune once we know the real sleep timeout

# Input report type IDs (buffer[0]) from the driver.
IRQ_BUTTONS = 2
IRQ_SENSORS = 5

# Button states (buffer[1]) for report id 2.
BTN_OK_ON, BTN_BACK_ON, BTN_BACK_OFF, BTN_OK_OFF = 1, 2, 3, 4


def find_hidraw():
    """Return the /dev/hidrawN path for the R100, or None."""
    target = f"{VID:04X}:{PID:04X}".upper()
    for node in sorted(glob.glob("/sys/class/hidraw/hidraw*")):
        uevent = os.path.join(node, "device", "uevent")
        try:
            with open(uevent) as f:
                text = f.read().upper()
        except OSError:
            continue
        # HID_ID looks like: HID_ID=0003:00001004:00006374
        if target.replace(":", "") in text.replace(":", ""):
            return "/dev/" + os.path.basename(node)
    return None


def decode_imu(buf):
    """Report id 5: skip report id + 1 unknown byte, then 6 little-endian
    float32 (gyro xyz, accel xyz). Apply OpenHMD's axis corrections."""
    if len(buf) < 26:
        return None
    gx, gy, gz, ax, ay, az = struct.unpack_from("<6f", buf, 2)
    gx, gy, gz = gx * 4.0, gy * 4.0, -(gz * 4.0)
    az = -az
    return (gx, gy, gz, ax, ay, az)


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else find_hidraw()
    if not path:
        print(f"Could not find the R100 ({VID:04X}:{PID:04X}).")
        print("Is it plugged in? Try: lsusb | grep -i 1004")
        print("If it shows up but this doesn't find it, pass the node explicitly,")
        print("e.g.  sudo python3 r100_wake.py /dev/hidraw3")
        sys.exit(1)

    print(f"Opening {path} ...")
    try:
        fd = os.open(path, os.O_RDWR | os.O_NONBLOCK)
    except PermissionError:
        print(f"Permission denied on {path}. Re-run with sudo.")
        sys.exit(1)

    def send(report, name):
        try:
            os.write(fd, report)
            print(f"  -> sent {name}: {report.hex(' ')}")
        except OSError as e:
            print(f"  !! failed to send {name}: {e}")

    print("Waking headset...")
    send(START_DEVICE, "VR App Start")
    time.sleep(0.1)
    # OpenHMD left these commented out, but on a cold device the sensors may need
    # to be explicitly switched on. Send them and see if report-id-5 starts.
    send(START_ACCEL, "Accel On")
    time.sleep(0.05)
    send(START_GYRO, "Gyro On")
    time.sleep(0.05)
    send(KEEP_ALIVE, "Sleep Disable")

    print("\nIf the panels lit up: SUCCESS. 🎉  Reading input (Ctrl-C to stop)...\n")

    last_keepalive = time.monotonic()
    last_imu_print = 0.0
    last_heartbeat = time.monotonic()
    total_reports = 0
    seen_types = set()

    try:
        while True:
            now = time.monotonic()
            if now - last_keepalive >= KEEP_ALIVE_INTERVAL:
                send(KEEP_ALIVE, "Sleep Disable")
                last_keepalive = now

            r, _, _ = select.select([fd], [], [], 0.2)
            if not r:
                # Heartbeat so we can tell "device is silent" from "script is stuck".
                if now - last_heartbeat >= 2.0:
                    print(f"...no input reports yet (received {total_reports} so far). "
                          f"Move the headset; if still nothing, the device isn't streaming.")
                    last_heartbeat = now
                continue
            try:
                buf = os.read(fd, 256)
            except BlockingIOError:
                continue
            if not buf:
                continue

            total_reports += 1
            t = buf[0]
            if t not in seen_types:
                seen_types.add(t)
                print(f"[new report type {t}] len={len(buf)} bytes: {buf[:min(len(buf),32)].hex(' ')}")

            if t == IRQ_BUTTONS and len(buf) > 1:
                st = buf[1]
                label = {BTN_OK_ON: "OK pressed", BTN_OK_OFF: "OK released",
                         BTN_BACK_ON: "BACK pressed", BTN_BACK_OFF: "BACK released"}.get(st, f"state {st}")
                print(f"BUTTON: {label}")
            elif t == IRQ_SENSORS:
                imu = decode_imu(buf)
                if imu and now - last_imu_print > 0.1:  # throttle to ~10 Hz
                    gx, gy, gz, ax, ay, az = imu
                    print(f"IMU gyro=({gx:+7.2f},{gy:+7.2f},{gz:+7.2f})  "
                          f"accel=({ax:+6.2f},{ay:+6.2f},{az:+6.2f})")
                    last_imu_print = now
    except KeyboardInterrupt:
        print("\nBye.")
    finally:
        os.close(fd)


if __name__ == "__main__":
    main()
