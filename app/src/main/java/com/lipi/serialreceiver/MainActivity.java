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
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
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
     * Jewelry weight tolerance.
     *
     * Expected: 48.636 g
     * Actual:   48.500 g
     *
     * Difference = 0.136 g
     *
     * MATCH when difference <= 2 grams.
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
    private Double currentWeightGrams = null;

    /*
     * EPC -> ScanRecord
     */
    private final Map<String, ScanRecord> scannedTags =
            new HashMap<>();

    // ============================================================
    // UI
    // ============================================================

    private TextView statusText;


    private Spinner baudSpinner;

    private Button connectButton;
    private Button clearButton;
    private ImageView jewelleryImageView;

    private TextView rfidStatusText;
    private Button rfidToggleButton;

    private TextView epcText;
    private TextView epcDetailText;

    private TextView itemNameText;
    private TextView itemTypeText;
    private TextView materialText;
    private TextView purityText;

    private TextView expectedWeightText;
    private TextView expectedWeightLargeText;

    private TextView liveWeightText;
    private TextView resultText;
    private TextView differenceText;

    private TextView scaleConnectionText;
    private TextView scaleLargeWeightText;
    private TextView scaleStableText;

    private TextView scannedTagsText;

    private TextView timeText;
    private TextView dateText;

    private Button resetScanButton;

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
    // CLOCK
    // ============================================================

    private final Runnable clockRunnable =
            new Runnable() {
                @Override
                public void run() {

                    updateClock();

                    mainHandler.postDelayed(
                            this,
                            1000
                    );
                }
            };

    // ============================================================
    // USB RECEIVER
    // ============================================================

    private final BroadcastReceiver usbReceiver =
            new BroadcastReceiver() {

                @Override
                public void onReceive(
                        Context context,
                        Intent intent) {

                    String action =
                            intent.getAction();

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
                                        "USB permission denied"
                                );
                            }
                        }

                    } else if (
                            UsbManager.ACTION_USB_DEVICE_ATTACHED
                                    .equals(action)) {

                        setStatus(
                                "Scale attached - tap Connect"
                        );

                    } else if (
                            UsbManager.ACTION_USB_DEVICE_DETACHED
                                    .equals(action)) {

                        setStatus(
                                "Scale disconnected"
                        );

                        closeConnection();
                    }
                }
            };

    // ============================================================
    // ACTIVITY
    // ============================================================

    @Override
    protected void onCreate(
            Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        getWindow().setFlags(
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );

        setContentView(
                R.layout.activity_main
        );

        bindViews();

        usbManager =
                (UsbManager) getSystemService(
                        Context.USB_SERVICE
                );

        setupBaudSpinner();

        setupScaleControls();

        setupRfid();

        showEmptyScan();

        updateClock();

        mainHandler.post(clockRunnable);

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

        handleLaunchIntent(
                getIntent()
        );
    }

    // ============================================================
    // BIND UI
    // ============================================================

    private void bindViews() {

        statusText =
                findViewById(
                        R.id.statusText
                );

        jewelleryImageView = findViewById(R.id.jewelleryImageView);

        baudSpinner =
                findViewById(
                        R.id.baudSpinner
                );

        connectButton =
                findViewById(
                        R.id.connectButton
                );

        clearButton =
                findViewById(
                        R.id.clearButton
                );



        rfidStatusText =
                findViewById(
                        R.id.rfidStatusText
                );

        rfidToggleButton =
                findViewById(
                        R.id.rfidToggleButton
                );

        epcText =
                findViewById(
                        R.id.epcText
                );

        epcDetailText =
                findViewById(
                        R.id.epcDetailText
                );

        itemNameText =
                findViewById(
                        R.id.itemNameText
                );

        itemTypeText =
                findViewById(
                        R.id.itemTypeText
                );

        materialText =
                findViewById(
                        R.id.materialText
                );

        purityText =
                findViewById(
                        R.id.purityText
                );

        expectedWeightText =
                findViewById(
                        R.id.expectedWeightText
                );

        expectedWeightLargeText =
                findViewById(
                        R.id.expectedWeightLargeText
                );

        liveWeightText =
                findViewById(
                        R.id.liveWeightText
                );

        resultText =
                findViewById(
                        R.id.resultText
                );

        differenceText =
                findViewById(
                        R.id.differenceText
                );

        scaleConnectionText =
                findViewById(
                        R.id.scaleConnectionText
                );

        scaleLargeWeightText =
                findViewById(
                        R.id.scaleLargeWeightText
                );

        scaleStableText =
                findViewById(
                        R.id.scaleStableText
                );

        scannedTagsText =
                findViewById(
                        R.id.scannedTagsText
                );

        timeText =
                findViewById(
                        R.id.timeText
                );

        dateText =
                findViewById(
                        R.id.dateText
                );

        resetScanButton =
                findViewById(
                        R.id.resetScanButton
                );
    }

    // ============================================================
    // CLOCK
    // ============================================================

    private void updateClock() {

        Date now = new Date();

        SimpleDateFormat timeFormat =
                new SimpleDateFormat(
                        "hh:mm:ss a",
                        Locale.US
                );

        SimpleDateFormat dateFormat =
                new SimpleDateFormat(
                        "dd MMM yyyy",
                        Locale.US
                );

        timeText.setText(
                timeFormat.format(now)
        );

        dateText.setText(
                dateFormat.format(now)
        );
    }

    // ============================================================
    // BAUD
    // ============================================================

    private void setupBaudSpinner() {

        String[] labels =
                new String[baudRates.length];

        for (int i = 0;
             i < baudRates.length;
             i++) {

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

        baudSpinner.setAdapter(
                adapter
        );

        // Default 9600
        for (int i = 0;
             i < baudRates.length;
             i++) {

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

        connectButton.setOnClickListener(
                v -> {

                    if (usbSerialPort == null) {

                        findAndConnect();

                    } else {

                        closeConnection();
                    }
                }
        );

        clearButton.setOnClickListener(
                v -> {

                    resetCurrentTag();

                    scannedTags.clear();

                    scannedTagsText.setText(
                            "No tags scanned"
                    );

                    currentWeightGrams = null;

                    liveWeightText.setText(
                            "—"
                    );

                    scaleLargeWeightText.setText(
                            "— g"
                    );

                    scaleStableText.setText(
                            "〰 Waiting for weight"
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
                }
        );
    }

    // ============================================================
    // FIND SERIAL DEVICE
    // ============================================================

    private UsbSerialDriver findDriver() {

        ProbeTable customTable =
                UsbSerialProber.getDefaultProbeTable();

        UsbSerialProber prober =
                new UsbSerialProber(
                        customTable
                );

        List<UsbSerialDriver> drivers =
                prober.findAllDrivers(
                        usbManager
                );

        if (drivers.isEmpty()) {

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
                    "No compatible serial adapter found"
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
    // OPEN SERIAL
    // ============================================================

    private void openConnection(
            UsbDevice device) {

        UsbSerialDriver foundDriver = null;

        for (
                UsbSerialDriver driver :
                UsbSerialProber
                        .getDefaultProber()
                        .findAllDrivers(
                                usbManager
                        )
        ) {

            if (driver.getDevice().equals(device)) {

                foundDriver = driver;

                break;
            }
        }

        if (foundDriver == null) {

            setStatus(
                    "Serial driver not found"
            );

            return;
        }

        android.hardware.usb.UsbDeviceConnection connection =
                usbManager.openDevice(device);

        if (connection == null) {

            setStatus(
                    "Failed to open USB connection"
            );

            return;
        }

        usbSerialPort =
                foundDriver
                        .getPorts()
                        .get(0);

        try {

            usbSerialPort.open(
                    connection
            );

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
                            + " baud"
            );

            connectButton.setText(
                    "DISCONNECT"
            );

            scaleConnectionText.setText(
                    "● SCALE CONNECTED"
            );

            scaleConnectionText.setTextColor(
                    0xFF2E7D32
            );

            scaleStableText.setText(
                    "〰 Waiting for weight"
            );

        } catch (IOException e) {

            Log.e(
                    TAG,
                    "Error opening serial port",
                    e
            );

            setStatus(
                    "Error opening scale: "
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
                "SCALE CONNECT"
        );

        scaleConnectionText.setText(
                "● SCALE DISCONNECTED"
        );

        scaleConnectionText.setTextColor(
                0xFFC62828
        );

        scaleStableText.setText(
                "〰 No scale reading"
        );

        setStatus(
                "Scale disconnected"
        );
    }


    // ============================================================
    // STATUS
    // ============================================================

    private void setStatus(
            String text) {

        mainHandler.post(
                () -> statusText.setText(text)
        );
    }

    // ============================================================
    // SERIAL DATA
    // ============================================================

    @Override
    public void onNewData(
            byte[] data) {

        String text =
                new String(
                        data,
                        StandardCharsets.UTF_8
                );

        mainHandler.post(
                () -> {

                    serialBuffer.append(
                            text
                    );

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

                            processScaleLine(
                                    line
                            );
                        }
                    }
                }
        );
    }

    // ============================================================
    // SCALE LINE
    // ============================================================

    private void processScaleLine(
            String line) {

        /*
         * Scale sends:
         *
         * 48.709 48.636 0.074
         *
         * We need:
         *
         * 48.636
         *
         * SECOND VALUE.
         */

        String[] values =
                line.trim().split("\\s+");


        if (values.length < 2) {

            return;
        }

        try {

            // TESTING ONLY
            double weight = 18.450;
            currentWeightGrams = weight;

            // -----------------------------------------------
            // SMALL RECEIVED WEIGHT
            // -----------------------------------------------

            liveWeightText.setText(
                    String.format(
                            Locale.US,
                            "%.3f g",
                            weight
                    )
            );

            // -----------------------------------------------
            // LARGE SCALE DISPLAY
            // -----------------------------------------------

            scaleLargeWeightText.setText(
                    String.format(
                            Locale.US,
                            "%.3f g",
                            weight
                    )
            );

            scaleConnectionText.setText(
                    "● SCALE CONNECTED"
            );

            scaleConnectionText.setTextColor(
                    0xFF2E7D32
            );

            scaleStableText.setText(
                    "〰 Stable"
            );

            scaleStableText.setTextColor(
                    0xFF2E7D32
            );

            /*
             * If an RFID tag is already selected,
             * compare the current scale weight.
             */

            if (currentItem != null) {

                evaluateCurrentTag();
            }

        } catch (NumberFormatException e) {

            Log.w(
                    TAG,
                    "Invalid scale data: "
                            + line
            );
        }
    }

    // ============================================================
    // SERIAL ERROR
    // ============================================================

    @Override
    public void onRunError(
            Exception e) {

        Log.e(
                TAG,
                "Serial connection lost",
                e
        );

        setStatus(
                "Scale connection lost"
        );

        mainHandler.post(
                this::closeConnection
        );
    }

    // ============================================================
    // RFID SETUP
    // ============================================================

    private void setupRfid() {

        rfidManager = new RfidManager();

        rfidManager.setListener(
                new RfidManager.Listener() {

                    @Override
                    public void onTagRead(String epc, String rssi) {

                        mainHandler.post(
                                () -> onTagScanned(epc)
                        );
                    }

                    @Override
                    public void onError(String message) {

                        mainHandler.post(() -> {

                            // Make sure physical RFID light is OFF
                            rfidManager.setRfidLed(false);

                            rfidStatusText.setText(
                                    "RFID: error"
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

        // ============================================================
        // INITIALIZE RFID
        // ============================================================

        boolean ready = rfidManager.init(this);

        if (ready) {

            // RFID is ready but NOT scanning
            rfidManager.setRfidLed(false);

            rfidStatusText.setText(
                    "● Ready"
            );

        } else {

            rfidStatusText.setText(
                    "● RFID unavailable"
            );
        }

        rfidToggleButton.setEnabled(ready);

        // ============================================================
        // START / STOP RFID
        // ============================================================

        rfidToggleButton.setOnClickListener(v -> {

            if (rfidManager.isScanning()) {

                // ----------------------------------------------------
                // STOP RFID SCAN
                // ----------------------------------------------------

                boolean stopped =
                        rfidManager.stopScan();

                // Physical RFID light OFF
                rfidManager.setRfidLed(false);

                rfidToggleButton.setText(
                        "START RFID SCAN"
                );

                rfidStatusText.setText(
                        stopped
                                ? "● Scan stopped"
                                : "● Scan stop failed"
                );

            } else {

                // ----------------------------------------------------
                // START RFID SCAN
                // ----------------------------------------------------

                boolean started =
                        rfidManager.startContinuousScan();

                if (started) {

                    // Physical RFID light ON
                    rfidManager.setStatusLed(
                            RfidManager.LedColor.YELLOW
                    );

                    rfidToggleButton.setText(
                            "STOP RFID SCAN"
                    );

                    rfidStatusText.setText(
                            "● Scanning..."
                    );

                } else {

                    // Start failed -> physical light OFF
                    rfidManager.setStatusLed(
                            RfidManager.LedColor.OFF
                    );

                    rfidToggleButton.setText(
                            "START RFID SCAN"
                    );

                    rfidStatusText.setText(
                            "● RFID start failed"
                    );
                }
            }
        });

        // ============================================================
        // RESET
        // ============================================================

        resetScanButton.setOnClickListener(
                v -> resetCurrentTag()
        );
    }

    // ============================================================
    // RFID TAG SCANNED
    // ============================================================

    private void onTagScanned(
            String epc) {

        if (
                epc == null
                        || epc.trim().isEmpty()
        ) {

            return;
        }
        rfidManager.successNotify();
        epc =
                epc.trim().toUpperCase();

        /*
         * Find jewelry.
         */

        JewelleryItem item =
                JewelleryRepository.findByEpc(
                        epc
                );

        currentEpc =
                epc;

        currentItem =
                item;

        // -----------------------------------------------
        // EPC
        // -----------------------------------------------

        epcText.setText(
                epc
        );

        epcDetailText.setText(
                epc
        );

        // -----------------------------------------------
        // RFID STATUS
        // -----------------------------------------------

        rfidStatusText.setText(
                "● Tag detected"
        );

        // -----------------------------------------------
        // UNKNOWN TAG
        // -----------------------------------------------

        if (item == null) {

            rfidManager.setStatusLed(
                    RfidManager.LedColor.RED
            );

            showUnknownTag();

            return;
        }

        if (item != null) {

            itemNameText.setText(item.getName());
            itemTypeText.setText(item.getType());
            materialText.setText(item.getMaterial());
            purityText.setText(item.getPurity());

            expectedWeightText.setText(
                    String.format(
                            java.util.Locale.US,
                            "%.3f g",
                            item.getExpectedWeightGrams()
                    )
            );

            expectedWeightLargeText.setText(
                    String.format(
                            java.util.Locale.US,
                            "%.3f g",
                            item.getExpectedWeightGrams()
                    )
            );

            // Load jewellery image
            int imageResId = getResources().getIdentifier(
                    item.getImageName(),
                    "drawable",
                    getPackageName()
            );

            if (imageResId != 0) {
                jewelleryImageView.setImageResource(imageResId);
                jewelleryImageView.setVisibility(View.VISIBLE);
            } else {
                jewelleryImageView.setVisibility(View.GONE);
            }
        }
        // -----------------------------------------------
        // PRODUCT DETAILS
        // -----------------------------------------------

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

        // -----------------------------------------------
        // EXPECTED WEIGHT
        // -----------------------------------------------

        double expected =
                item.getExpectedWeightGrams();

        String expectedFormatted =
                String.format(
                        Locale.US,
                        "%.3f",
                        expected
                );

        expectedWeightText.setText(
                "Expected Weight: "
                        + expectedFormatted
                        + " g"
        );

        expectedWeightLargeText.setText(
                expectedFormatted
        );

        // -----------------------------------------------
        // WEIGHT CHECK
        // -----------------------------------------------

        if (currentWeightGrams != null) {

            evaluateCurrentTag();

        } else {

            resultText.setText(
                    "WAITING"
            );

            resultText.setBackgroundColor(
                    0xFF777777
            );

            differenceText.setText(
                    "Difference: —"
            );
        }

        // -----------------------------------------------
        // MULTIPLE TAG RECORD
        // -----------------------------------------------

        updateScannedTag(
                item
        );
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

        expectedWeightLargeText.setText(
                "—"
        );

        resultText.setText(
                "UNKNOWN"
        );

        resultText.setBackgroundColor(
                0xFF777777
        );

        differenceText.setText(
                "EPC is not registered"
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
                Math.abs(
                        difference
                );

        boolean matched =
                absoluteDifference
                        <= WEIGHT_TOLERANCE_GRAMS;

        // -----------------------------------------------
        // DIFFERENCE
        // -----------------------------------------------

        differenceText.setText(
                String.format(
                        Locale.US,
                        "Difference: %+.3f g",
                        difference
                )
        );

        // -----------------------------------------------
        // MATCH
        // -----------------------------------------------

        if (matched) {

            resultText.setText("✓ MATCHED");
            resultText.setBackgroundColor(0xFF2E7D32);

            rfidManager.setStatusLed(
                    RfidManager.LedColor.GREEN
            );

        } else {

            resultText.setText("✗ MISMATCH");
            resultText.setBackgroundColor(0xFFC62828);

            rfidManager.setStatusLed(
                    RfidManager.LedColor.RED
            );
        }

        // -----------------------------------------------
        // UPDATE MULTI TAG
        // -----------------------------------------------

        ScanRecord record =
                scannedTags.get(
                        currentEpc
                );

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
            JewelleryItem item) {

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
    // MULTIPLE TAG UI
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
            );

            builder.append(
                    ". "
            );

            builder.append(
                    record.item.getName()
            );

            builder.append(
                    "\n"
            );

            builder.append(
                    "EPC: "
            );

            builder.append(
                    record.epc
            );

            builder.append(
                    "\n"
            );

            builder.append(
                    String.format(
                            Locale.US,
                            "Expected: %.3f g",
                            record.item
                                    .getExpectedWeightGrams()
                    )
            );

            builder.append(
                    "\n"
            );

            if (record.actualWeight != null) {

                builder.append(
                        String.format(
                                Locale.US,
                                "Actual: %.3f g",
                                record.actualWeight
                        )
                );

                builder.append(
                        "\n"
                );

                builder.append(
                        String.format(
                                Locale.US,
                                "Difference: %+.3f g",
                                record.difference
                        )
                );

                builder.append(
                        "\n"
                );

                if (record.matched) {

                    builder.append(
                            "Result: ✓ MATCHED"
                    );

                } else {

                    builder.append(
                            "Result: ✗ MISMATCH"
                    );

                }

            } else {

                builder.append(
                        "Actual: Waiting for scale"
                );

                builder.append(
                        "\n"
                );

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

        epcDetailText.setText(
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

        expectedWeightLargeText.setText(
                "—"
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
                "● Ready"
        );
    }

    // ============================================================
    // USB ATTACH
    // ============================================================

    @Override
    protected void onNewIntent(
            Intent intent) {

        super.onNewIntent(
                intent
        );

        handleLaunchIntent(
                intent
        );
    }

    private void handleLaunchIntent(
            Intent intent) {

        if (
                intent != null
                        && UsbManager
                        .ACTION_USB_DEVICE_ATTACHED
                        .equals(
                                intent.getAction()
                        )
        ) {

            findAndConnect();
        }
    }

    // ============================================================
    // DESTROY
    // ============================================================

    @Override
    protected void onDestroy() {

        mainHandler.removeCallbacks(
                clockRunnable
        );

        closeConnection();

        if (rfidManager != null) {
            rfidManager.setRfidLed(false);
            rfidManager.setAndroidLed(false);
            rfidManager.release();
        }

        try {

            unregisterReceiver(
                    usbReceiver
            );

        } catch (IllegalArgumentException ignored) {
        }

        super.onDestroy();
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