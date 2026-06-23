package com.lgvr.r100;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import java.util.Locale;

/**
 * Phase 1 app: detect the LG 360 VR (R100), send the wake handshake (lights the
 * panels), and show live head tracking + buttons on the phone screen.
 *
 * This is the phone-side equivalent of tools/r100_wake.py. The stereo renderer
 * onto the headset's DisplayPort output comes in a later phase.
 */
public class MainActivity extends Activity implements R100Device.Listener {

    private static final String ACTION_USB_PERMISSION = "com.lgvr.r100.USB_PERMISSION";

    private UsbManager usbManager;
    private R100Device r100;

    private TextView statusView, trackingView, logView;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuf = new StringBuilder();

    // recenter offset (simple yaw/pitch/roll zeroing handled later; placeholder now)
    private volatile float lastGx, lastGy, lastGz, lastAx, lastAy, lastAz;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted && dev != null) {
                    log("Permission granted.");
                    connectTo(dev);
                } else {
                    log("USB permission denied.");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (dev != null && dev.getVendorId() == R100Device.VID
                        && dev.getProductId() == R100Device.PID) {
                    log("Headset detached.");
                    if (r100 != null) { r100.close(); r100 = null; }
                    setStatus("Headset unplugged.");
                }
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        statusView = findViewById(R.id.status);
        trackingView = findViewById(R.id.tracking);
        logView = findViewById(R.id.log);
        ((Button) findViewById(R.id.btnConnect)).setOnClickListener(v -> findAndConnect());
        ((Button) findViewById(R.id.btnLock)).setOnClickListener(v -> log("(recenter is a no-op in phase 1)"));

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        log("R100 VR phase-1. VID=0x1004 PID=0x6374.");
        // If launched by plugging the headset in, the device is in the intent.
        handleAttachIntent(getIntent());
        findAndConnect();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleAttachIntent(intent);
    }

    private void handleAttachIntent(Intent intent) {
        if (intent != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (dev != null) requestAndConnect(dev);
        }
    }

    private void findAndConnect() {
        for (UsbDevice dev : usbManager.getDeviceList().values()) {
            if (dev.getVendorId() == R100Device.VID && dev.getProductId() == R100Device.PID) {
                requestAndConnect(dev);
                return;
            }
        }
        setStatus("R100 not found. Plug it in (directly, not through a hub).");
    }

    private void requestAndConnect(UsbDevice dev) {
        if (usbManager.hasPermission(dev)) {
            connectTo(dev);
            return;
        }
        setStatus("Requesting USB permission…");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, intent, flags);
        usbManager.requestPermission(dev, pi);
    }

    private void connectTo(UsbDevice dev) {
        if (r100 != null) { r100.close(); r100 = null; }
        setStatus("Connecting to R100…");
        r100 = new R100Device(usbManager, dev, this);
        if (r100.open()) {
            setStatus("Connected. Headset awake — move it to see tracking.");
        } else {
            setStatus("Connect failed — see log below.");
            r100 = null;
        }
    }

    // ---- R100Device.Listener (called from background threads) ----

    @Override public void onLog(String msg) { log(msg); }

    @Override public void onImu(float gx, float gy, float gz, float ax, float ay, float az) {
        lastGx = gx; lastGy = gy; lastGz = gz; lastAx = ax; lastAy = ay; lastAz = az;
        ui.post(() -> trackingView.setText(String.format(Locale.US,
                "gyro:  %+7.2f %+7.2f %+7.2f\naccel: %+6.2f %+6.2f %+6.2f\nbuttons: (move/press to test)",
                lastGx, lastGy, lastGz, lastAx, lastAy, lastAz)));
    }

    @Override public void onButton(String name, boolean pressed) {
        log("BUTTON " + name + " " + (pressed ? "pressed" : "released"));
    }

    @Override public void onClosed() { log("Connection closed."); }

    // ---- UI helpers ----

    private void setStatus(String s) { ui.post(() -> statusView.setText(s)); }

    private void log(String m) {
        ui.post(() -> {
            logBuf.insert(0, m + "\n");
            if (logBuf.length() > 4000) logBuf.setLength(4000);
            logView.setText(logBuf.toString());
        });
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        if (r100 != null) r100.close();
    }
}
