package com.lgvr.r100;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
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
    private VrPresentation vr;

    private TextView statusView, trackingView, logView;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuf = new StringBuilder();

    private volatile boolean wantConnected = false; // should we auto-reconnect?
    private volatile boolean connecting = false;     // guard against double-connect

    private volatile float lastGx, lastGy, lastGz, lastAx, lastAy, lastAz;

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { ui.post(() -> autoEnterVrIfPossible()); }
        @Override public void onDisplayChanged(int id) { ui.post(() -> autoEnterVrIfPossible()); }
        @Override public void onDisplayRemoved(int id) {
            ui.post(() -> {
                if (vr != null) { vr.dismiss(); vr = null; log("External display removed."); }
            });
        }
    };

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {        @Override public void onReceive(Context context, Intent intent) {
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
                    wantConnected = false; // re-attach intent will reconnect us
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
        ((Button) findViewById(R.id.btnLock)).setOnClickListener(v -> log("(recenter comes with head-tracking)"));
        ((Button) findViewById(R.id.btnVr)).setOnClickListener(v -> enterVr());

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // Auto-start VR the moment the headset's display appears (it can drop
        // quickly if nothing claims it), and re-grab it if it blips.
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        dm.registerDisplayListener(displayListener, ui);

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        log("R100 VR phase-1. VID=0x1004 PID=0x6374.");
        // If launched by plugging the headset in, the device is in the intent;
        // otherwise scan for an already-connected one. Don't do both (that
        // double-connects and tears the first link down).
        if (!handleAttachIntent(getIntent())) {
            findAndConnect();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAttachIntent(intent); // re-attach (incl. after the headset re-enumerates)
    }

    private boolean handleAttachIntent(Intent intent) {
        if (intent != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (dev != null) { requestAndConnect(dev); return true; }
        }
        return false;
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
        if (connecting || r100 != null) return; // already connected/connecting
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
        if (connecting) return;
        connecting = true;
        try {
            if (r100 != null) { r100.close(); r100 = null; }
            setStatus("Connecting to R100…");
            R100Device d = new R100Device(usbManager, dev, this);
            if (d.open()) {
                r100 = d;
                wantConnected = true;
                setStatus("Connected. Headset awake — move it to see tracking.");
                autoEnterVrIfPossible();
            } else {
                setStatus("Connect failed — see log below.");
            }
        } finally {
            connecting = false;
        }
    }

    /** If the headset's display is present and we're not already showing, start VR. */
    private void autoEnterVrIfPossible() {
        if (vr != null) return;
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).length > 0) {
            enterVr();
        }
    }

    /** Push the stereo renderer onto the headset's external (DisplayPort) display. */
    private void enterVr() {
        if (vr != null) return; // already running
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        Display[] displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (displays.length == 0) {
            setStatus("No external display yet — keep the headset plugged in; VR auto-starts when it appears.");
            return;
        }
        Display target = displays[0];
        try {
            vr = new VrPresentation(this, target);
            vr.show();
            log("VR view started on: " + target.getName() + " (" +
                    target.getMode().getPhysicalWidth() + "x" + target.getMode().getPhysicalHeight() + ")");
            setStatus("VR running — look into the headset. (Test card; head-tracking next.)");
        } catch (Exception e) {
            log("Failed to start VR: " + e);
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

    @Override public void onConnectionLost() {
        ui.post(() -> {
            r100 = null;
            log("Connection lost.");
            if (wantConnected) {
                setStatus("Reconnecting…");
                ui.postDelayed(this::findAndConnect, 1200);
            }
        });
    }

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
        wantConnected = false;
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        try {
            ((DisplayManager) getSystemService(DISPLAY_SERVICE)).unregisterDisplayListener(displayListener);
        } catch (Exception ignored) {}
        if (vr != null) { vr.dismiss(); vr = null; }
        if (r100 != null) { r100.close(); r100 = null; }
    }
}
