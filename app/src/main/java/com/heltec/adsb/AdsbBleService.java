package com.heltec.adsb;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
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
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground Service keeping the Heltec ADS-B BLE connection alive in background,
 * recording flights into SQLite continuously, and firing smart aircraft alerts.
 */
public class AdsbBleService extends Service {

    public static final String ACTION_DISCONNECT = "com.heltec.adsb.ACTION_DISCONNECT";
    public static final String PREFS_NAME = "adsb_prefs";
    public static final String PREF_ALERT_LOW_ALT = "alert_low_alt";
    public static final String PREF_ALERT_GOV_MIL = "alert_gov_mil";
    public static final String PREF_ALERT_ALL = "alert_all";

    private static final String CHANNEL_SERVICE = "adsb_ble_service";
    private static final String CHANNEL_ALERTS = "adsb_flight_alerts";
    private static final int NOTIF_ID_SERVICE = 1001;
    private static final int NOTIF_ID_ALERT_BASE = 2000;
    private static final int LOW_ALT_THRESHOLD_FT = 5000;
    private static final long ALERT_COOLDOWN_MS = 10 * 60 * 1000L;

    private static final UUID NUS_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID NUS_RX_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID NUS_TX_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    public interface Listener {
        void onConnectionStateChanged(boolean connected, boolean scanning, boolean connecting, String statusText);
        void onStatusUpdate(long dialHz, long bursts, boolean ready);
        void onFlightsUpdated();
        void onLogLine(String line);
        void onBoardMessage(String message);
    }

    public class LocalBinder extends Binder {
        public AdsbBleService getService() {
            return AdsbBleService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner leScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic rxChar;
    private FlightDatabaseHelper flightDb;
    private Listener listener;

    private boolean isScanning = false;
    private boolean isConnecting = false;
    private boolean isConnected = false;
    private boolean isStreamLiveEnabled = false;
    private String statusText = "Status: Disconnected";
    private long currentDialHz = 1089800000L;
    private long lastBursts = 0L;
    private boolean lastReady = false;
    private String lastNotifFlightSummary = "Listening for aircraft...";

    private final Queue<String> bleWriteQueue = new ArrayDeque<>();
    private boolean isBleWriteInFlight = false;
    private final StringBuilder incomingBuffer = new StringBuilder();

    private boolean isSilentSyncInProgress = false;
    private boolean showSyncToastOnFinish = false;
    private final Map<String, SyncAggregate> pendingSyncAircraft = new LinkedHashMap<>();
    private final Map<String, Long> alertCooldownMap = new HashMap<>();
    private int alertNotifSeq = 0;

    private String lastScreenFingerprint = "";
    private long lastScreenRecordMs = 0L;
    private String lastObsIcaoOrCallsign = "";
    private long lastObsTimestampMs = 0L;

    private static final class SyncAggregate {
        String icao = "";
        String callsign = "";
        int lastAltitude = -1;
        int lastSpeed = -1;
        double lat = Double.NaN;
        double lon = Double.NaN;
        long timestampMs = -1L;
    }

    private final Runnable bleWriteTimeoutRunnable = () -> {
        synchronized (bleWriteQueue) {
            isBleWriteInFlight = false;
        }
        drainBleWriteQueue();
    };

    private final Runnable scanTimeoutRunnable = () -> {
        if (isScanning && !isConnected && !isConnecting) {
            stopScan();
            updateStatus("Status: No device found. Try again.");
            stopForegroundCompat();
        }
    };

    private final Runnable syncTimeoutRunnable = this::finishBoardSync;

    @Override
    public void onCreate() {
        super.onCreate();
        flightDb = new FlightDatabaseHelper(this);
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) {
            bluetoothAdapter = bm.getAdapter();
        }
        createNotificationChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            disconnectDevice();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void setListener(Listener l) {
        this.listener = l;
        if (l != null) {
            l.onConnectionStateChanged(isConnected, isScanning, isConnecting, statusText);
            l.onStatusUpdate(currentDialHz, lastBursts, lastReady);
        }
    }

    public boolean isConnected() { return isConnected; }
    public boolean isScanning() { return isScanning; }
    public boolean isConnecting() { return isConnecting; }
    public boolean isStreamLiveEnabled() { return isStreamLiveEnabled; }
    public long getCurrentDialHz() { return currentDialHz; }

    public void setStreamLiveEnabled(boolean enabled) {
        isStreamLiveEnabled = enabled;
        sendBleCommand(enabled ? "stream on\n" : "stream off\n");
    }

    public void setDialHz(long hz) {
        currentDialHz = hz;
        sendBleCommand("tune " + hz + "\n");
        updateServiceNotification();
    }

    public void startManualDump() {
        isSilentSyncInProgress = false;
        sendBleCommand("dump\n");
    }

    public void startScanAndConnect() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            notifyBoardMessage("Enable Bluetooth before scanning");
            return;
        }
        leScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (leScanner == null) return;

