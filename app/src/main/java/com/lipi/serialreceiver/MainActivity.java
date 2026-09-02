package com.lipi.serialreceiver;

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
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import com.lipi.serialreceiver.inventory.JewelleryRepository;
import com.lipi.serialreceiver.model.JewelleryItem;
import com.lipi.serialreceiver.rfid.RfidManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity
        implements SerialInputOutputManager.Listener {

    private static final String TAG = "JewelleryMonitor";

    private static final String ACTION_USB_PERMISSION =
            "com.lipi.serialreceiver.USB_PERMISSION";

    /*
     * Weight tolerance in grams.
     *
     * Example:
     * Expected = 48.636 g
     * Actual   = 50.000 g
     * Difference = 1.364 g
     * Result = MATCH
     */
    private static final double WEIGHT_TOLERANCE_GRAMS = 2.0;

    // ============================================================
    // SCALE
    // ============================================================

    private UsbManager usbManager;
    private UsbSerialPort usbSerialPort;
    private SerialInputOutputManager ioManager;

    private final int[] baudRates = {
            300,
            1200,
            2400,
            4800,
            9600,
            19200,
            38400,
            57600,
            115200
    };

    // ============================================================
    // RFID
    // ============================================================

    private RfidManager rfidManager;

    // ============================================================
    // CURRENT SCAN
    // ============================================================

    private String currentEpc = null;
    private JewelleryItem currentItem = null;

    /*
     * Latest weight received from scale in grams.
     */
    private Double currentWeightGrams = null;

    /*
     * Keep all EPCs that have been scanned.
     *
     * EPC -> ScanRecord
     */
    private final Map<String, ScanRecord> scannedTags =
            new HashMap<>();

    // ============================================================
    // UI
    // ============================================================

    private TextView statusText;
    private TextView dataText;

    private Spinner baudSpinner;

    private Button connectButton;
    private Button clearButton;

    private Button sendButton;
    private EditText sendEditText;

    private TextView rfidStatusText;
    private Button rfidToggleButton;

    private TextView epcText;
    private TextView itemNameText;
    private TextView itemTypeText;
    private TextView materialText;
    private TextView purityText;
    private TextView expectedWeightText;

    private TextView liveWeightText;
    private TextView resultText;
    private TextView differenceText;

    private Button resetScanButton;

    private TextView scannedTagsText;

    // ============================================================
    // HANDLER
    // ============================================================

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    // ============================================================
    // SERIAL BUFFER
    // ============================================================

    private final StringBuilder serialBuffer =
            new StringBuilder();

    // ============================================================
    // USB RECEIVER
    // ============================================================

    private final BroadcastReceiver usbReceiver =
            new BroadcastReceiver() {

                @Override
                public void onReceive(Context context, Intent intent) {

                    String action = intent.getAction();

                    if (ACTION_USB_PERMISSION.equals(action)) {

                        synchronized (this) {

                            UsbDevice device =
                                    intent.getParcelableExtra(
                                            UsbManager.EXTRA_DEVICE
                                    );

                            boolean granted =
                                    intent.getBooleanExtra(
                                            UsbManager.EXTRA_PERMISSION_GRANTED,
                                            false
                                    );

                            if (granted && device != null) {

                                openConnection(device);

                            } else {

                                setStatus(
                                        "Permission denied for device"
                                );
                            }
                        }

                    } else if (
                            UsbManager.ACTION_USB_DEVICE_ATTACHED
                                    .equals(action)) {

                        setStatus(
                                "Device attached — tap Connect"
                        );

                    } else if (
                            UsbManager.ACTION_USB_DEVICE_DETACHED
                                    .equals(action)) {

                        setStatus("Device detached");

                        closeConnection();
                    }
                }
            };

    // ============================================================
    // ACTIVITY
    // ============================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        bindViews();

        usbManager =
                (UsbManager) getSystemService(
                        Context.USB_SERVICE
                );

        setupBaudSpinner();

        setupScaleControls();

        setupRfid();

        showEmptyScan();

        // --------------------------------------------------------
        // USB receiver
        // --------------------------------------------------------

        IntentFilter filter =
                new IntentFilter();

        filter.addAction(
                ACTION_USB_PERMISSION
        );

        filter.addAction(
                UsbManager.ACTION_USB_DEVICE_ATTACHED
        );

        filter.addAction(
                UsbManager.ACTION_USB_DEVICE_DETACHED
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            registerReceiver(
                    usbReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
            );

        } else {

            registerReceiver(
                    usbReceiver,
                    filter
            );
        }

        handleLaunchIntent(getIntent());
    }

    // ============================================================
    // BIND UI
    // ============================================================

    private void bindViews() {

        statusText = findViewById(
                R.id.statusText
        );

        dataText = findViewById(
                R.id.dataText
        );

        baudSpinner = findViewById(
                R.id.baudSpinner
        );

        connectButton = findViewById(
                R.id.connectButton
        );

        clearButton = findViewById(
                R.id.clearButton
        );

        sendButton = findViewById(
                R.id.sendButton
        );

        sendEditText = findViewById(
                R.id.sendEditText
        );

        rfidStatusText = findViewById(
                R.id.rfidStatusText
        );

        rfidToggleButton = findViewById(
                R.id.rfidToggleButton
        );

        epcText = findViewById(
                R.id.epcText
        );

        itemNameText = findViewById(
                R.id.itemNameText
        );

        itemTypeText = findViewById(
                R.id.itemTypeText
        );

        materialText = findViewById(
                R.id.materialText
        );

        purityText = findViewById(
                R.id.purityText
        );

        expectedWeightText = findViewById(
                R.id.expectedWeightText
        );

        liveWeightText = findViewById(
                R.id.liveWeightText
        );

        resultText = findViewById(
                R.id.resultText
        );

        differenceText = findViewById(
                R.id.differenceText
        );

        resetScanButton = findViewById(
                R.id.resetScanButton
        );

        scannedTagsText = findViewById(
                R.id.scannedTagsText
        );
    }

    // ============================================================
    // BAUD
    // ============================================================

    private void setupBaudSpinner() {

        String[] labels =
                new String[baudRates.length];

        for (int i = 0; i < baudRates.length; i++) {

            labels[i] =
                    baudRates[i] + " baud";
        }

        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(
                        this,
                        android.R.layout.simple_spinner_item,
                        labels
                );

        adapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
        );

        baudSpinner.setAdapter(adapter);

        // Default = 9600
        for (int i = 0; i < baudRates.length; i++) {

            if (baudRates[i] == 9600) {

                baudSpinner.setSelection(i);

                break;
            }
        }
    }

    // ============================================================
    // SCALE CONTROLS
    // ============================================================

    private void setupScaleControls() {

        connectButton.setOnClickListener(v -> {

            if (usbSerialPort == null) {

                findAndConnect();

            } else {

                closeConnection();
            }
        });

        clearButton.setOnClickListener(v -> {

            dataText.setText("");

            serialBuffer.setLength(0);
        });

        sendButton.setOnClickListener(v ->
                sendData()
        );
    }

    // ============================================================
    // FIND SERIAL DEVICE
    // ============================================================

    private UsbSerialDriver findDriver() {

        ProbeTable customTable =
                UsbSerialProber.getDefaultProbeTable();

        UsbSerialProber prober =
                new UsbSerialProber(customTable);

        List<UsbSerialDriver> drivers =
                prober.findAllDrivers(
                        usbManager
                );

        if (drivers.isEmpty()) {

            for (UsbDevice device :
                    usbManager.getDeviceList().values()) {

                Log.d(
                        TAG,
                        "Unrecognized USB device: "
                                + "vendorId="
                                + device.getVendorId()
                                + " productId="
                                + device.getProductId()
                );
            }

            return null;
        }

        return drivers.get(0);
    }

    // ============================================================
    // CONNECT
    // ============================================================

    private void findAndConnect() {

        UsbSerialDriver driver =
                findDriver();

        if (driver == null) {

            setStatus(
                    "No compatible serial adapter found."
            );

            Toast.makeText(
                    this,
                    "No serial device found",
                    Toast.LENGTH_LONG
            ).show();

            return;
        }

        UsbDevice device =
                driver.getDevice();

        if (!usbManager.hasPermission(device)) {

            int flags =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                            ? PendingIntent.FLAG_MUTABLE
                            : 0;

            PendingIntent permissionIntent =
                    PendingIntent.getBroadcast(
                            this,
                            0,
                            new Intent(
                                    ACTION_USB_PERMISSION
                            ),
                            flags
                    );

            usbManager.requestPermission(
                    device,
                    permissionIntent
            );

            setStatus(
                    "Requesting USB permission..."
            );

        } else {

            openConnection(device);
        }
    }

    // ============================================================
    // OPEN SERIAL CONNECTION
    // ============================================================

    private void openConnection(
            UsbDevice device
    ) {

        UsbSerialDriver foundDriver = null;

        for (
                UsbSerialDriver d :
                UsbSerialProber
                        .getDefaultProber()
                        .findAllDrivers(usbManager)
        ) {

            if (d.getDevice().equals(device)) {

                foundDriver = d;

                break;
            }
        }

        if (foundDriver == null) {

            setStatus(
                    "Driver not found for device"
            );

            return;
        }

        android.hardware.usb.UsbDeviceConnection connection =
                usbManager.openDevice(device);

        if (connection == null) {

            setStatus(
                    "Failed to open device connection"
            );

            return;
        }

        usbSerialPort =
                foundDriver.getPorts().get(0);

        try {

            usbSerialPort.open(connection);

            int baud =
                    baudRates[
                            baudSpinner
                                    .getSelectedItemPosition()
                            ];

            usbSerialPort.setParameters(
                    baud,
                    UsbSerialPort.DATABITS_8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
            );

            ioManager =
                    new SerialInputOutputManager(
                            usbSerialPort,
                            this
                    );

            ioManager.start();

            setStatus(
                    "Scale connected @ "
                            + baud
                            + " baud ("
                            + device.getDeviceName()
                            + ")"
            );

            connectButton.setText(
                    "Disconnect"
            );

        } catch (IOException e) {

            Log.e(
                    TAG,
                    "Error opening port",
                    e
            );

            setStatus(
                    "Error opening port: "
                            + e.getMessage()
            );

            closeConnection();
        }
    }

    // ============================================================
    // CLOSE SERIAL
    // ============================================================

    private void closeConnection() {

        if (ioManager != null) {

            ioManager.stop();

            ioManager = null;
        }

        if (usbSerialPort != null) {

            try {

                usbSerialPort.close();

            } catch (IOException ignored) {
            }

            usbSerialPort = null;
        }

        connectButton.setText(
                "Connect"
        );

        setStatus(
                "Disconnected"
        );
    }

    // ============================================================
    // SEND SERIAL DATA
    // ============================================================

    private void sendData() {

        String text =
                sendEditText
                        .getText()
                        .toString();

        if (
                text.isEmpty()
                        || usbSerialPort == null
        ) {

            return;
        }

        try {

            usbSerialPort.write(
                    text.getBytes(
                            StandardCharsets.UTF_8
                    ),
                    1000
            );

            sendEditText.setText("");

        } catch (IOException e) {

            Toast.makeText(
                    this,
                    "Send failed: "
                            + e.getMessage(),
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    // ============================================================
    // STATUS
    // ============================================================

    private void setStatus(String text) {

        mainHandler.post(() ->
                statusText.setText(text)
        );
    }

    // ============================================================
    // SERIAL DATA
    // ============================================================

    @Override
    public void onNewData(byte[] data) {

        String text =
                new String(
                        data,
                        StandardCharsets.UTF_8
                );

        mainHandler.post(() -> {

            serialBuffer.append(text);

            int newlineIndex;

            while (
                    (newlineIndex =
                            serialBuffer.indexOf("\n"))
                            >= 0
            ) {

                String line =
                        serialBuffer
                                .substring(
                                        0,
                                        newlineIndex
                                )
                                .trim();

                serialBuffer.delete(
                        0,
                        newlineIndex + 1
                );

                if (!line.isEmpty()) {

                    processScaleLine(line);
                }
            }
        });
    }

    // ============================================================
    // SCALE LINE
    // ============================================================

    private void processScaleLine(
            String line
    ) {

        /*
         * Example scale data:
         *
         * 48.709 48.636 0.074
         *
         * We want:
         *
         * 48.636
         *
         * which is the SECOND value.
         */

        String[] values =
                line.trim().split("\\s+");

        // Keep raw serial data visible.
        dataText.setText(line);

        if (values.length < 2) {

            return;
        }

        try {

            double weight =
                    Double.parseDouble(
                            values[1]
                    );

            /*
             * Scale value is being treated as grams.
             */
            currentWeightGrams = weight;

            liveWeightText.setText(
                    String.format(
                            Locale.US,
                            "%.3f g",
                            weight
                    )
            );

            /*
             * If an RFID tag is already selected,
             * compare the new scale weight.
             */
            if (currentItem != null) {

                evaluateCurrentTag();
            }

        } catch (NumberFormatException e) {

            Log.w(
                    TAG,
                    "Unable to parse scale weight: "
                            + line
            );
        }
    }

    // ============================================================
    // SERIAL ERROR
    // ============================================================

    @Override
    public void onRunError(Exception e) {

        Log.e(
                TAG,
                "Serial connection lost",
                e
        );

        setStatus(
                "Connection lost: "
                        + e.getMessage()
        );

        mainHandler.post(
                this::closeConnection
        );
    }

    // ============================================================
    // RFID SETUP
    // ============================================================

    private void setupRfid() {

        rfidManager =
                new RfidManager();

        rfidManager.setListener(
                new RfidManager.Listener() {

                    @Override
                    public void onTagRead(
                            String epc,
                            String rssi
                    ) {

                        mainHandler.post(() ->
                                onTagScanned(epc)
                        );
                    }

                    @Override
                    public void onError(
                            String message
                    ) {

                        mainHandler.post(() -> {

                            rfidStatusText.setText(
                                    "RFID: error — "
                                            + message
                            );

                            Toast.makeText(
                                    MainActivity.this,
                                    message,
                                    Toast.LENGTH_LONG
                            ).show();
                        });
                    }
                }
        );

        boolean ready =
                rfidManager.init(this);

        rfidStatusText.setText(
                ready
                        ? "RFID: ready"
                        : "RFID: unavailable on this device"
        );

        rfidToggleButton.setEnabled(
                ready
        );

        rfidToggleButton.setOnClickListener(
                v -> {

                    if (rfidManager.isScanning()) {

                        rfidManager.stopScan();

                        rfidToggleButton.setText(
                                "Start Scan"
                        );

                        rfidStatusText.setText(
                                "RFID: stopped"
                        );

                    } else {

                        boolean started =
                                rfidManager
                                        .startContinuousScan();

                        if (started) {

                            rfidToggleButton.setText(
                                    "Stop Scan"
                            );

                            rfidStatusText.setText(
                                    "RFID: scanning..."
                            );
                        }
                    }
                }
        );

        resetScanButton.setOnClickListener(
                v -> resetCurrentTag()
        );
    }

    // ============================================================
    // RFID TAG SCANNED
    // ============================================================

    private void onTagScanned(
            String epc
    ) {

        if (
                epc == null
                        || epc.trim().isEmpty()
        ) {

            return;
        }

        epc =
                epc.trim().toUpperCase();

        /*
         * Find jewellery by EPC.
         */
        JewelleryItem item =
                JewelleryRepository.findByEpc(
                        epc
                );

        /*
         * Make this tag the CURRENT tag.
         */
        currentEpc = epc;

        currentItem = item;

        /*
         * Do NOT clear the weight.
         *
         * If the item is already sitting on the scale,
         * the latest scale reading can immediately be
         * compared with this newly scanned tag.
         */

        epcText.setText(
                epc
        );

        rfidStatusText.setText(
                "RFID: tag detected"
        );

        if (item == null) {

            showUnknownTag();

            return;
        }

        /*
         * Display jewellery details.
         */
        itemNameText.setText(
                "Name: "
                        + item.getName()
        );

        itemTypeText.setText(
                "Type: "
                        + item.getType()
        );

        materialText.setText(
                "Material: "
                        + item.getMaterial()
        );

        purityText.setText(
                "Purity: "
                        + item.getPurity()
        );

        expectedWeightText.setText(
                String.format(
                        Locale.US,
                        "Expected Weight: %.3f g",
                        item.getExpectedWeightGrams()
                )
        );

        /*
         * If we already have a scale reading,
         * compare it immediately.
         */
        if (currentWeightGrams != null) {

            evaluateCurrentTag();

        } else {

            resultText.setText(
                    "WAITING FOR SCALE"
            );

            differenceText.setText(
                    "Difference: —"
            );
        }

        /*
         * Add / update this tag in our multi-tag list.
         */
        updateScannedTag(item);
    }

    // ============================================================
    // UNKNOWN TAG
    // ============================================================

    private void showUnknownTag() {

        itemNameText.setText(
                "Name: Unknown Tag"
        );

        itemTypeText.setText(
                "Type: —"
        );

        materialText.setText(
                "Material: —"
        );

        purityText.setText(
                "Purity: —"
        );

        expectedWeightText.setText(
                "Expected Weight: —"
        );

        resultText.setText(
                "UNKNOWN TAG"
        );

        differenceText.setText(
                "EPC is not registered"
        );

        resultText.setBackgroundColor(
                0xFF777777
        );
    }

    // ============================================================
    // WEIGHT COMPARISON
    // ============================================================

    private void evaluateCurrentTag() {

        if (
                currentItem == null
                        || currentWeightGrams == null
        ) {

            return;
        }

        double expected =
                currentItem
                        .getExpectedWeightGrams();

        double actual =
                currentWeightGrams;

        double difference =
                actual - expected;

        double absoluteDifference =
                Math.abs(difference);

        boolean matched =
                absoluteDifference
                        <= WEIGHT_TOLERANCE_GRAMS;

        // --------------------------------------------------------
        // Difference display
        // --------------------------------------------------------

        differenceText.setText(
                String.format(
                        Locale.US,
                        "Difference: %+.3f g",
                        difference
                )
        );

        // --------------------------------------------------------
        // Result
        // --------------------------------------------------------

        if (matched) {

            resultText.setText(
                    "✓ MATCH"
            );

            resultText.setBackgroundColor(
                    0xFF2E7D32
            );

        } else {

            resultText.setText(
                    "✗ MISMATCH"
            );

            resultText.setBackgroundColor(
                    0xFFC62828
            );
        }

        /*
         * Update the record for this EPC.
         */
        ScanRecord record =
                scannedTags.get(currentEpc);

        if (record != null) {

            record.actualWeight =
                    actual;

            record.difference =
                    difference;

            record.matched =
                    matched;

            updateScannedTagsText();
        }
    }

    // ============================================================
    // MULTIPLE TAGS
    // ============================================================

    private void updateScannedTag(
            JewelleryItem item
    ) {

        ScanRecord record =
                scannedTags.get(
                        item.getEpc()
                );

        if (record == null) {

            record =
                    new ScanRecord();

            record.epc =
                    item.getEpc();

            record.item =
                    item;

            scannedTags.put(
                    item.getEpc(),
                    record
            );
        }

        /*
         * If there is already a current scale reading,
         * use it for this tag.
         */
        if (currentWeightGrams != null) {

            record.actualWeight =
                    currentWeightGrams;

            double difference =
                    currentWeightGrams
                            - item.getExpectedWeightGrams();

            record.difference =
                    difference;

            record.matched =
                    Math.abs(difference)
                            <= WEIGHT_TOLERANCE_GRAMS;
        }

        updateScannedTagsText();
    }

    // ============================================================
    // MULTI TAG UI
    // ============================================================

    private void updateScannedTagsText() {

        if (scannedTags.isEmpty()) {

            scannedTagsText.setText(
                    "No tags scanned"
            );

            return;
        }

        StringBuilder builder =
                new StringBuilder();

        int count = 1;

        for (
                ScanRecord record :
                scannedTags.values()
        ) {

            builder.append(
                    count++
            ).append(". ");

            builder.append(
                    record.item.getName()
            );

            builder.append("\n");

            builder.append(
                    "EPC: "
            );

            builder.append(
                    record.epc
            );

            builder.append("\n");

            builder.append(
                    String.format(
                            Locale.US,
                            "Expected: %.3f g",
                            record.item
                                    .getExpectedWeightGrams()
                    )
            );

            builder.append("\n");

            if (record.actualWeight != null) {

                builder.append(
                        String.format(
                                Locale.US,
                                "Actual: %.3f g",
                                record.actualWeight
                        )
                );

                builder.append("\n");

                builder.append(
                        String.format(
                                Locale.US,
                                "Difference: %+.3f g",
                                record.difference
                        )
                );

                builder.append("\n");

                builder.append(
                        record.matched
                                ? "Result: MATCH ✓"
                                : "Result: MISMATCH ✗"
                );

            } else {

                builder.append(
                        "Actual: Waiting for scale"
                );

                builder.append("\n");

                builder.append(
                        "Result: WAITING"
                );
            }

            builder.append(
                    "\n\n"
            );
        }

        scannedTagsText.setText(
                builder.toString()
        );
    }

    // ============================================================
    // RESET CURRENT TAG
    // ============================================================

    private void resetCurrentTag() {

        currentEpc = null;

        currentItem = null;

        currentWeightGrams = null;

        showEmptyScan();
    }

    // ============================================================
    // EMPTY STATE
    // ============================================================

    private void showEmptyScan() {

        epcText.setText(
                "—"
        );

        itemNameText.setText(
                "Name: —"
        );

        itemTypeText.setText(
                "Type: —"
        );

        materialText.setText(
                "Material: —"
        );

        purityText.setText(
                "Purity: —"
        );

        expectedWeightText.setText(
                "Expected Weight: —"
        );

        liveWeightText.setText(
                "—"
        );

        resultText.setText(
                "WAITING"
        );

        resultText.setBackgroundColor(
                0xFF777777
        );

        differenceText.setText(
                "Difference: —"
        );

        rfidStatusText.setText(
                "RFID: idle"
        );
    }

    // ============================================================
    // USB ATTACH INTENT
    // ============================================================

    @Override
    protected void onNewIntent(
            Intent intent
    ) {

        super.onNewIntent(intent);

        handleLaunchIntent(intent);
    }

    private void handleLaunchIntent(
            Intent intent
    ) {

        if (
                intent != null
                        && UsbManager
                        .ACTION_USB_DEVICE_ATTACHED
                        .equals(intent.getAction())
        ) {

            findAndConnect();
        }
    }

    // ============================================================
    // DESTROY
    // ============================================================

    @Override
    protected void onDestroy() {

        super.onDestroy();

        closeConnection();

        if (rfidManager != null) {

            rfidManager.release();
        }

        try {

            unregisterReceiver(
                    usbReceiver
            );

        } catch (IllegalArgumentException ignored) {
        }
    }

    // ============================================================
    // SCAN RECORD
    // ============================================================

    private static class ScanRecord {

        String epc;

        JewelleryItem item;

        Double actualWeight;

        double difference;

        boolean matched;
    }
}