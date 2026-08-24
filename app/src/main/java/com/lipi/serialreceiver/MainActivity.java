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
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class MainActivity extends AppCompatActivity implements SerialInputOutputManager.Listener {

    private static final String TAG = "SerialMonitor";
    private static final String ACTION_USB_PERMISSION = "com.lipi.serialreceiver.USB_PERMISSION";
    private UsbManager usbManager;
    private UsbSerialPort usbSerialPort;
    private SerialInputOutputManager ioManager;
    private TextView statusText;
    private TextView dataText;
    private ScrollView scrollView;
    private Spinner baudSpinner;
    private Button connectButton;
    private Button clearButton;
    private Button sendButton;
    private EditText sendEditText;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final int[] baudRates = {300, 1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200};

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

        statusText = findViewById(R.id.statusText);
        dataText = findViewById(R.id.dataText);
        scrollView = findViewById(R.id.scrollView);
        baudSpinner = findViewById(R.id.baudSpinner);
        connectButton = findViewById(R.id.connectButton);
        clearButton = findViewById(R.id.clearButton);
        sendButton = findViewById(R.id.sendButton);
        sendEditText = findViewById(R.id.sendEditText);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        setupBaudSpinner();

        connectButton.setOnClickListener(v -> {
            if (usbSerialPort == null) {
                findAndConnect();
            } else {
                closeConnection();
            }
        });



        sendButton.setOnClickListener(v -> sendData());

        // Register USB broadcast receiver (permission result + attach/detach)
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

    private void setupBaudSpinner() {
        String[] labels = new String[baudRates.length];
        for (int i = 0; i < baudRates.length; i++) labels[i] = baudRates[i] + " baud";
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        baudSpinner.setAdapter(adapter);
        // Default to 9600, the most common RS232 default
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
            // fall back to scanning raw device list in case prober misses it
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
            // 8 data bits, 1 stop bit, no parity — standard RS232 default
            usbSerialPort.setParameters(baud, UsbSerialPort.DATABITS_8,
                    UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

            ioManager = new SerialInputOutputManager(usbSerialPort, this);
            ioManager.start();

            setStatus("Connected @ " + baud + " baud (" + device.getDeviceName() + ")");
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
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    // --- SerialInputOutputManager.Listener callbacks (background thread) ---

    @Override
    public void onNewData(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        appendData(text);
    }

    @Override
    public void onRunError(Exception e) {
        Log.e(TAG, "Serial connection lost", e);
        setStatus("Connection lost: " + e.getMessage());
        mainHandler.post(this::closeConnection);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeConnection();
        try {
            unregisterReceiver(usbReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }
}