        startForegroundCompat();
        closeGattInternal();

        isScanning = true;
        isConnecting = false;
        updateStatus("Status: Scanning for 'Heltec-ADSB'...");

        mainHandler.removeCallbacks(scanTimeoutRunnable);
        mainHandler.postDelayed(scanTimeoutRunnable, 12000L);

        try {
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            leScanner.startScan(null, settings, scanCallback);
        } catch (SecurityException ignored) {}
    }

    public void stopScan() {
        mainHandler.removeCallbacks(scanTimeoutRunnable);
        if (isScanning && leScanner != null) {
            try {
                leScanner.stopScan(scanCallback);
            } catch (SecurityException ignored) {}
            isScanning = false;
            notifyConnectionState();
        }
    }

    public void disconnectDevice() {
        stopScan();
        closeGattInternal();
        isConnected = false;
        isConnecting = false;
        isScanning = false;
        updateStatus("Status: Disconnected");
        stopForegroundCompat();
        stopSelf();
    }

    public void syncFlightsFromBoard(boolean showToast) {
        if (!isConnected || rxChar == null) {
            if (showToast) notifyBoardMessage("Connect to the Heltec board first");
            return;
        }
        isSilentSyncInProgress = true;
        showSyncToastOnFinish = showToast;
        pendingSyncAircraft.clear();
        mainHandler.removeCallbacks(syncTimeoutRunnable);
        mainHandler.postDelayed(syncTimeoutRunnable, 4000L);
        sendBleCommand("sync\n");
        if (showToast) {
            notifyBoardMessage("Syncing aircraft from Heltec board...");
        }
    }

    public void sendBleCommand(String cmd) {
        if (!isConnected || bluetoothGatt == null || rxChar == null) return;
        synchronized (bleWriteQueue) {
            bleWriteQueue.offer(cmd);
        }
        drainBleWriteQueue();
    }

    private void drainBleWriteQueue() {
        String nextCmd;
        synchronized (bleWriteQueue) {
            if (isBleWriteInFlight || bleWriteQueue.isEmpty()) return;
            if (!isConnected || bluetoothGatt == null || rxChar == null) {
                bleWriteQueue.clear();
                return;
            }
            nextCmd = bleWriteQueue.poll();
            isBleWriteInFlight = true;
        }
        try {
            rxChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            rxChar.setValue(nextCmd.getBytes(StandardCharsets.UTF_8));
            boolean ok = bluetoothGatt.writeCharacteristic(rxChar);
            if (!ok) {
                synchronized (bleWriteQueue) {
                    isBleWriteInFlight = false;
                }
                mainHandler.postDelayed(this::drainBleWriteQueue, 120L);
            } else {
                mainHandler.removeCallbacks(bleWriteTimeoutRunnable);
                mainHandler.postDelayed(bleWriteTimeoutRunnable, 1500L);
            }
        } catch (SecurityException e) {
            synchronized (bleWriteQueue) {
                isBleWriteInFlight = false;
            }
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (!isScanning || isConnecting || isConnected) return;
            BluetoothDevice device = result.getDevice();
            String name = null;
            ScanRecord record = result.getScanRecord();
            if (record != null && record.getDeviceName() != null) {
                name = record.getDeviceName();
            }
            if (name == null) {
                try {
                    name = device.getName();
                } catch (SecurityException ignored) {}
            }
            if (name != null && (name.contains("Heltec") || name.contains("ADSB") || name.contains("adsb"))) {
                isConnecting = true;
                stopScan();
                connectToDevice(device);
            }
        }
    };

    private void connectToDevice(BluetoothDevice device) {
        closeGattInternal();
        updateStatus("Status: Connecting to " + device.getAddress() + "...");
        try {
            bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            if (bluetoothGatt == null) {
                isConnecting = false;
                updateStatus("Status: Failed to open BLE connection");
                stopForegroundCompat();
            }
        } catch (SecurityException e) {
            isConnecting = false;
            notifyConnectionState();
        }
    }

