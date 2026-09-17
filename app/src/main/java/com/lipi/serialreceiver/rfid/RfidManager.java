package com.lipi.serialreceiver.rfid;

import android.content.Context;
import android.util.Log;

import com.rscja.deviceapi.RFIDWithUHFA4;
import com.rscja.deviceapi.entity.UHFTAGInfo;
import com.rscja.deviceapi.exception.ConfigurationException;
import com.rscja.deviceapi.interfaces.IUHFInventoryCallback;

public class RfidManager {

    private static final String TAG = "RfidManager";

    public interface Listener {
        void onTagRead(String epc, String rssi);
        void onError(String message);
    }

    private RFIDWithUHFA4 reader;
    private Listener listener;

    private volatile boolean scanning = false;
    public enum LedColor {
        OFF,
        YELLOW,
        GREEN,
        RED
    }
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    // ============================================================
    // INIT
    // ============================================================

    public boolean init(Context context) {

        try {

            reader = RFIDWithUHFA4.getInstance();

        } catch (ConfigurationException e) {

            Log.e(
                    TAG,
                    "RFID module not supported on this device",
                    e
            );

            notifyError(
                    "RFID module not supported on this device: "
                            + e.getMessage()
            );

            return false;
        }

        if (reader == null) {

            notifyError("RFID reader instance is null");

            return false;
        }

        boolean ok = reader.init(context);

        if (!ok) {

            notifyError(
                    "Failed to initialize RFID reader"
            );

            return false;
        }

        // --------------------------------------------------------
        // Initially keep RFID scan LED OFF
        // --------------------------------------------------------

        try {
            setStatusLed(LedColor.OFF);
        } catch (Exception e) {
            Log.w(TAG, "Unable to switch RFID LED off", e);
        }

        // --------------------------------------------------------
        // Inventory callback
        // --------------------------------------------------------

        reader.setInventoryCallback(
                new IUHFInventoryCallback() {

                    @Override
                    public void callback(UHFTAGInfo tagInfo) {

                        if (tagInfo == null) {
                            return;
                        }

                        if (tagInfo.getEPC() == null) {
                            return;
                        }

                        if (listener != null) {

                            listener.onTagRead(
                                    tagInfo.getEPC(),
                                    tagInfo.getRssi()
                            );
                        }
                    }
                }
        );

        return true;
    }

    // ============================================================
    // START RFID
    // ============================================================

    public boolean startContinuousScan() {

        if (reader == null) {

            notifyError(
                    "RFID reader not initialized"
            );

            return false;
        }

        if (scanning) {
            return true;
        }

        try {

            boolean started =
                    reader.startInventoryTag();

            if (started) {

                scanning = true;

                //setStatusLed(LedColor.YELLOW);

                Log.d(
                        TAG,
                        "RFID scanning started - YELLOW"
                );

            } else {

                scanning = false;

                setStatusLed(LedColor.OFF);

                notifyError(
                        "Failed to start RFID scan"
                );
            }

            return started;

        } catch (Exception e) {

            scanning = false;

            try {
                setStatusLed(LedColor.OFF);
            } catch (Exception ignored) {
            }

            Log.e(
                    TAG,
                    "Error starting RFID scan",
                    e
            );

            notifyError(
                    "RFID scan error: "
                            + e.getMessage()
            );

            return false;
        }
    }

    // ============================================================
    // STOP RFID
    // ============================================================

    public boolean stopScan() {

        if (reader == null) {
            return false;
        }

        try {

            boolean stopped =
                    reader.stopInventory();

            scanning = false;

            // ---------------------------------------------
            // RFID SCAN LED OFF
            // ---------------------------------------------

            setStatusLed(LedColor.OFF);

            Log.d(
                    TAG,
                    "RFID scanning stopped - LED OFF"
            );

            return stopped;

        } catch (Exception e) {

            scanning = false;

            try {
                setStatusLed(LedColor.OFF);
            } catch (Exception ignored) {
            }

            Log.e(
                    TAG,
                    "Error stopping RFID scan",
                    e
            );

            return false;
        }
    }

