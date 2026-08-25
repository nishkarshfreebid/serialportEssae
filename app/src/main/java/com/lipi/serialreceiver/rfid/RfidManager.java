package com.lipi.serialreceiver.rfid;

import android.content.Context;
import android.util.Log;

import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.deviceapi.entity.UHFTAGInfo;
import com.rscja.deviceapi.exception.ConfigurationException;
import com.rscja.deviceapi.interfaces.IUHFInventoryCallback;

/**
 * Thin wrapper around the Chainway DeviceAPI UHF (RFID) module.
 *
 * This targets the device's built-in UHF reader (com.rscja.deviceapi.RFIDWithUHFUART),
 * which is separate from the USB-serial connection used for the weighing scale — the two
 * run independently and don't compete for the same port.
 *
 * Usage:
 *   rfidManager = new RfidManager();
 *   rfidManager.init(context);
 *   rfidManager.setListener(tag -> { ... });
 *   rfidManager.startContinuousScan();
 *   ...
 *   rfidManager.stopScan();
 *   rfidManager.release();
 */
public class RfidManager {

    private static final String TAG = "RfidManager";

    /** Callback for the UI layer; delivered on a background thread (see docs for callback()). */
    public interface Listener {
        void onTagRead(String epc, String rssi);
        void onError(String message);
    }

    private RFIDWithUHFUART reader;
    private Listener listener;
    private volatile boolean scanning = false;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Initializes the on-board UHF module. Call once, e.g. in Activity.onCreate(). */
    public boolean init(Context context) {
        try {
            reader = RFIDWithUHFUART.getInstance();
        } catch (ConfigurationException e) {
            Log.e(TAG, "RFID module not supported on this device", e);
            notifyError("RFID module not supported on this device: " + e.getMessage());
            return false;
        }

        boolean ok = reader.init(context);
        if (!ok) {
            notifyError("Failed to initialize RFID reader");
            return false;
        }

        reader.setInventoryCallback(new IUHFInventoryCallback() {
            @Override
            public void callback(UHFTAGInfo tagInfo) {
                if (tagInfo == null || tagInfo.getEPC() == null) return;
                if (listener != null) {
                    listener.onTagRead(tagInfo.getEPC(), tagInfo.getRssi());
                }
            }
        });

        return true;
    }

    /** Starts continuous inventory (repeated tag reads until stopScan() is called). */
    public boolean startContinuousScan() {
        if (reader == null) {
            notifyError("RFID reader not initialized");
            return false;
        }
        if (scanning) return true;
        boolean started = reader.startInventoryTag();
        scanning = started;
        if (!started) {
            notifyError("Failed to start RFID scan");
        }
        return started;
    }

    public boolean stopScan() {
        if (reader == null) return false;
        boolean stopped = reader.stopInventory();
        scanning = false;
        return stopped;
    }

    public boolean isScanning() {
        return scanning;
    }

    /** Releases the reader. Call in Activity.onDestroy(). */
    public void release() {
        if (reader != null) {
            try {
                if (scanning) {
                    reader.stopInventory();
                }
                reader.free();
            } catch (Exception e) {
                Log.w(TAG, "Error releasing RFID reader", e);
            }
        }
    }

    private void notifyError(String message) {
        Log.e(TAG, message);
        if (listener != null) {
            listener.onError(message);
        }
    }
}