    private void closeGattInternal() {
        mainHandler.removeCallbacks(bleWriteTimeoutRunnable);
        mainHandler.removeCallbacks(syncTimeoutRunnable);
        synchronized (bleWriteQueue) {
            bleWriteQueue.clear();
            isBleWriteInFlight = false;
        }
        synchronized (incomingBuffer) {
            incomingBuffer.setLength(0);
        }
        rxChar = null;
        isSilentSyncInProgress = false;
        if (bluetoothGatt != null) {
            try { bluetoothGatt.disconnect(); } catch (SecurityException ignored) {}
            try { bluetoothGatt.close(); } catch (SecurityException ignored) {}
            bluetoothGatt = null;
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true;
                isConnecting = false;
                synchronized (incomingBuffer) {
                    incomingBuffer.setLength(0);
                }
                updateStatus("Status: Connected, discovering services...");
                mainHandler.postDelayed(() -> {
                    if (bluetoothGatt == gatt && isConnected) {
                        try { gatt.discoverServices(); } catch (SecurityException ignored) {}
                    }
                }, 150L);
            } else {
                boolean wasError = (status != BluetoothGatt.GATT_SUCCESS);
                isConnected = false;
                isConnecting = false;
                mainHandler.post(() -> {
                    if (bluetoothGatt == gatt) {
                        closeGattInternal();
                    } else {
                        try { gatt.close(); } catch (SecurityException ignored) {}
                    }
                    updateStatus(wasError
                            ? "Status: Disconnected (BLE " + status + ") - Tap Connect"
                            : "Status: Disconnected");
                    stopForegroundCompat();
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
                                return;
                            }
                        } catch (SecurityException ignored) {}
                    }
                    onBleReady();
                }
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (CCCD_UUID.equals(descriptor.getUuid())) {
                onBleReady();
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            mainHandler.post(() -> {
                mainHandler.removeCallbacks(bleWriteTimeoutRunnable);
                synchronized (bleWriteQueue) {
                    isBleWriteInFlight = false;
                }
                drainBleWriteQueue();
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (NUS_TX_UUID.equals(characteristic.getUuid())) {
                byte[] data = characteristic.getValue();
                if (data != null && data.length > 0) {
                    processIncomingChunk(new String(data, StandardCharsets.UTF_8));
                }
            }
        }
    };

    private void onBleReady() {
        mainHandler.post(() -> {
            if (!isConnected) return;
            updateStatus("Status: Connected to Heltec-ADSB (Ready)");
            sendBleCommand(isStreamLiveEnabled ? "stream on\n" : "stream off\n");
            sendBleCommand("status\n");
            syncFlightsFromBoard(false);
        });
    }

    private void processIncomingChunk(String chunk) {
        List<String> lines = new ArrayList<>();
        synchronized (incomingBuffer) {
            incomingBuffer.append(chunk);
            int newlineIdx;
            while ((newlineIdx = incomingBuffer.indexOf("\n")) != -1) {
                String line = incomingBuffer.substring(0, newlineIdx).trim();
                incomingBuffer.delete(0, newlineIdx + 1);
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
        }
        if (!lines.isEmpty()) {
            mainHandler.post(() -> {
                for (String line : lines) {
                    handleLine(line);
                }
            });
        }
    }

    private void handleLine(String line) {
        if (line.startsWith("SXSYNCEND")) {
            finishBoardSync();
            String remainder = line.substring(9).trim();
            if (!remainder.isEmpty()) handleLine(remainder);
            return;
        }
        if (line.startsWith("SXEND")) {
            finishBoardSync();
            String remainder = line.substring(5).trim();
            if (!remainder.isEmpty()) handleLine(remainder);
            return;
        }
        if (line.startsWith("SXFLIGHT ")) {
            handleSyncFlightLine(line);
            return;
        }
        if (line.startsWith("SXSCREEN ")) {
            handleScreenLine(line);
        } else if (line.startsWith("SXSTATUS ")) {
            long bursts = extractParam(line, "bursts=");
            long dial = extractParam(line, "dial=");
            long ready = extractParam(line, "ready=");
            if (dial > 0) currentDialHz = dial;
            lastBursts = bursts;
            lastReady = (ready == 1);
            if (listener != null) {
                listener.onStatusUpdate(currentDialHz, lastBursts, lastReady);
            }
            updateServiceNotification();
        } else if (line.startsWith("SXMSG ")) {
            notifyBoardMessage(line.substring(6));
        } else if (line.startsWith("SXLOG ")) {
            ingestJsonObservation(line, true);
            if (isSilentSyncInProgress) {
                mainHandler.removeCallbacks(syncTimeoutRunnable);
                mainHandler.postDelayed(syncTimeoutRunnable, 2000L);
            } else if (listener != null) {
                listener.onLogLine(line);
            }
        } else if (line.startsWith("SXOBS ") || line.startsWith("SXCARD ")) {
            if (isStreamLiveEnabled && listener != null) {
                listener.onLogLine(line);
            }
            ingestJsonObservation(line, false);
        }
    }

    private void handleSyncFlightLine(String line) {
        int jsonStart = line.indexOf('{');
        if (jsonStart == -1) return;
        try {
            JSONObject obj = new JSONObject(line.substring(jsonStart));
            String icao = obj.optString("icao", "").trim().toUpperCase(Locale.US);
            String callsign = obj.optString("callsign", "").trim().toUpperCase(Locale.US);
            int alt = obj.has("alt") ? obj.optInt("alt", -1) : -1;
            int spd = obj.has("spd") ? obj.optInt("spd", -1) : -1;
            double lat = (obj.has("lat") && obj.has("lon")) ? obj.optDouble("lat", Double.NaN) : Double.NaN;
            double lon = (obj.has("lat") && obj.has("lon")) ? obj.optDouble("lon", Double.NaN) : Double.NaN;
            long ageSec = obj.has("age") ? obj.optLong("age", -1L) : -1L;

            if (ModeSDecoder.isValidIcao(icao) || ModeSDecoder.isValidAircraftIdentifier(callsign)) {
                long ts = ageSec >= 0 ? (System.currentTimeMillis() - ageSec * 1000L) : -1L;
                if (isSilentSyncInProgress) {
                    String key = !icao.isEmpty() ? icao : callsign;
                    SyncAggregate agg = pendingSyncAircraft.get(key);
                    if (agg == null) {
                        agg = new SyncAggregate();
                        agg.icao = icao;
                        pendingSyncAircraft.put(key, agg);
                    }
                    if (ModeSDecoder.isValidAircraftIdentifier(callsign)) agg.callsign = callsign;
                    if (alt >= 0) agg.lastAltitude = alt;
                    if (spd >= 0) agg.lastSpeed = spd;
                    if (!Double.isNaN(lat) && !Double.isNaN(lon)) {
                        agg.lat = lat;
                        agg.lon = lon;
                    }
                    agg.timestampMs = ts;
                    mainHandler.removeCallbacks(syncTimeoutRunnable);
                    mainHandler.postDelayed(syncTimeoutRunnable, 2000L);
                } else {
                    recordFlight(callsign, icao, alt, spd, lat, lon, ts, 0, true, false);
                }
            }
        } catch (Exception ignored) {}
    }

    private void handleScreenLine(String line) {
        String payload = line.substring(9);
        String[] parts = payload.split("\\|", -1);
        String header = parts.length > 0 ? parts[0].trim() : "";
        boolean isAircraftScreen = header.startsWith("LIVE") || header.startsWith("H");
        String candidateId = parts.length > 1 ? parts[1].trim() : "";
        if (!isAircraftScreen || !ModeSDecoder.isValidAircraftIdentifier(candidateId)) {
            return;
        }

        String screenCallsign = candidateId.toUpperCase(Locale.US);
        String screenIcao = ModeSDecoder.isValidIcao(screenCallsign) ? screenCallsign : "";
        int screenAlt = -1;
        int screenSpeed = -1;
        double screenLat = Double.NaN;
        double screenLon = Double.NaN;

        String latLine = null;
        String lonLine = null;
        for (int i = 3; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.isEmpty()) continue;
            if (p.startsWith("ICAO ") && p.length() >= 11) {
                String maybeIcao = p.substring(5).trim().toUpperCase(Locale.US);
                if (ModeSDecoder.isValidIcao(maybeIcao)) screenIcao = maybeIcao;
            } else if (p.contains("ft")) {
                int parsedAlt = parseFirstInt(p);
                if (parsedAlt >= 0) screenAlt = parsedAlt;
            } else if (p.contains("kt")) {
                int parsedSpd = parseSpeedKt(p);
                if (parsedSpd >= 0) screenSpeed = parsedSpd;
            } else if (p.endsWith(" N") || p.endsWith(" S")) {
                latLine = p;
            } else if (p.endsWith(" E") || p.endsWith(" W")) {
                lonLine = p;
            }
        }

        if (latLine != null && lonLine != null) {
            double parsedLat = parseHemisphereCoord(latLine);
            double parsedLon = parseHemisphereCoord(lonLine);
            if (!Double.isNaN(parsedLat) && !Double.isNaN(parsedLon)) {
                screenLat = parsedLat;
                screenLon = parsedLon;
            }
        }

        long ageMs = parseScreenAgeMs(header);
        long now = System.currentTimeMillis();
        long screenTs = (ageMs >= 0L) ? (now - ageMs) : -1L;

        boolean recentlyRecordedViaJson = (screenCallsign.equalsIgnoreCase(lastObsIcaoOrCallsign)
                || (!screenIcao.isEmpty() && screenIcao.equalsIgnoreCase(lastObsIcaoOrCallsign)))
                && (now - lastObsTimestampMs) < 2000L;
        if (!recentlyRecordedViaJson) {
            String fingerprint = screenCallsign + "|" + screenIcao + "|" + screenAlt + "|" + screenSpeed
                    + "|" + (Double.isNaN(screenLat) ? "" : String.format(Locale.US, "%.4f,%.4f", screenLat, screenLon));
            boolean changed = !fingerprint.equals(lastScreenFingerprint);
            boolean isFresh = (ageMs >= 0L && ageMs <= 15_000L && (now - lastScreenRecordMs) >= 10_000L);
            if (changed || isFresh) {
                lastScreenFingerprint = fingerprint;
                lastScreenRecordMs = now;
                boolean eligibleForAlert = (ageMs >= 0L && ageMs <= FlightRecord.LIVE_THRESHOLD_MS);
                recordFlight(screenCallsign, screenIcao, screenAlt, screenSpeed,
                        screenLat, screenLon, screenTs, changed ? 1 : 0, false, eligibleForAlert);
            }
        }
    }

    private void ingestJsonObservation(String line, boolean isFromDump) {
        int jsonStart = line.indexOf('{');
        if (jsonStart == -1) return;
        try {
            JSONObject obj = new JSONObject(line.substring(jsonStart));
            String callsign = obj.optString("callsign", "").trim();
            String icao = obj.optString("icao", "").trim();
            int alt = obj.has("altitude") ? obj.optInt("altitude", -1) : -1;
            int speed = obj.has("speed") ? obj.optInt("speed", -1) : -1;
            double lat = (obj.has("lat") && obj.has("lon")) ? obj.optDouble("lat", Double.NaN) : Double.NaN;
            double lon = (obj.has("lat") && obj.has("lon")) ? obj.optDouble("lon", Double.NaN) : Double.NaN;

            String rawHex = obj.optString("df17", "");
            if (rawHex.isEmpty()) rawHex = obj.optString("df11", "");
            if (!rawHex.isEmpty()) {
                ModeSDecoder.DecodedFrame decoded = ModeSDecoder.decodeHex(rawHex);
                if (decoded != null) {
                    if (icao.isEmpty() && !decoded.icao.isEmpty()) icao = decoded.icao;
                    if (callsign.isEmpty() && !decoded.callsign.isEmpty()) callsign = decoded.callsign;
                    if (alt < 0 && decoded.altitude >= 0) alt = decoded.altitude;
                    if (speed < 0 && decoded.speed >= 0) speed = decoded.speed;
                }
            }

            if (!ModeSDecoder.isValidAircraftIdentifier(callsign) && !ModeSDecoder.isValidIcao(icao)) return;

            if (!isFromDump) {
                lastObsIcaoOrCallsign = !callsign.isEmpty() ? callsign : icao;
                lastObsTimestampMs = System.currentTimeMillis();
            }
            recordFlight(callsign, icao, alt, speed, lat, lon,
                    isFromDump ? -1L : System.currentTimeMillis(),
                    isFromDump ? 0 : 1, isFromDump, !isFromDump);
        } catch (Exception ignored) {}
    }

    private void finishBoardSync() {
        if (!isSilentSyncInProgress) return;
        isSilentSyncInProgress = false;
        mainHandler.removeCallbacks(syncTimeoutRunnable);

        List<SyncAggregate> snapshot = new ArrayList<>(pendingSyncAircraft.values());
        pendingSyncAircraft.clear();
        boolean notifyUser = showSyncToastOnFinish;
        showSyncToastOnFinish = false;

        if (snapshot.isEmpty()) {
            if (notifyUser) notifyBoardMessage("No stored aircraft found on board");
            return;
        }

        dbExecutor.execute(() -> {
            for (SyncAggregate agg : snapshot) {
                flightDb.upsertFlight(
                        agg.callsign, agg.icao, agg.lastAltitude, agg.lastSpeed,
                        agg.lat, agg.lon, agg.timestampMs, 0, true);
            }
            flightDb.deduplicateFlights();
            mainHandler.post(() -> {
                if (listener != null) listener.onFlightsUpdated();
                if (notifyUser || !snapshot.isEmpty()) {
                    notifyBoardMessage(String.format(Locale.getDefault(),
                            "Synced %d aircraft from board", snapshot.size()));
                }
            });
        });
    }

    private void recordFlight(
            String callsign, String icao, int alt, int speed,
            double lat, double lon, long ts, int packetDelta,
            boolean isFromSync, boolean checkAlerts
    ) {
        String displayId = ModeSDecoder.isValidAircraftIdentifier(callsign) ? callsign : icao;
        if (displayId != null && !displayId.isEmpty() && !isFromSync) {
            lastNotifFlightSummary = alt >= 0
                    ? String.format(Locale.getDefault(), "Last: %s (%,d ft)", displayId, alt)
                    : ("Last: " + displayId);
            updateServiceNotification();
        }
        if (checkAlerts) {
            maybeFireFlightAlert(callsign, icao, alt, speed, lat, lon);
        }
        dbExecutor.execute(() -> {
            flightDb.upsertFlight(callsign, icao, alt, speed, lat, lon, ts, packetDelta, isFromSync);
            mainHandler.post(() -> {
                if (listener != null) listener.onFlightsUpdated();
            });
        });
    }

    private void maybeFireFlightAlert(String callsign, String icao, int alt, int speed, double lat, double lon) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean alertLowAlt = prefs.getBoolean(PREF_ALERT_LOW_ALT, true);
        boolean alertGovMil = prefs.getBoolean(PREF_ALERT_GOV_MIL, true);
        boolean alertAll = prefs.getBoolean(PREF_ALERT_ALL, false);
        if (!alertLowAlt && !alertGovMil && !alertAll) return;

        String id = ModeSDecoder.isValidAircraftIdentifier(callsign) ? callsign : icao;
        if (id == null || id.isEmpty()) return;

        AircraftDbHelper.AircraftInfo acInfo = AircraftDbHelper.getInstance(this).lookup(icao);
        boolean isMilByDb = (acInfo != null && acInfo.military);

        String reason = null;
        if (alertGovMil && (isGovOrMilitary(callsign) || isMilByDb)) {
            String op = FlightEnricher.lookupAirline(callsign);
            if (op.isEmpty() && acInfo != null && !acInfo.operator.isEmpty()) op = acInfo.operator;
            reason = op.isEmpty() ? "Government / Military aircraft" : op;
        } else if (alertLowAlt && alt > 0 && alt <= LOW_ALT_THRESHOLD_FT) {
            reason = String.format(Locale.getDefault(), "Low altitude (%,d ft)", alt);
        } else if (alertAll) {
            String op = FlightEnricher.lookupAirline(callsign);
            if (op.isEmpty() && acInfo != null && !acInfo.displayModel().isEmpty()) op = acInfo.displayModel();
            reason = op.isEmpty() ? "New aircraft spotted" : op;
        }
        if (reason == null) return;

        String key = (icao != null && !icao.isEmpty()) ? icao : id;
        long now = System.currentTimeMillis();
        Long lastAlert = alertCooldownMap.get(key);
        if (lastAlert != null && (now - lastAlert) < ALERT_COOLDOWN_MS) {
            return;
        }
        alertCooldownMap.put(key, now);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        StringBuilder body = new StringBuilder(reason);
        if (speed >= 0) body.append(" · ").append(speed).append(" kt");
        String place = FlightEnricher.findNearestPlace(lat, lon);
        if (!place.isEmpty()) body.append(" · ").append(place);

        PendingIntent openPi = PendingIntent.getActivity(
                this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_plane)
                .setContentTitle("Aircraft Alert: " + id)
                .setContentText(body.toString())
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .build();

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID_ALERT_BASE + (++alertNotifSeq % 20), notif);
        }
    }

    private boolean isGovOrMilitary(String callsign) {
        if (callsign == null || callsign.length() < 3) return false;
        String p = callsign.trim().toUpperCase(Locale.US).substring(0, 3);
        return p.equals("IAM") || p.equals("MMI") || p.equals("GFI")
                || p.equals("POL") || p.equals("VFS") || p.equals("RCH")
                || p.equals("NATO") || p.equals("RRR") || p.equals("CTM");
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel svc = new NotificationChannel(
                    CHANNEL_SERVICE, "BLE Background Connection", NotificationManager.IMPORTANCE_LOW);
            svc.setDescription("Keeps Heltec ADS-B connected while the screen is off");
            nm.createNotificationChannel(svc);

            NotificationChannel alerts = new NotificationChannel(
                    CHANNEL_ALERTS, "Aircraft Alerts", NotificationManager.IMPORTANCE_HIGH);
            alerts.setDescription("Alerts for low-altitude, military/gov, or new aircraft");
            nm.createNotificationChannel(alerts);
        }
    }

    private Notification buildServiceNotification() {
        PendingIntent openPi = PendingIntent.getActivity(
                this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent discPi = PendingIntent.getService(
                this, 1,
                new Intent(this, AdsbBleService.class).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = isConnected
                ? String.format(Locale.getDefault(), "Heltec ADS-B · %.2f MHz", currentDialHz / 1e6)
                : (isScanning ? "Heltec ADS-B · Scanning..." : "Heltec ADS-B · Connecting...");

        return new NotificationCompat.Builder(this, CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_plane)
                .setContentTitle(title)
                .setContentText(lastNotifFlightSummary)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPi)
                .addAction(0, "Disconnect", discPi)
                .build();
    }

    private void startForegroundCompat() {
        Notification notif = buildServiceNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID_SERVICE, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIF_ID_SERVICE, notif);
        }
    }

    private void updateServiceNotification() {
        if (!isConnected && !isScanning && !isConnecting) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID_SERVICE, buildServiceNotification());
        }
    }

    private void stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void updateStatus(String text) {
        statusText = text;
        notifyConnectionState();
        updateServiceNotification();
    }

    private void notifyConnectionState() {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onConnectionStateChanged(isConnected, isScanning, isConnecting, statusText);
            }
        });
    }

    private void notifyBoardMessage(String msg) {
        mainHandler.post(() -> {
            if (listener != null) listener.onBoardMessage(msg);
        });
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

    private int parseFirstInt(String text) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isDigit(ch)) digits.append(ch);
            else if (digits.length() > 0) break;
        }
        if (digits.length() == 0) return -1;
        try { return Integer.parseInt(digits.toString()); } catch (Exception e) { return -1; }
    }

    private int parseSpeedKt(String text) {
        int ktIdx = text.indexOf("kt");
        if (ktIdx <= 0) return -1;
        int start = ktIdx - 1;
        while (start >= 0 && (Character.isWhitespace(text.charAt(start)) || Character.isDigit(text.charAt(start)))) {
            start--;
        }
        return parseFirstInt(text.substring(start + 1, ktIdx));
    }

    private double parseHemisphereCoord(String text) {
        String s = text.trim().toUpperCase(Locale.US);
        boolean negative = s.endsWith("S") || s.endsWith("W");
        String num = s.replace("N", "").replace("S", "").replace("E", "").replace("W", "").trim();
        try {
            double val = Double.parseDouble(num);
            return negative ? -val : val;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private long parseScreenAgeMs(String header) {
        if (header == null || header.isEmpty()) return -1L;
        String[] tokens = header.trim().split("\\s+");
        if (tokens.length < 2) return -1L;
        String last = tokens[tokens.length - 1].toLowerCase(Locale.US);
        if (last.equals("saved")) return -2L;
        if (last.length() < 2) return -1L;
        char unit = last.charAt(last.length() - 1);
        try {
            long val = Long.parseLong(last.substring(0, last.length() - 1));
            if (val < 0) return -1L;
            switch (unit) {
                case 's': return val * 1000L;
                case 'm': return val * 60_000L;
                case 'h': return val * 3_600_000L;
                case 'd': return val * 86_400_000L;
                default: return -1L;
            }
        } catch (Exception e) {
            return -1L;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        closeGattInternal();
        dbExecutor.shutdownNow();
        if (flightDb != null) flightDb.close();
    }
}
