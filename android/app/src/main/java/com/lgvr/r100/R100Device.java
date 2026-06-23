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
 * All USB traffic happens on a SINGLE I/O thread (like OpenHMD): reads and the
 * occasional write are interleaved on one thread so they never contend on the
 * same UsbDeviceConnection (concurrent read+write was causing the -1 send
 * failures). Disconnect is detected by read-starvation, not by send failures.
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

    private static final int IRQ_BUTTONS = 2;
    private static final int IRQ_SENSORS = 5;
    private static final int BTN_OK_ON = 1, BTN_BACK_ON = 2, BTN_BACK_OFF = 3, BTN_OK_OFF = 4;

    private static final long KEEP_ALIVE_MS = 5000; // resend "Sleep Disable" occasionally
    private static final long DATA_TIMEOUT_MS = 3000; // no reports this long => link gone

    public interface Listener {
        void onLog(String msg);
        void onImu(float gx, float gy, float gz, float ax, float ay, float az);
        void onButton(String name, boolean pressed);
        void onClosed();
        /** Called once when the link drops unexpectedly (vs. an intentional close()). */
        void onConnectionLost();
    }

    private final UsbManager manager;
    private final UsbDevice device;
    private final Listener listener;

    private UsbDeviceConnection connection;
    private UsbInterface iface;
    private UsbEndpoint epIn, epOut;
    private int interfaceNumber;

    private volatile boolean running = false;
    private volatile boolean closed = false;
    private Thread ioThread;

    public R100Device(UsbManager manager, UsbDevice device, Listener listener) {
        this.manager = manager;
        this.device = device;
        this.listener = listener;
    }

    public boolean open() {
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
        startIo();
        return true;
    }

    /** Intentional close (user/teardown): does NOT fire onConnectionLost. */
    public synchronized void close() {
        if (closed) return;
        closed = true;
        running = false;
        if (ioThread != null) ioThread.interrupt();
        teardownConnection();
        listener.onClosed();
    }

    private synchronized void lost() {
        if (closed) return;
        closed = true;
        running = false;
        teardownConnection();
        listener.onConnectionLost();
    }

    private void teardownConnection() {
        UsbDeviceConnection c = connection;
        connection = null;
        try {
            if (c != null) {
                if (iface != null) c.releaseInterface(iface);
                c.close();
            }
        } catch (Exception ignored) {}
    }

    /** Only ever called from the I/O thread, so it never contends with reads. */
    private int send(byte[] report, String name) {
        UsbDeviceConnection c = connection;
        if (c == null) return -1;
        int r;
        try {
            if (epOut != null) {
                r = c.bulkTransfer(epOut, report, report.length, 1000);
            } else {
                int value = 0x0200 | (report[0] & 0xff); // SET_REPORT (Output | reportId)
                r = c.controlTransfer(0x21, 0x09, value, interfaceNumber, report, report.length, 1000);
            }
        } catch (Exception e) {
            r = -1;
        }
        if (name != null) log((r >= 0 ? "→ sent " : "✗ failed ") + name + " (" + r + ")");
        return r;
    }

    private void sendWithRetry(byte[] report, String name, int tries) {
        for (int i = 0; i < tries; i++) {
            if (send(report, i == 0 ? name : null) >= 0) return;
            sleep(60);
        }
    }

    private void startIo() {
        ioThread = new Thread(() -> {
            sleep(150); // let the interface settle
            // Handshake (sent once, like OpenHMD). VR App Start is retried.
            sendWithRetry(START_DEVICE, "VR App Start", 4);
            sleep(60); send(ACCEL_ON, "Accel On");
            sleep(40); send(GYRO_ON, "Gyro On");
            sleep(40); send(KEEP_ALIVE, "Sleep Disable");
            log("Handshake sent — the headset backlight should be on.");

            byte[] buf = new byte[64];
            long now = System.currentTimeMillis();
            long lastData = now, lastKa = now;

            while (running) {
                UsbDeviceConnection c = connection;
                if (c == null) break;
                int n;
                try {
                    n = c.bulkTransfer(epIn, buf, buf.length, 200);
                } catch (Exception e) {
                    break;
                }
                now = System.currentTimeMillis();
                if (n > 0) {
                    lastData = now;
                    try { parse(buf, n); } catch (Exception ignored) {}
                }
                if (now - lastKa >= KEEP_ALIVE_MS) {
                    send(KEEP_ALIVE, null); // best-effort, quiet
                    lastKa = now;
                }
                if (now - lastData >= DATA_TIMEOUT_MS) {
                    log("No reports for " + (DATA_TIMEOUT_MS / 1000) + "s — reconnecting…");
                    lost();
                    return;
                }
            }
        }, "r100-io");
        ioThread.start();
    }

    private void parse(byte[] buf, int n) {
        int type = buf[0] & 0xff;
        if (type == IRQ_BUTTONS && n > 1) {
            switch (buf[1] & 0xff) {
                case BTN_OK_ON:    listener.onButton("OK", true); break;
                case BTN_OK_OFF:   listener.onButton("OK", false); break;
                case BTN_BACK_ON:  listener.onButton("BACK", true); break;
                case BTN_BACK_OFF: listener.onButton("BACK", false); break;
            }
        } else if (type == IRQ_SENSORS && n >= 25) {
            ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
            // offset 0 = report id (5); skip only that byte, then 6 float32 LE:
            // gyro xyz at 1/5/9, accel xyz at 13/17/21.
            float gx = bb.getFloat(1),  gy = bb.getFloat(5),  gz = bb.getFloat(9);
            float ax = bb.getFloat(13), ay = bb.getFloat(17), az = bb.getFloat(21);
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
