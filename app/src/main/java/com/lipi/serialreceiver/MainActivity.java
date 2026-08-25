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
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import com.lipi.serialreceiver.inventory.InventoryRepository;
import com.lipi.serialreceiver.model.InventoryItem;
import com.lipi.serialreceiver.model.ScanResult;
import com.lipi.serialreceiver.rfid.RfidManager;
import com.lipi.serialreceiver.scale.WeightParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements SerialInputOutputManager.Listener {

    private static final String TAG = "SerialMonitor";
    private static final String ACTION_USB_PERMISSION = "com.lipi.serialreceiver.USB_PERMISSION";

    /** Allowed difference (kg) between expected item weight and live scale reading to count as a MATCH. */
    private static final double WEIGHT_TOLERANCE_KG = 0.05;

    // --- Scale (USB-serial) ---
    private UsbManager usbManager;
    private UsbSerialPort usbSerialPort;
    private SerialInputOutputManager ioManager;
    private final int[] baudRates = {300, 1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200};

    // --- RFID (Chainway UHF) ---
    private RfidManager rfidManager;

    // --- Combined scan state ---
    private String lastEpc = null;
    private Double lastWeightKg = null;

    // --- Views ---
    private TextView statusText;
    private TextView dataText;
    private ScrollView scrollView;
    private Spinner baudSpinner;
    private Button connectButton;
    private Button clearButton;
    private Button sendButton;
    private EditText sendEditText;

    private TextView rfidStatusText;
    private Button rfidToggleButton;

    private View verdictCard;
    private TextView verdictLabel;
    private TextView itemNameText;
    private TextView skuText;
    private TextView epcText;
    private TextView stockText;
    private TextView expectedWeightText;
    private TextView liveWeightText;
    private Button resetScanButton;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    if (granted && device != null) {
                        openConnection(device);
                    } else {
                        setStatus("Permission denied for device");
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                setStatus("Device attached — tap Connect");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                setStatus("Device detached");
                closeConnection();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        setupBaudSpinner();
        setupScaleControls();
        setupRfid();
        renderScanResult(currentScanResult());

        // Register USB broadcast receiver (permission result + attach/detach) for the scale
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        // If the app was launched via the USB_DEVICE_ATTACHED intent filter
        handleLaunchIntent(getIntent());
    }

    private void bindViews() {
        statusText = findViewById(R.id.statusText);
        dataText = findViewById(R.id.dataText);
        scrollView = null; // dataText now scrolls itself inside a fixed-height box
        baudSpinner = findViewById(R.id.baudSpinner);
        connectButton = findViewById(R.id.connectButton);
        clearButton = findViewById(R.id.clearButton);
        sendButton = findViewById(R.id.sendButton);
        sendEditText = findViewById(R.id.sendEditText);

        rfidStatusText = findViewById(R.id.rfidStatusText);
        rfidToggleButton = findViewById(R.id.rfidToggleButton);

        verdictCard = findViewById(R.id.verdictCard);
        verdictLabel = findViewById(R.id.verdictLabel);
        itemNameText = findViewById(R.id.itemNameText);
        skuText = findViewById(R.id.skuText);
        epcText = findViewById(R.id.epcText);
        stockText = findViewById(R.id.stockText);
        expectedWeightText = findViewById(R.id.expectedWeightText);
        liveWeightText = findViewById(R.id.liveWeightText);
        resetScanButton = findViewById(R.id.resetScanButton);
    }

    // =====================================================================
    // Scale (USB-serial) setup — unchanged behavior, plus weight parsing
    // =====================================================================

    private void setupScaleControls() {
        connectButton.setOnClickListener(v -> {
            if (usbSerialPort == null) {
                findAndConnect();
            } else {
                closeConnection();
            }
        });

        clearButton.setOnClickListener(v -> dataText.setText(""));

        sendButton.setOnClickListener(v -> sendData());
    }

    private void setupBaudSpinner() {
        String[] labels = new String[baudRates.length];
        for (int i = 0; i < baudRates.length; i++) labels[i] = baudRates[i] + " baud";
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        baudSpinner.setAdapter(adapter);
        for (int i = 0; i < baudRates.length; i++) {
            if (baudRates[i] == 9600) {
                baudSpinner.setSelection(i);
                break;
            }
        }
    }

    /** Uses a custom + default probe table so CH340/CH341 and friends are recognized. */
    private UsbSerialDriver findDriver() {
        ProbeTable customTable = UsbSerialProber.getDefaultProbeTable();
        UsbSerialProber prober = new UsbSerialProber(customTable);

        List<UsbSerialDriver> drivers = prober.findAllDrivers(usbManager);
        if (drivers.isEmpty()) {
            for (UsbDevice device : usbManager.getDeviceList().values()) {
                Log.d(TAG, "Unrecognized USB device: vendorId=" + device.getVendorId()
                        + " productId=" + device.getProductId());
            }
            return null;
        }
        return drivers.get(0);
    }

    private void findAndConnect() {
        UsbSerialDriver driver = findDriver();
        if (driver == null) {
            setStatus("No compatible serial adapter found. Plug in the UGREEN USB-RS232 cable.");
            Toast.makeText(this, "No serial device found", Toast.LENGTH_LONG).show();
            return;
        }

        UsbDevice device = driver.getDevice();
        if (!usbManager.hasPermission(device)) {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? PendingIntent.FLAG_MUTABLE
                    : 0;
            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                    this, 0, new Intent(ACTION_USB_PERMISSION), flags);
            usbManager.requestPermission(device, permissionIntent);
            setStatus("Requesting USB permission...");
        } else {
            openConnection(device);
        }
    }

    private void openConnection(UsbDevice device) {
        UsbSerialDriver foundDriver = null;
        for (UsbSerialDriver d : UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)) {
            if (d.getDevice().equals(device)) {
                foundDriver = d;
                break;
            }
        }
        if (foundDriver == null) {
            setStatus("Driver not found for device");
            return;
        }

        android.hardware.usb.UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            setStatus("Failed to open device connection");
            return;
        }

        usbSerialPort = foundDriver.getPorts().get(0);
        try {
            usbSerialPort.open(connection);
            int baud = baudRates[baudSpinner.getSelectedItemPosition()];
            usbSerialPort.setParameters(baud, UsbSerialPort.DATABITS_8,
                    UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

            ioManager = new SerialInputOutputManager(usbSerialPort, this);
            ioManager.start();

            setStatus("Scale connected @ " + baud + " baud (" + device.getDeviceName() + ")");
            connectButton.setText("Disconnect");
        } catch (IOException e) {
            Log.e(TAG, "Error opening port", e);
            setStatus("Error opening port: " + e.getMessage());
            closeConnection();
        }
    }

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
        connectButton.setText("Connect");
        setStatus("Disconnected");
    }

    private void sendData() {
        String text = sendEditText.getText().toString();
        if (text.isEmpty() || usbSerialPort == null) return;
        try {
            usbSerialPort.write(text.getBytes(StandardCharsets.UTF_8), 1000);
            sendEditText.setText("");
        } catch (IOException e) {
            Toast.makeText(this, "Send failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void setStatus(String s) {
        mainHandler.post(() -> statusText.setText(s));
    }

    private void appendData(String s) {
        mainHandler.post(() -> {
            dataText.append(s);
        });
    }

    // --- SerialInputOutputManager.Listener callbacks (background thread) ---

    @Override
    public void onNewData(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        appendData(text);

        // Try to parse a weight out of each incoming chunk. The AX-620 typically sends
        // one line per stable reading; partial chunks that don't contain a number are ignored.
        for (String line : text.split("\\r?\\n")) {
            WeightParser.ParsedWeight parsed = WeightParser.parse(line);
            if (parsed != null && parsed.stable) {
                onWeightRead(parsed.weightKg);
            }
        }
    }

    @Override
    public void onRunError(Exception e) {
        Log.e(TAG, "Serial connection lost", e);
        setStatus("Connection lost: " + e.getMessage());
        mainHandler.post(this::closeConnection);
    }

    // =====================================================================
    // RFID (Chainway UHF) setup
    // =====================================================================

    private void setupRfid() {
        rfidManager = new RfidManager();
        rfidManager.setListener(new RfidManager.Listener() {
            @Override
            public void onTagRead(String epc, String rssi) {
                mainHandler.post(() -> onTagScanned(epc));
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> {
                    rfidStatusText.setText("RFID: error — " + message);
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        });

        boolean ready = rfidManager.init(this);
        rfidStatusText.setText(ready ? "RFID: ready" : "RFID: unavailable on this device");
        rfidToggleButton.setEnabled(ready);

        rfidToggleButton.setOnClickListener(v -> {
            if (rfidManager.isScanning()) {
                rfidManager.stopScan();
                rfidToggleButton.setText("Start Scan");
                rfidStatusText.setText("RFID: stopped");
            } else {
                boolean started = rfidManager.startContinuousScan();
                if (started) {
                    rfidToggleButton.setText("Stop Scan");
                    rfidStatusText.setText("RFID: scanning...");
                }
            }
        });

        resetScanButton.setOnClickListener(v -> resetScan());
    }

    /** Called (on main thread) whenever the RFID reader picks up a tag. */
    private void onTagScanned(String epc) {
        if (epc == null || epc.equals(lastEpc)) return; // same tag repeatedly in continuous mode — ignore
        lastEpc = epc;
        lastWeightKg = null; // require a fresh weight reading for this newly scanned item
        renderScanResult(currentScanResult());
    }

    /** Called (on main thread, via onNewData) whenever the scale reports a stable weight. */
    private void onWeightRead(double weightKg) {
        lastWeightKg = weightKg;
        renderScanResult(currentScanResult());
    }

    private void resetScan() {
        lastEpc = null;
        lastWeightKg = null;
        renderScanResult(currentScanResult());
    }

    private ScanResult currentScanResult() {
        InventoryItem item = lastEpc != null ? InventoryRepository.findByEpc(lastEpc) : null;
        return new ScanResult(lastEpc, item, lastWeightKg, WEIGHT_TOLERANCE_KG);
    }

    // =====================================================================
    // UI rendering for the verdict card
    // =====================================================================

    private void renderScanResult(ScanResult result) {
        epcText.setText("EPC: " + (result.getEpc() != null ? result.getEpc() : "—"));

        switch (result.getVerdict()) {
            case AWAITING_TAG:
                setVerdictCard(R.color.status_unknown, "AWAITING TAG");
                itemNameText.setText("Item: —");
                skuText.setText("SKU: —");
                stockText.setText("In stock: —");
                expectedWeightText.setText("—");
                liveWeightText.setText("—");
                break;

            case UNKNOWN_TAG:
                setVerdictCard(R.color.status_unknown, "UNKNOWN TAG");
                itemNameText.setText("Item: not found in inventory");
                skuText.setText("SKU: —");
                stockText.setText("In stock: —");
                expectedWeightText.setText("—");
                liveWeightText.setText(formatWeight(result.getWeightKg()));
                break;

            case AWAITING_WEIGHT: {
                InventoryItem item = result.getItem();
                setVerdictCard(R.color.status_pending, "PLACE ITEM ON SCALE");
                itemNameText.setText("Item: " + item.getItemName());
                skuText.setText("SKU: " + item.getSku());
                stockText.setText("In stock: " + item.getStockQty());
                expectedWeightText.setText(formatWeight(item.getExpectedWeightKg()));
                liveWeightText.setText("—");
                break;
            }

            case MATCH: {
                InventoryItem item = result.getItem();
                setVerdictCard(R.color.status_match, "MATCH ✓");
                itemNameText.setText("Item: " + item.getItemName());
                skuText.setText("SKU: " + item.getSku());
                stockText.setText("In stock: " + item.getStockQty());
                expectedWeightText.setText(formatWeight(item.getExpectedWeightKg()));
                liveWeightText.setText(formatWeight(result.getWeightKg()));
                break;
            }

            case MISMATCH: {
                InventoryItem item = result.getItem();
                setVerdictCard(R.color.status_mismatch, "MISMATCH ✗");
                itemNameText.setText("Item: " + item.getItemName());
                skuText.setText("SKU: " + item.getSku());
                stockText.setText("In stock: " + item.getStockQty());
                expectedWeightText.setText(formatWeight(item.getExpectedWeightKg()));
                liveWeightText.setText(formatWeight(result.getWeightKg()));
                break;
            }
        }
    }

    private void setVerdictCard(int colorRes, String label) {
        verdictCard.setBackgroundColor(ContextCompat.getColor(this, colorRes));
        verdictLabel.setText(label);
    }

    private String formatWeight(Double kg) {
        if (kg == null) return "—";
        return String.format(Locale.US, "%.3f kg", kg);
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleLaunchIntent(intent);
    }

    private void handleLaunchIntent(Intent intent) {
        if (intent != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            findAndConnect();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeConnection();
        if (rfidManager != null) {
            rfidManager.release();
        }
        try {
            unregisterReceiver(usbReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }
}
