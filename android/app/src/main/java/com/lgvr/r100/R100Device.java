package com.lgvr.r100;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Talks to the LG 360 VR (R100) over USB (Android USB Host API).
 *
 * Mirrors what tools/r100_wake.py and OpenHMD's driver do:
 *   - send "VR App Start" to switch the panels/backlight on
 *   - re-send "Sleep Disable" as a keep-alive
 *   - read input reports: id 5 = IMU (gyro/accel), id 2 = buttons
 *
 * Protocol details: see docs/r100-protocol.md.
 */
public class R100Device {

    public static final int VID = 0x1004; // LG Electronics
    public static final int PID = 0x6374; // LGE Custom Human interface

    // HID output reports: report-id 0x03, length byte, ASCII command.
    private static final byte[] START_DEVICE =
            {0x03, 0x0C, 'V', 'R', ' ', 'A', 'p', 'p', ' ', 'S', 't', 'a', 'r', 't'};
    private static final byte[] KEEP_ALIVE =
            {0x03, 0x0D, 'S', 'l', 'e', 'e', 'p', ' ', 'D', 'i', 's', 'a', 'b', 'l', 'e'};
    private static final byte[] ACCEL_ON =
            {0x03, 0x08, 'A', 'c', 'c', 'e', 'l', ' ', 'O', 'n'};
    private static final byte[] GYRO_ON =
            {0x03, 0x07, 'G', 'y', 'r', 'o', ' ', 'O', 'n'};

    // Input report types (buffer[0]).
    private static final int IRQ_BUTTONS = 2;
    private static final int IRQ_SENSORS = 5;

    // Button states (buffer[1]) for report id 2.
    private static final int BTN_OK_ON = 1, BTN_BACK_ON = 2, BTN_BACK_OFF = 3, BTN_OK_OFF = 4;

    public interface Listener {
        void onLog(String msg);
        void onImu(float gx, float gy, float gz, float ax, float ay, float az);
        void onButton(String name, boolean pressed);
        void onClosed();
    }

    private final UsbManager manager;
    private final UsbDevice device;
    private final Listener listener;

    private UsbDeviceConnection connection;
    private UsbInterface iface;
    private UsbEndpoint epIn, epOut;
    private int interfaceNumber;

    private volatile boolean running = false;
    private Thread readerThread, keepAliveThread;

    public R100Device(UsbManager manager, UsbDevice device, Listener listener) {
        this.manager = manager;
        this.device = device;
        this.listener = listener;
    }

    /** Open the device, send the wake handshake, start reading. Returns false on failure. */
    public boolean open() {
        // Find the HID interface and its interrupt endpoints.
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getInterfaceClass() != UsbConstants.USB_CLASS_HID) continue;
            UsbEndpoint in = null, out = null;
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_INT) continue;
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) in = ep;
                else out = ep;
            }
            if (in != null) {
                iface = intf;
                interfaceNumber = intf.getId();
                epIn = in;
                epOut = out;
                break;
            }
        }
        if (iface == null || epIn == null) {
            log("No HID interface with an interrupt-IN endpoint found.");
            return false;
        }

        connection = manager.openDevice(device);
        if (connection == null) {
            log("openDevice() failed (permission?).");
            return false;
        }
        if (!connection.claimInterface(iface, true)) {
            log("claimInterface() failed.");
            connection.close();
            connection = null;
            return false;
        }

        log("Opened. epIn=" + epIn.getAddress()
                + (epOut != null ? (" epOut=" + epOut.getAddress()) : " (no OUT ep, using control)"));

        running = true;
        startReader();

        // Wake sequence (same as r100_wake.py).
        send(START_DEVICE, "VR App Start");
        sleep(80);
        send(ACCEL_ON, "Accel On");
        sleep(40);
        send(GYRO_ON, "Gyro On");
        sleep(40);
        send(KEEP_ALIVE, "Sleep Disable");
        startKeepAlive();
        log("Handshake sent — the headset backlight should be on.");
        return true;
    }

    public void close() {
        running = false;
        if (keepAliveThread != null) keepAliveThread.interrupt();
        if (readerThread != null) readerThread.interrupt();
        try {
            if (connection != null) {
                if (iface != null) connection.releaseInterface(iface);
                connection.close();
            }
        } catch (Exception ignored) {
        }
        connection = null;
        listener.onClosed();
    }

    /** Send a HID output report; prefers the interrupt-OUT endpoint, falls back to control SET_REPORT. */
    private synchronized int send(byte[] report, String name) {
        if (connection == null) return -1;
        int r;
        if (epOut != null) {
            r = connection.bulkTransfer(epOut, report, report.length, 1000);
        } else {
            // SET_REPORT: bmRequestType=0x21, bRequest=0x09, wValue=(Output<<8)|reportId, wIndex=interface
            int value = 0x0200 | (report[0] & 0xff);
            r = connection.controlTransfer(0x21, 0x09, value, interfaceNumber,
                    report, report.length, 1000);
        }
        if (name != null) log((r >= 0 ? "→ sent " : "✗ failed ") + name + " (" + r + ")");
        return r;
    }

    private void startKeepAlive() {
        keepAliveThread = new Thread(() -> {
            while (running) {
                sleep(1000);
                if (!running) break;
                send(KEEP_ALIVE, null); // quiet keep-alive
            }
        }, "r100-keepalive");
        keepAliveThread.start();
    }

    private void startReader() {
        readerThread = new Thread(() -> {
            byte[] buf = new byte[64];
            while (running) {
                int n = connection.bulkTransfer(epIn, buf, buf.length, 500);
                if (n <= 0) continue;
                parse(buf, n);
            }
        }, "r100-reader");
        readerThread.start();
    }

    private void parse(byte[] buf, int n) {
        int type = buf[0] & 0xff;
        if (type == IRQ_BUTTONS && n > 1) {
            int st = buf[1] & 0xff;
            switch (st) {
                case BTN_OK_ON:   listener.onButton("OK", true); break;
                case BTN_OK_OFF:  listener.onButton("OK", false); break;
                case BTN_BACK_ON: listener.onButton("BACK", true); break;
                case BTN_BACK_OFF:listener.onButton("BACK", false); break;
            }
        } else if (type == IRQ_SENSORS && n >= 25) {
            ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
            // offset 0 = report id (5); OpenHMD skips ONLY that byte, then reads
            // 6 float32 LE: gyro xyz at 1/5/9, accel xyz at 13/17/21.
            float gx = bb.getFloat(1),  gy = bb.getFloat(5),  gz = bb.getFloat(9);
            float ax = bb.getFloat(13), ay = bb.getFloat(17), az = bb.getFloat(21);
            // OpenHMD corrections
            gx *= 4f; gy *= 4f; gz = -(gz * 4f);
            az = -az;
            listener.onImu(gx, gy, gz, ax, ay, az);
        }
    }

    private void log(String m) { listener.onLog(m); }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
