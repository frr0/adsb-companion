package com.heltec.adsb;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQ_CODE = 101;
    private static final UUID NUS_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID NUS_RX_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID NUS_TX_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner leScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic rxChar;

    private boolean isScanning = false;
    private boolean isConnected = false;
    private boolean isStreamLiveEnabled = false; // Default OFF as strictly requested

    private long currentDialHz = 1089800000L;

    // Header & Connection UI
    private View statusDot;
    private View btnHeaderStatus, btnHeaderConnect;
    private ImageView ivHeaderConnect;
    private TextView tvStatus;

    // Hero & Telemetry UI
    private TextView tvHeroFreqPill, tvCallsign, tvHeroSubtitle;
    private TextView tvAltitude, tvSpeed, tvCoords, tvRawOled, tvBoardInfo, tvBurstCount;
    private View btnConnect, circleConnect, btnLiveMode, btnPrevAircraft, btnStatus;
    private ImageView ivConnectIcon;
    private TextView tvConnectLabel;

    // Controls Tab UI
    private TextView tvDialFreq;
    private MaterialButton btnTap, btnHold, btnDouble;
    private MaterialButton btnTuneDown, btnTuneUp, btnFreqDefault, btnSaveDial;
    private View rowPrevAircraft, rowLiveMode, rowStatus, btnClearHistory;

    // Logs Tab UI
    private TextView tvLogCountHero, tvStreamBadge, tvLogStats, tvLogs;
    private MaterialButton btnToggleStream, btnDump, btnSaveFile, btnClear;
    private ScrollView scrollLogs;

    // Tab Containers & Bottom Nav
    private View tabHome, tabControls, tabLogs;
    private BottomNavigationView bottomNav;

    private final StringBuilder incomingBuffer = new StringBuilder();
    private final StringBuilder logAccumulator = new StringBuilder();
    private int logLineCount = 0;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupNavigation();

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) {
            bluetoothAdapter = bm.getAdapter();
        }

        // Connect / Disconnect / Stop Scan (Hero + Header)
        View.OnClickListener connectClickListener = v -> {
            if (isConnected) {
                disconnectDevice();
            } else if (!isScanning) {
                checkPermissionsAndScan();
            } else {
                stopScan();
            }
        };
        btnConnect.setOnClickListener(connectClickListener);
        btnHeaderConnect.setOnClickListener(connectClickListener);

        // 1. Physical PRG button handlers
        btnTap.setOnClickListener(v -> sendBleCommandWithToast("tap\n", "PRG Button: Tap"));
        btnHold.setOnClickListener(v -> sendBleCommandWithToast("hold\n", "PRG Button: Hold"));
        btnDouble.setOnClickListener(v -> sendBleCommandWithToast("double\n", "PRG Button: Double Click"));

        // 2. Direct board options (accessible from both Home quick actions and Controls list)
        View.OnClickListener prevListener = v -> sendBleCommandWithToast("prev\n", "Showing previous aircraft");
        View.OnClickListener liveListener = v -> sendBleCommandWithToast("live\n", "Returning to real-time mode (Live)");
        View.OnClickListener statusListener = v -> sendBleCommandWithToast("status\n", "Requesting hardware status...");

        btnPrevAircraft.setOnClickListener(prevListener);
        rowPrevAircraft.setOnClickListener(prevListener);

        btnLiveMode.setOnClickListener(liveListener);
        rowLiveMode.setOnClickListener(liveListener);

        btnStatus.setOnClickListener(statusListener);
        rowStatus.setOnClickListener(statusListener);
        btnHeaderStatus.setOnClickListener(statusListener);

        btnClearHistory.setOnClickListener(v -> sendBleCommandWithToast("clear\n", "Clearing history and memory..."));

        btnTuneDown.setOnClickListener(v -> {
            currentDialHz = Math.max(150000000L, currentDialHz - 100000L);
            updateFrequencyDisplays();
            sendBleCommand("tune " + currentDialHz + "\n");
            Toast.makeText(this, String.format(Locale.getDefault(), "Tuning: %.2f MHz", currentDialHz / 1e6), Toast.LENGTH_SHORT).show();
        });

        btnTuneUp.setOnClickListener(v -> {
            currentDialHz = Math.min(1150000000L, currentDialHz + 100000L);
            updateFrequencyDisplays();
            sendBleCommand("tune " + currentDialHz + "\n");
            Toast.makeText(this, String.format(Locale.getDefault(), "Tuning: %.2f MHz", currentDialHz / 1e6), Toast.LENGTH_SHORT).show();
        });

        btnFreqDefault.setOnClickListener(v -> {
            currentDialHz = 1089800000L;
            updateFrequencyDisplays();
            sendBleCommand("tune 1089800000\n");
            Toast.makeText(this, "Restored default frequency: 1089.8 MHz", Toast.LENGTH_SHORT).show();
        });

        btnSaveDial.setOnClickListener(v -> sendBleCommandWithToast("save_dial\n", "Saving frequency to Flash memory..."));

        // 3. Toggle Log Acquisition Button (Default OFF)
        btnToggleStream.setOnClickListener(v -> {
            isStreamLiveEnabled = !isStreamLiveEnabled;
            if (isStreamLiveEnabled) {
                sendBleCommand("stream on\n");
                Toast.makeText(this, "Log acquisition ENABLED", Toast.LENGTH_SHORT).show();
            } else {
                sendBleCommand("stream off\n");
                Toast.makeText(this, "Log acquisition DISABLED", Toast.LENGTH_SHORT).show();
            }
            updateStreamButtonUI();
        });

        // 4. Log actions
        btnDump.setOnClickListener(v -> {
            if (!isConnected || rxChar == null) {
                Toast.makeText(this, "Not connected to Heltec board", Toast.LENGTH_SHORT).show();
                return;
            }
            sendBleCommand("dump\n");
            Toast.makeText(this, "Downloading stored logs from Flash memory...", Toast.LENGTH_SHORT).show();
        });

        btnSaveFile.setOnClickListener(v -> saveLogsToFile());

        btnClear.setOnClickListener(v -> {
            logAccumulator.setLength(0);
            logLineCount = 0;
            tvLogs.setText("");
            updateLogCountText();
            Toast.makeText(this, "Log console cleared", Toast.LENGTH_SHORT).show();
        });

        updateFrequencyDisplays();
        updateConnectionUI();
        updateStreamButtonUI();
    }

    private void bindViews() {
        statusDot = findViewById(R.id.statusDot);
        btnHeaderStatus = findViewById(R.id.btnHeaderStatus);
        btnHeaderConnect = findViewById(R.id.btnHeaderConnect);
        ivHeaderConnect = findViewById(R.id.ivHeaderConnect);
        tvStatus = findViewById(R.id.tvStatus);

        tvHeroFreqPill = findViewById(R.id.tvHeroFreqPill);
        tvCallsign = findViewById(R.id.tvCallsign);
        tvHeroSubtitle = findViewById(R.id.tvHeroSubtitle);
        tvAltitude = findViewById(R.id.tvAltitude);
        tvSpeed = findViewById(R.id.tvSpeed);
        tvCoords = findViewById(R.id.tvCoords);
        tvRawOled = findViewById(R.id.tvRawOled);
        tvBoardInfo = findViewById(R.id.tvBoardInfo);
        tvBurstCount = findViewById(R.id.tvBurstCount);

        btnConnect = findViewById(R.id.btnConnect);
        circleConnect = findViewById(R.id.circleConnect);
        ivConnectIcon = findViewById(R.id.ivConnectIcon);
        tvConnectLabel = findViewById(R.id.tvConnectLabel);
        btnLiveMode = findViewById(R.id.btnLiveMode);
        btnPrevAircraft = findViewById(R.id.btnPrevAircraft);
        btnStatus = findViewById(R.id.btnStatus);

        tvDialFreq = findViewById(R.id.tvDialFreq);
        btnTap = findViewById(R.id.btnTap);
        btnHold = findViewById(R.id.btnHold);
        btnDouble = findViewById(R.id.btnDouble);
        btnTuneDown = findViewById(R.id.btnTuneDown);
        btnTuneUp = findViewById(R.id.btnTuneUp);
        btnFreqDefault = findViewById(R.id.btnFreqDefault);
        btnSaveDial = findViewById(R.id.btnSaveDial);
        rowPrevAircraft = findViewById(R.id.rowPrevAircraft);
        rowLiveMode = findViewById(R.id.rowLiveMode);
        rowStatus = findViewById(R.id.rowStatus);
        btnClearHistory = findViewById(R.id.btnClearHistory);

        tvLogCountHero = findViewById(R.id.tvLogCountHero);
        tvStreamBadge = findViewById(R.id.tvStreamBadge);
        tvLogStats = findViewById(R.id.tvLogStats);
        tvLogs = findViewById(R.id.tvLogs);
        scrollLogs = findViewById(R.id.scrollLogs);
        btnToggleStream = findViewById(R.id.btnToggleStream);
        btnDump = findViewById(R.id.btnDump);
        btnSaveFile = findViewById(R.id.btnSaveFile);
        btnClear = findViewById(R.id.btnClear);

        tabHome = findViewById(R.id.tabHome);
        tabControls = findViewById(R.id.tabControls);
        tabLogs = findViewById(R.id.tabLogs);
        bottomNav = findViewById(R.id.bottomNav);
    }

    private void setupNavigation() {
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            tabHome.setVisibility(id == R.id.nav_home ? View.VISIBLE : View.GONE);
            tabControls.setVisibility(id == R.id.nav_controls ? View.VISIBLE : View.GONE);
            tabLogs.setVisibility(id == R.id.nav_logs ? View.VISIBLE : View.GONE);
            return true;
        });
    }

    private void updateFrequencyDisplays() {
        double mhz = currentDialHz / 1e6;
        tvHeroFreqPill.setText(String.format(Locale.getDefault(), "%.2f MHz · SX1262", mhz));
        tvDialFreq.setText(String.format(Locale.getDefault(), "%.2f MHz", mhz));
    }

    private void updateConnectionUI() {
        int blue = ContextCompat.getColor(this, R.color.rev_blue);
        int green = ContextCompat.getColor(this, R.color.rev_green);
        int amber = ContextCompat.getColor(this, R.color.rev_amber);
        int red = ContextCompat.getColor(this, R.color.rev_red);
        int muted = ContextCompat.getColor(this, R.color.rev_text_tertiary);
        int onBlue = ContextCompat.getColor(this, R.color.rev_text_on_blue);

        if (isConnected) {
            statusDot.setBackgroundTintList(ColorStateList.valueOf(green));
            tvConnectLabel.setText("Disconnect");
            tvConnectLabel.setTextColor(red);
            circleConnect.setBackgroundResource(R.drawable.bg_circle_red);
            ivConnectIcon.setImageTintList(ColorStateList.valueOf(red));
            btnHeaderConnect.setBackgroundResource(R.drawable.bg_circle_green);
            ivHeaderConnect.setImageTintList(ColorStateList.valueOf(green));
        } else if (isScanning) {
            statusDot.setBackgroundTintList(ColorStateList.valueOf(amber));
            tvConnectLabel.setText("Stop Scan");
            tvConnectLabel.setTextColor(amber);
            circleConnect.setBackgroundResource(R.drawable.bg_circle_amber);
            ivConnectIcon.setImageTintList(ColorStateList.valueOf(amber));
            btnHeaderConnect.setBackgroundResource(R.drawable.bg_circle_amber);
            ivHeaderConnect.setImageTintList(ColorStateList.valueOf(amber));
        } else {
            statusDot.setBackgroundTintList(ColorStateList.valueOf(muted));
            tvConnectLabel.setText("Connect");
            tvConnectLabel.setTextColor(blue);
            circleConnect.setBackgroundResource(R.drawable.bg_circle_blue);
            ivConnectIcon.setImageTintList(ColorStateList.valueOf(onBlue));
            btnHeaderConnect.setBackgroundResource(R.drawable.bg_circle_blue_soft);
            ivHeaderConnect.setImageTintList(ColorStateList.valueOf(blue));
        }
    }

    private void updateStreamButtonUI() {
        if (isStreamLiveEnabled) {
            btnToggleStream.setText("Log Acquisition: ON (Tap to Disable)");
            btnToggleStream.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.rev_green)));
            btnToggleStream.setTextColor(ContextCompat.getColor(this, R.color.rev_text_on_blue));
            tvStreamBadge.setText("STREAM LIVE");
            tvStreamBadge.setTextColor(ContextCompat.getColor(this, R.color.rev_green));
            tvStreamBadge.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.rev_green_soft)));
        } else {
            btnToggleStream.setText("Log Acquisition: OFF (Tap to Enable)");
            btnToggleStream.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.rev_surface_secondary)));
            btnToggleStream.setTextColor(ContextCompat.getColor(this, R.color.rev_text_primary));
            tvStreamBadge.setText("STREAM OFF");
            tvStreamBadge.setTextColor(ContextCompat.getColor(this, R.color.rev_text_secondary));
            tvStreamBadge.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.rev_surface_secondary)));
        }
        updateLogCountText();
    }

    private void updateLogCountText() {
        String streamState = isStreamLiveEnabled ? "Live Stream: ON" : "Live Stream: OFF (Default)";
        tvLogCountHero.setText(String.valueOf(logLineCount));
        tvLogStats.setText(String.format(Locale.getDefault(), "Stored logs: %d lines | %s", logLineCount, streamState));
    }

    private void sendBleCommandWithToast(String cmd, String toastMsg) {
        if (!isConnected || rxChar == null) {
            Toast.makeText(this, "Connect to the board via Bluetooth first", Toast.LENGTH_SHORT).show();
            return;
        }
        sendBleCommand(cmd);
        Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show();
    }

    private void checkPermissionsAndScan() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMISSION_REQ_CODE);
        } else {
            startScan();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) allGranted = false;
            }
            if (allGranted) startScan();
            else Toast.makeText(this, "Bluetooth permissions are required to scan", Toast.LENGTH_LONG).show();
        }
    }

    private void startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Enable Bluetooth before scanning", Toast.LENGTH_SHORT).show();
            return;
        }
        leScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (leScanner == null) return;

        isScanning = true;
        updateConnectionUI();
        tvStatus.setText("Status: Scanning for 'Heltec-ADSB'...");

        mainHandler.postDelayed(() -> {
            if (isScanning && !isConnected) {
                stopScan();
                tvStatus.setText("Status: No device found. Try again.");
            }
        }, 12000);

        try {
            leScanner.startScan(scanCallback);
        } catch (SecurityException ignored) {}
    }

    private void stopScan() {
        if (isScanning && leScanner != null) {
            try {
                leScanner.stopScan(scanCallback);
            } catch (SecurityException ignored) {}
            isScanning = false;
            updateConnectionUI();
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = null;
            try {
                name = device.getName();
            } catch (SecurityException ignored) {}

            if (name != null && (name.contains("Heltec") || name.contains("ADSB") || name.contains("adsb"))) {
                stopScan();
                connectToDevice(device);
            }
        }
    };

    private void connectToDevice(BluetoothDevice device) {
        mainHandler.post(() -> tvStatus.setText("Status: Connecting to " + device.getAddress() + "..."));
        try {
            bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException ignored) {}
    }

    private void disconnectDevice() {
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (SecurityException ignored) {}
            bluetoothGatt = null;
        }
        isConnected = false;
        isScanning = false;
        updateConnectionUI();
        tvStatus.setText("Status: Disconnected");
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true;
                mainHandler.post(() -> {
                    tvStatus.setText("Status: Connected, discovering services...");
                    updateConnectionUI();
                });
                try {
                    gatt.discoverServices();
                } catch (SecurityException ignored) {}
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false;
                mainHandler.post(() -> {
                    tvStatus.setText("Status: Disconnected");
                    updateConnectionUI();
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService nus = gatt.getService(NUS_SERVICE_UUID);
                if (nus != null) {
                    rxChar = nus.getCharacteristic(NUS_RX_UUID);
                    BluetoothGattCharacteristic txChar = nus.getCharacteristic(NUS_TX_UUID);
                    if (txChar != null) {
                        try {
                            gatt.setCharacteristicNotification(txChar, true);
                            BluetoothGattDescriptor desc = txChar.getDescriptor(CCCD_UUID);
                            if (desc != null) {
                                desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                gatt.writeDescriptor(desc);
                            }
                        } catch (SecurityException ignored) {}
                    }
                    mainHandler.post(() -> {
                        tvStatus.setText("Status: Connected to Heltec-ADSB (Ready)");
                        updateConnectionUI();
                        // Guarantee stream is OFF by default on board as well
                        sendBleCommand(isStreamLiveEnabled ? "stream on\n" : "stream off\n");
                        sendBleCommand("status\n");
                    });
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (NUS_TX_UUID.equals(characteristic.getUuid())) {
                byte[] data = characteristic.getValue();
                if (data != null && data.length > 0) {
                    String str = new String(data, StandardCharsets.UTF_8);
                    processIncomingChunk(str);
                }
            }
        }
    };

    private void sendBleCommand(String cmd) {
        if (bluetoothGatt != null && rxChar != null) {
            try {
                rxChar.setValue(cmd.getBytes(StandardCharsets.UTF_8));
                bluetoothGatt.writeCharacteristic(rxChar);
            } catch (SecurityException ignored) {}
        }
    }

    private void processIncomingChunk(String chunk) {
        incomingBuffer.append(chunk);
        int newlineIdx;
        while ((newlineIdx = incomingBuffer.indexOf("\n")) != -1) {
            String line = incomingBuffer.substring(0, newlineIdx).trim();
            incomingBuffer.delete(0, newlineIdx + 1);
            if (!line.isEmpty()) {
                mainHandler.post(() -> handleLine(line));
            }
        }
    }

    private void handleLine(String line) {
        if (line.startsWith("SXSCREEN ")) {
            // Live OLED screen lines replica (always updated so screen is live)
            String payload = line.substring(9);
            String[] parts = payload.split("\\|", -1);
            StringBuilder oledText = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (!parts[i].isEmpty()) {
                    oledText.append(parts[i]).append("\n");
                }
            }
            tvRawOled.setText(oledText.toString().trim());

            if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                String callsign = parts[1].trim();
                tvCallsign.setText(callsign);
                tvHeroSubtitle.setText("Active flight detected");
            }
            if (parts.length > 3 && !parts[3].trim().isEmpty()) {
                String part = parts[3].trim();
                if (part.contains("ft")) tvAltitude.setText(part);
                if (part.contains("kt")) tvSpeed.setText(part);
            }
            if (parts.length > 5 && !parts[5].trim().isEmpty()) {
                String coords = parts[5].trim();
                tvCoords.setText(coords);
                tvHeroSubtitle.setText(coords);
            }
        } else if (line.startsWith("SXSTATUS ")) {
            long bursts = extractParam(line, "bursts=");
            long dial = extractParam(line, "dial=");
            long ready = extractParam(line, "ready=");
            if (dial > 0) {
                currentDialHz = dial;
                updateFrequencyDisplays();
            }
            tvBoardInfo.setText(String.format(Locale.getDefault(),
                    "Freq: %.2f MHz | Radio: %s | RF Packets: %d",
                    currentDialHz / 1e6, ready == 1 ? "Ready" : "Init", bursts));
            tvBurstCount.setText(String.format(Locale.getDefault(), "%d pkt", bursts));
            tvBurstCount.setTextColor(ContextCompat.getColor(this,
                    ready == 1 ? R.color.rev_green : R.color.rev_amber));
        } else if (line.startsWith("SXMSG ")) {
            Toast.makeText(this, line.substring(6), Toast.LENGTH_SHORT).show();
        } else if (line.startsWith("SXLOG ")) {
            // Explicitly requested flash log dump
            logAccumulator.append(line).append("\n");
            logLineCount++;
            updateLogCountText();
            tvLogs.append(line + "\n");
            scrollLogs.post(() -> scrollLogs.fullScroll(View.FOCUS_DOWN));
        } else if (line.startsWith("SXOBS ") || line.startsWith("SXCARD ")) {
            // Live Observation burst: displayed ONLY if stream live is activated by the user
            if (isStreamLiveEnabled) {
                logAccumulator.append(line).append("\n");
                logLineCount++;
                updateLogCountText();
                tvLogs.append(line + "\n");
                scrollLogs.post(() -> scrollLogs.fullScroll(View.FOCUS_DOWN));
            }

            // Extract aircraft flight telemetry for the UI cards
            int jsonStart = line.indexOf('{');
            if (jsonStart != -1) {
                try {
                    JSONObject obj = new JSONObject(line.substring(jsonStart));
                    if (obj.has("callsign")) {
                        tvCallsign.setText(obj.getString("callsign"));
                        tvHeroSubtitle.setText("Active flight detected");
                    }
                    if (obj.has("altitude")) {
                        tvAltitude.setText(obj.getInt("altitude") + " ft");
                    }
                    if (obj.has("speed")) {
                        tvSpeed.setText(obj.getInt("speed") + " kt");
                    }
                    if (obj.has("lat") && obj.has("lon")) {
                        String pos = String.format(Locale.US, "Pos: %.4f°, %.4f°",
                                obj.getDouble("lat"), obj.getDouble("lon"));
                        tvCoords.setText(pos);
                        tvHeroSubtitle.setText(pos);
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    private long extractParam(String line, String key) {
        int idx = line.indexOf(key);
        if (idx == -1) return 0;
        int end = line.indexOf(' ', idx);
        if (end == -1) end = line.length();
        try {
            return Long.parseLong(line.substring(idx + key.length(), end).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private void saveLogsToFile() {
        if (logAccumulator.length() == 0) {
            Toast.makeText(this, "No logs available to save", Toast.LENGTH_SHORT).show();
            return;
        }

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = "adsb_log_" + timeStamp + ".txt";

        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, fileName);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(logAccumulator.toString().getBytes(StandardCharsets.UTF_8));
            fos.close();

            Toast.makeText(this, "File saved to:\nDownload/" + fileName, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error saving file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnectDevice();
    }
}