    // ============================================================
    // IS SCANNING
    // ============================================================

    public boolean isScanning() {
        return scanning;
    }
// ============================================================
// STATUS LED
// ============================================================

    // ============================================================
// STATUS LED
// ============================================================

    public void setStatusLed(LedColor color) {

        if (reader == null) {
            return;
        }

        try {

            // Always turn everything OFF first
            reader.output1Off();
            reader.output2Off();
            reader.output3Off();
            reader.output4Off();

            switch (color) {

                case YELLOW:
                    // GPIO 1 + 2 + 3 + 4
                    reader.output1On();
                    reader.output2On();
                    reader.output3On();
                    reader.output4On();
                    break;

                case RED:
                    // GPIO 1 + 2 + 4
                    reader.output1On();
                    reader.output2On();
                    reader.output4On();
                    break;

                case GREEN:
                    // GPIO 1 + 2 + 3
                    reader.output1On();
                    reader.output2On();
                    reader.output3On();
                    break;

                case OFF:
                default:
                    // Already OFF
                    break;
            }

            Log.d(TAG, "Status LED: " + color);

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Failed to set status LED: " + color,
                    e
            );
        }
    }
    // ============================================================
    // RFID LED
    // ============================================================

    public void setRfidLed(boolean enabled) {

        if (reader == null) {
            return;
        }

        try {

            reader.rfidLedSwitch(enabled);

            Log.d(
                    TAG,
                    "RFID LED: " + enabled
            );

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Failed to control RFID LED",
                    e
            );
        }
    }

    // ============================================================
    // ANDROID / WORK LED
    // ============================================================

    public void setAndroidLed(boolean enabled) {

        if (reader == null) {
            return;
        }

        try {

            reader.androidLedSwitch(enabled);

            Log.d(
                    TAG,
                    "Android LED: " + enabled
            );

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Failed to control Android LED",
                    e
            );
        }
    }

    // ============================================================
    // ANTENNA LED
    // ============================================================

    public void setAntennaLed(
            int antenna,
            boolean enabled
    ) {

        if (reader == null) {
            return;
        }

        try {

            reader.antLedSwitch(
                    antenna,
                    enabled
            );

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Failed to control antenna LED",
                    e
            );
        }
    }

    // ============================================================
    // SUCCESS LED
    // ============================================================

    public void successLed() {

        if (reader == null) {
            return;
        }

        try {

            reader.led();

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Failed to trigger RFID LED",
                    e
            );
        }
    }

    // ============================================================
    // BUZZER
    // ============================================================

    public void buzzer() {

        if (reader == null) {
            return;
        }

        try {

            reader.buzzer();

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Failed to trigger buzzer",
                    e
            );
        }
    }

    // ============================================================
    // SUCCESS NOTIFICATION
    // ============================================================

    public void successNotify() {

        if (reader == null) {
            return;
        }

        try {

            reader.successNotify();

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Failed success notification",
                    e
            );
        }
    }

    // ============================================================
    // RELEASE
    // ============================================================

    public void release() {

        if (reader == null) {
            return;
        }

        try {

            if (scanning) {
                reader.stopInventory();
            }

            scanning = false;

            setStatusLed(LedColor.OFF);

            reader.androidLedSwitch(false);

            reader.output1Off();
            reader.output2Off();
            reader.output3Off();
            reader.output4Off();

            reader.free();

        } catch (Exception e) {

            Log.w(
                    TAG,
                    "Error releasing RFID reader",
                    e
            );
        }

        reader = null;
    }

    // ============================================================
    // ERROR
    // ============================================================

    private void notifyError(String message) {

        Log.e(
                TAG,
                message
        );

        if (listener != null) {
            listener.onError(message);
        }
    }
}