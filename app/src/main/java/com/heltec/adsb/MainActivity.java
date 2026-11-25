package com.heltec.adsb;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements AdsbBleService.Listener {

    private static final int PERMISSION_REQ_CODE = 101;

    private AdsbBleService bleService;
    private boolean isServiceBound = false;

    private boolean isConnected = false;
    private boolean isScanning = false;
    private boolean isConnecting = false;
    private boolean isStreamLiveEnabled = false;
    private long currentDialHz = 1089800000L;

    // Header & Connection UI
    private View statusDot;
    private View btnHeaderSync, btnHeaderStatus, btnHeaderConnect;
    private ImageView ivHeaderSync, ivHeaderConnect;
    private TextView tvStatus, tvBatteryPill;
    private int lastBatteryPct = -1;
    private int lastBatteryMv = -1;

    // Home Dashboard UI
    private TextView tvHeroFreqPill, tvBoardInfo, tvBurstCount;
    private View cardHomeEmptyRecent;
    private TextView tvHomeCountsSummary, tvLiveSwipeHint, btnHomeSeeAllFlights;
    private RecyclerView rvLiveCards, rvHomeRecent;
    private LiveCardAdapter liveCardAdapter;
    private FlightAdapter homeRecentAdapter;

    // Settings Tab UI (Alerts + Offline DB + Controls + Logs)
    private SwitchMaterial swAlertLowAlt, swAlertGovMil, swAlertAll;
    private TextView tvAircraftDbStatus;
    private MaterialButton btnDownloadAircraftDb, btnDeleteAircraftDb;
    private boolean isDownloadingAircraftDb = false;
    private TextView tvDialFreq;
    private MaterialButton btnTap, btnHold, btnDouble;
    private MaterialButton btnTuneDown, btnTuneUp, btnFreqDefault, btnSaveDial;
    private View rowPrevAircraft, rowLiveMode, rowStatus, btnClearHistory;
    private TextView tvLogCountHero, tvStreamBadge, tvLogStats, tvLogs;
    private MaterialButton btnToggleStream, btnDump, btnSaveFile, btnClear;
    private ScrollView scrollLogs;

    // Tab Containers & Bottom Nav
    private View tabHome, tabFlights, tabSettings;
    private BottomNavigationView bottomNav;

    // Flights Tab UI & Database
    private TextView tvFlightsCountBadge;
    private EditText etFlightSearch;
    private TextView btnFilterToday, btnFilterGps, btnSortFlights;
    private View btnFlightsMenu;
    private RecyclerView rvFlights;
    private View layoutEmptyFlights;
    private FlightAdapter flightAdapter;
    private FlightDatabaseHelper flightDb;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private boolean filterOnlyToday = false;
    private boolean filterOnlyGps = false;
    private FlightDatabaseHelper.SortMode flightSortMode = FlightDatabaseHelper.SortMode.RECENT;

    private final StringBuilder logAccumulator = new StringBuilder();
    private int logLineCount = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            AdsbBleService.LocalBinder local = (AdsbBleService.LocalBinder) binder;
            bleService = local.getService();
            isServiceBound = true;
            isStreamLiveEnabled = bleService.isStreamLiveEnabled();
            currentDialHz = bleService.getCurrentDialHz();
            updateFrequencyDisplays();
            updateStreamButtonUI();
            bleService.setListener(MainActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isServiceBound = false;
            bleService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupNavigation();
        setupFlightsTab();
        setupAlertSwitches();
        setupOfflineAircraftDb();

        btnHeaderConnect.setOnClickListener(v -> {
            if (isConnected || isConnecting) {
                if (bleService != null) bleService.disconnectDevice();
            } else if (!isScanning) {
                checkPermissionsAndScan();
            } else {
                if (bleService != null) bleService.stopScan();
            }
        });

        btnHeaderSync.setOnClickListener(v -> {
            if (bleService != null) bleService.syncFlightsFromBoard(true);
            else Toast.makeText(this, "Connect to the Heltec board first", Toast.LENGTH_SHORT).show();
        });

        tvBatteryPill.setOnClickListener(v -> {
            if (lastBatteryPct >= 0) {
                String msg = lastBatteryMv > 0
                        ? String.format(Locale.getDefault(), "Heltec battery: %d%% (%.2f V)", lastBatteryPct, lastBatteryMv / 1000.0)
                        : String.format(Locale.getDefault(), "Heltec battery: %d%%", lastBatteryPct);
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            }
        });

        // Hardware & PRG controls
        btnTap.setOnClickListener(v -> sendBleCommandWithToast("tap\n", "PRG Button: Tap"));
        btnHold.setOnClickListener(v -> sendBleCommandWithToast("hold\n", "PRG Button: Hold"));
        btnDouble.setOnClickListener(v -> sendBleCommandWithToast("double\n", "PRG Button: Double Click"));

        View.OnClickListener prevListener = v -> sendBleCommandWithToast("prev\n", "Showing previous aircraft");
        View.OnClickListener liveListener = v -> sendBleCommandWithToast("live\n", "Returning to real-time mode (Live)");
        View.OnClickListener statusListener = v -> sendBleCommandWithToast("status\n", "Requesting hardware status...");

        rowPrevAircraft.setOnClickListener(prevListener);
        rowLiveMode.setOnClickListener(liveListener);
        rowStatus.setOnClickListener(statusListener);
        btnHeaderStatus.setOnClickListener(statusListener);

        tvHomeCountsSummary.setOnClickListener(v -> bottomNav.setSelectedItemId(R.id.nav_flights));
        btnHomeSeeAllFlights.setOnClickListener(v -> bottomNav.setSelectedItemId(R.id.nav_flights));

        btnClearHistory.setOnClickListener(v -> sendBleCommandWithToast("clear\n", "Clearing board flash history..."));

        btnTuneDown.setOnClickListener(v -> {
            currentDialHz = Math.max(150000000L, currentDialHz - 100000L);
            updateFrequencyDisplays();
            if (bleService != null) bleService.setDialHz(currentDialHz);
            Toast.makeText(this, String.format(Locale.getDefault(), "Tuning: %.2f MHz", currentDialHz / 1e6), Toast.LENGTH_SHORT).show();
        });

        btnTuneUp.setOnClickListener(v -> {
            currentDialHz = Math.min(1150000000L, currentDialHz + 100000L);
            updateFrequencyDisplays();
            if (bleService != null) bleService.setDialHz(currentDialHz);
            Toast.makeText(this, String.format(Locale.getDefault(), "Tuning: %.2f MHz", currentDialHz / 1e6), Toast.LENGTH_SHORT).show();
        });

        btnFreqDefault.setOnClickListener(v -> {
            currentDialHz = 1089800000L;
            updateFrequencyDisplays();
            if (bleService != null) bleService.setDialHz(currentDialHz);
            Toast.makeText(this, "Restored default frequency: 1089.8 MHz", Toast.LENGTH_SHORT).show();
        });

        btnSaveDial.setOnClickListener(v -> sendBleCommandWithToast("save_dial\n", "Saving frequency to Flash memory..."));

        btnToggleStream.setOnClickListener(v -> {
            isStreamLiveEnabled = !isStreamLiveEnabled;
            if (bleService != null) bleService.setStreamLiveEnabled(isStreamLiveEnabled);
            Toast.makeText(this, isStreamLiveEnabled ? "Log acquisition ENABLED" : "Log acquisition DISABLED", Toast.LENGTH_SHORT).show();
            updateStreamButtonUI();
        });

        btnDump.setOnClickListener(v -> {
            if (!isConnected || bleService == null) {
                Toast.makeText(this, "Not connected to Heltec board", Toast.LENGTH_SHORT).show();
                return;
            }
            bleService.startManualDump();
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

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, AdsbBleService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAllFlightViews();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isServiceBound) {
            if (bleService != null) bleService.setListener(null);
            unbindService(serviceConnection);
            isServiceBound = false;
        }
    }

    private void bindViews() {
        statusDot = findViewById(R.id.statusDot);
        btnHeaderSync = findViewById(R.id.btnHeaderSync);
        ivHeaderSync = findViewById(R.id.ivHeaderSync);
        btnHeaderStatus = findViewById(R.id.btnHeaderStatus);
        btnHeaderConnect = findViewById(R.id.btnHeaderConnect);
        ivHeaderConnect = findViewById(R.id.ivHeaderConnect);
        tvStatus = findViewById(R.id.tvStatus);
        tvBatteryPill = findViewById(R.id.tvBatteryPill);

        tvHeroFreqPill = findViewById(R.id.tvHeroFreqPill);
        tvBoardInfo = findViewById(R.id.tvBoardInfo);
        tvBurstCount = findViewById(R.id.tvBurstCount);

        cardHomeEmptyRecent = findViewById(R.id.cardHomeEmptyRecent);
        tvHomeCountsSummary = findViewById(R.id.tvHomeCountsSummary);
        tvLiveSwipeHint = findViewById(R.id.tvLiveSwipeHint);
        btnHomeSeeAllFlights = findViewById(R.id.btnHomeSeeAllFlights);
        rvLiveCards = findViewById(R.id.rvLiveCards);
        rvHomeRecent = findViewById(R.id.rvHomeRecent);

        swAlertLowAlt = findViewById(R.id.swAlertLowAlt);
        swAlertGovMil = findViewById(R.id.swAlertGovMil);
        swAlertAll = findViewById(R.id.swAlertAll);
        tvAircraftDbStatus = findViewById(R.id.tvAircraftDbStatus);
        btnDownloadAircraftDb = findViewById(R.id.btnDownloadAircraftDb);
        btnDeleteAircraftDb = findViewById(R.id.btnDeleteAircraftDb);

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
        tabFlights = findViewById(R.id.tabFlights);
        tabSettings = findViewById(R.id.tabSettings);
        bottomNav = findViewById(R.id.bottomNav);

        tvFlightsCountBadge = findViewById(R.id.tvFlightsCountBadge);
        etFlightSearch = findViewById(R.id.etFlightSearch);
        btnFilterToday = findViewById(R.id.btnFilterToday);
        btnFilterGps = findViewById(R.id.btnFilterGps);
        btnSortFlights = findViewById(R.id.btnSortFlights);
        btnFlightsMenu = findViewById(R.id.btnFlightsMenu);
        rvFlights = findViewById(R.id.rvFlights);
        layoutEmptyFlights = findViewById(R.id.layoutEmptyFlights);
    }

    private void setupAlertSwitches() {
        SharedPreferences prefs = getSharedPreferences(AdsbBleService.PREFS_NAME, MODE_PRIVATE);
        swAlertLowAlt.setChecked(prefs.getBoolean(AdsbBleService.PREF_ALERT_LOW_ALT, true));
        swAlertGovMil.setChecked(prefs.getBoolean(AdsbBleService.PREF_ALERT_GOV_MIL, true));
        swAlertAll.setChecked(prefs.getBoolean(AdsbBleService.PREF_ALERT_ALL, false));

        swAlertLowAlt.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean(AdsbBleService.PREF_ALERT_LOW_ALT, checked).apply());
        swAlertGovMil.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean(AdsbBleService.PREF_ALERT_GOV_MIL, checked).apply());
        swAlertAll.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean(AdsbBleService.PREF_ALERT_ALL, checked).apply());
    }

    private void setupOfflineAircraftDb() {
        updateAircraftDbStatusUI();

        btnDownloadAircraftDb.setOnClickListener(v -> {
            if (isDownloadingAircraftDb) return;
            isDownloadingAircraftDb = true;
            btnDownloadAircraftDb.setEnabled(false);
            btnDeleteAircraftDb.setEnabled(false);
            btnDownloadAircraftDb.setText("Downloading...");
            tvAircraftDbStatus.setText("Downloading gzipped ICAO catalog (~10 MB)...");

            new Thread(() -> {
                try {
                    AircraftDbHelper db = AircraftDbHelper.getInstance(this);
                    int count = db.downloadAndImport(imported -> mainHandler.post(() ->
                            tvAircraftDbStatus.setText(String.format(Locale.getDefault(),
                                    "Indexing offline DB... %,d aircraft", imported))));
                    mainHandler.post(() -> {
                        isDownloadingAircraftDb = false;
                        updateAircraftDbStatusUI();
                        refreshAllFlightViews();
                        Toast.makeText(this,
                                String.format(Locale.getDefault(), "Offline DB ready: %,d aircraft", count),
                                Toast.LENGTH_LONG).show();
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        isDownloadingAircraftDb = false;
                        updateAircraftDbStatusUI();
                        Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        });

        btnDeleteAircraftDb.setOnClickListener(v -> {
            if (isDownloadingAircraftDb) return;
            dbExecutor.execute(() -> {
                AircraftDbHelper.getInstance(this).clearDatabase();
                mainHandler.post(() -> {
                    updateAircraftDbStatusUI();
                    refreshAllFlightViews();
                    Toast.makeText(this, "Offline aircraft DB deleted", Toast.LENGTH_SHORT).show();
                });
            });
        });
    }

    private void updateAircraftDbStatusUI() {
        AircraftDbHelper db = AircraftDbHelper.getInstance(this);
        int count = db.getCachedCount();
        btnDownloadAircraftDb.setEnabled(!isDownloadingAircraftDb);
        if (count > 0) {
            btnDownloadAircraftDb.setText("Update Offline DB");
            btnDeleteAircraftDb.setEnabled(!isDownloadingAircraftDb);
            btnDeleteAircraftDb.setAlpha(1.0f);
            tvAircraftDbStatus.setText(String.format(Locale.getDefault(),
                    "%,d aircraft cached · 100%% offline", count));
        } else {
            btnDownloadAircraftDb.setText("Download Offline DB");
            btnDeleteAircraftDb.setEnabled(false);
            btnDeleteAircraftDb.setAlpha(0.45f);
            tvAircraftDbStatus.setText("Not downloaded · ~10 MB one-time download");
        }
    }

    private void setupNavigation() {
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            tabHome.setVisibility(id == R.id.nav_home ? View.VISIBLE : View.GONE);
            tabFlights.setVisibility(id == R.id.nav_flights ? View.VISIBLE : View.GONE);
            tabSettings.setVisibility(id == R.id.nav_settings ? View.VISIBLE : View.GONE);
            if (id == R.id.nav_flights) {
                refreshFlightsList();
            } else if (id == R.id.nav_home) {
                refreshHomeDashboard();
            }
            return true;
        });
    }

    private void setupFlightsTab() {
        flightDb = new FlightDatabaseHelper(this);
        flightAdapter = new FlightAdapter(this::showFlightDetailSheet);
        rvFlights.setLayoutManager(new LinearLayoutManager(this));
        rvFlights.setAdapter(flightAdapter);

        homeRecentAdapter = new FlightAdapter(this::showFlightDetailSheet);
        rvHomeRecent.setLayoutManager(new LinearLayoutManager(this));
        rvHomeRecent.setAdapter(homeRecentAdapter);

        liveCardAdapter = new LiveCardAdapter(this::showFlightDetailSheet);
        rvLiveCards.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvLiveCards.setAdapter(liveCardAdapter);
        new PagerSnapHelper().attachToRecyclerView(rvLiveCards);

        dbExecutor.execute(() -> {
            flightDb.purgeInvalidFlights();
            flightDb.deduplicateFlights();
            mainHandler.post(this::refreshAllFlightViews);
        });

        etFlightSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                refreshFlightsList();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnFilterToday.setOnClickListener(v -> {
            filterOnlyToday = !filterOnlyToday;
            updateFlightFilterButtons();
            refreshFlightsList();
        });

        btnFilterGps.setOnClickListener(v -> {
            filterOnlyGps = !filterOnlyGps;
            updateFlightFilterButtons();
            refreshFlightsList();
        });

        btnSortFlights.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(this, v);
            popup.getMenu().add(0, 1, 0, "Most Recent");
            popup.getMenu().add(0, 2, 1, "Highest Altitude");
            popup.getMenu().add(0, 3, 2, "Most Packets");
            popup.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) flightSortMode = FlightDatabaseHelper.SortMode.RECENT;
                else if (item.getItemId() == 2) flightSortMode = FlightDatabaseHelper.SortMode.ALTITUDE;
                else if (item.getItemId() == 3) flightSortMode = FlightDatabaseHelper.SortMode.PACKETS;
                updateFlightFilterButtons();
                refreshFlightsList();
                return true;
            });
            popup.show();
        });

        btnFlightsMenu.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(this, v);
            popup.getMenu().add(0, 1, 0, "Export CSV");
            popup.getMenu().add(0, 2, 1, "Clear History");
            popup.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) {
                    exportFlightsCsv();
                    return true;
                } else if (item.getItemId() == 2) {
                    new AlertDialog.Builder(this)
                            .setTitle("Clear flight history")
                            .setMessage("Delete all recorded flights from the local database?")
                            .setPositiveButton("Delete", (d, w) -> dbExecutor.execute(() -> {
                                flightDb.clearAllFlights();
                                mainHandler.post(() -> {
                                    refreshAllFlightViews();
                                    Toast.makeText(this, "Flight history cleared", Toast.LENGTH_SHORT).show();
                                });
                            }))
                            .setNegativeButton("Cancel", null)
                            .show();
                    return true;
                }
                return false;
            });
            popup.show();
        });

        updateFlightFilterButtons();
        refreshAllFlightViews();
    }

    private void updateFlightFilterButtons() {
        int activeBg = ContextCompat.getColor(this, R.color.rev_blue_soft);
        int activeFg = ContextCompat.getColor(this, R.color.rev_blue);
        int inactiveBg = ContextCompat.getColor(this, R.color.rev_surface);
        int inactiveFg = ContextCompat.getColor(this, R.color.rev_text_secondary);

        btnFilterToday.setBackgroundTintList(ColorStateList.valueOf(filterOnlyToday ? activeBg : inactiveBg));
        btnFilterToday.setTextColor(filterOnlyToday ? activeFg : inactiveFg);

        btnFilterGps.setBackgroundTintList(ColorStateList.valueOf(filterOnlyGps ? activeBg : inactiveBg));
        btnFilterGps.setTextColor(filterOnlyGps ? activeFg : inactiveFg);

        if (flightSortMode == FlightDatabaseHelper.SortMode.ALTITUDE) {
            btnSortFlights.setText("Sort: Altitude ▾");
        } else if (flightSortMode == FlightDatabaseHelper.SortMode.PACKETS) {
            btnSortFlights.setText("Sort: Packets ▾");
        } else {
            btnSortFlights.setText("Sort: Recent ▾");
        }
    }

    private void refreshFlightsList() {
        if (flightDb == null) return;
        String query = etFlightSearch != null ? etFlightSearch.getText().toString() : "";
        boolean onlyToday = filterOnlyToday;
        boolean onlyGps = filterOnlyGps;
        FlightDatabaseHelper.SortMode sort = flightSortMode;

        dbExecutor.execute(() -> {
            List<FlightRecord> flights = flightDb.queryFlights(query, onlyToday, onlyGps, sort);
            int total = flightDb.getTotalFlightCount();
            mainHandler.post(() -> {
                flightAdapter.submitList(flights);
                boolean isEmpty = flights.isEmpty();
                rvFlights.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
                layoutEmptyFlights.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
                tvFlightsCountBadge.setText(String.format(Locale.getDefault(),
                        total == flights.size() ? "%d" : "%d / %d",
                        flights.size(), total));
            });
        });
    }

    private void refreshAllFlightViews() {
        refreshFlightsList();
        refreshHomeDashboard();
    }

    private void refreshHomeDashboard() {
        if (flightDb == null) return;
        dbExecutor.execute(() -> {
            int todayCount = flightDb.getTodayFlightCount();
            int totalCount = flightDb.getTotalFlightCount();
            List<FlightRecord> latest = flightDb.getRecentFlights(12);
            long now = System.currentTimeMillis();

            List<FlightRecord> liveCards = new ArrayList<>();
            for (FlightRecord f : latest) {
                if (f.isLive(now)) {
                    liveCards.add(f);
                }
            }
            if (liveCards.isEmpty() && !latest.isEmpty()) {
                liveCards.add(latest.get(0));
            }

            List<FlightRecord> recentThree = latest.size() > 3
                    ? new ArrayList<>(latest.subList(0, 3))
                    : latest;

            mainHandler.post(() -> {
                tvHomeCountsSummary.setText(String.format(Locale.getDefault(),
                        "%d today · %d total", todayCount, totalCount));
                liveCardAdapter.submitCards(liveCards);
                tvLiveSwipeHint.setVisibility(liveCards.size() > 1 ? View.VISIBLE : View.GONE);

                homeRecentAdapter.submitList(recentThree);
                boolean empty = recentThree.isEmpty();
                rvHomeRecent.setVisibility(empty ? View.GONE : View.VISIBLE);
                cardHomeEmptyRecent.setVisibility(empty ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void showFlightDetailSheet(FlightRecord flight) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_flight_detail, null);
        dialog.setContentView(view);

        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault());
        TextView tvDetailCallsign = view.findViewById(R.id.tvDetailCallsign);
        TextView tvDetailSub = view.findViewById(R.id.tvDetailSub);
        TextView tvDetailModel = view.findViewById(R.id.tvDetailModel);
        TextView tvDetailOperator = view.findViewById(R.id.tvDetailOperator);
        TextView tvDetailArea = view.findViewById(R.id.tvDetailArea);
        TextView tvDetailAltitude = view.findViewById(R.id.tvDetailAltitude);
        TextView tvDetailSpeed = view.findViewById(R.id.tvDetailSpeed);
        TextView tvDetailCoords = view.findViewById(R.id.tvDetailCoords);
        TextView tvDetailFirstSeen = view.findViewById(R.id.tvDetailFirstSeen);
        TextView tvDetailLastSeen = view.findViewById(R.id.tvDetailLastSeen);
        MaterialButton btnDetailMaps = view.findViewById(R.id.btnDetailMaps);
        MaterialButton btnDetailCopy = view.findViewById(R.id.btnDetailCopy);
        MaterialButton btnDetailDelete = view.findViewById(R.id.btnDetailDelete);

        AircraftDbHelper.AircraftInfo acInfo = AircraftDbHelper.getInstance(this).lookup(flight.icao);
        boolean isIcaoOnly = flight.callsign == null || flight.callsign.isEmpty()
                || flight.callsign.equalsIgnoreCase(flight.icao)
                || ModeSDecoder.isValidIcao(flight.callsign);
        String displayTitle = (isIcaoOnly && acInfo != null && !acInfo.displayModel().isEmpty())
                ? acInfo.displayModel()
                : flight.callsign;
        tvDetailCallsign.setText(displayTitle);
        String icaoPart = (flight.icao != null && !flight.icao.isEmpty()) ? "ICAO " + flight.icao + " · " : "";
        tvDetailSub.setText(String.format(Locale.getDefault(), "%s%d packets", icaoPart, flight.packetCount));

        // Tap callsign/name or ICAO to copy directly to clipboard
        tvDetailCallsign.setOnClickListener(v -> copyToClipboard("Callsign", displayTitle, "Copied: " + displayTitle));
        tvDetailSub.setOnClickListener(v -> {
            String toCopy = (flight.icao != null && !flight.icao.isEmpty()) ? flight.icao : flight.callsign;
            copyToClipboard("ICAO", toCopy, "Copied ICAO: " + toCopy);
        });
        if (acInfo != null) {
            StringBuilder m = new StringBuilder();
            if (!acInfo.displayModel().isEmpty()) m.append(acInfo.displayModel());
            if (!acInfo.type.isEmpty() && !acInfo.displayModel().equals(acInfo.type)) {
                m.append(" (").append(acInfo.type).append(")");
            }
            if (!acInfo.reg.isEmpty()) {
                if (m.length() > 0) m.append(" · ");
                m.append(acInfo.reg);
            }
            tvDetailModel.setText(m.length() > 0 ? m.toString() : "--");
        } else {
            tvDetailModel.setText(AircraftDbHelper.getInstance(this).getCachedCount() > 0
                    ? "--" : "Download DB in Settings");
        }

        String airline = FlightEnricher.lookupAirline(flight.callsign);
        if (airline.isEmpty() && acInfo != null && !acInfo.operator.isEmpty()) {
            airline = acInfo.operator;
        }
        String country = FlightEnricher.lookupCountry(flight.icao);
        String opText = (!airline.isEmpty() && !country.isEmpty()) ? (airline + " (" + country + ")")
                : (!airline.isEmpty() ? airline : (!country.isEmpty() ? country : "--"));
        tvDetailOperator.setText(opText);

        String area = flight.hasCoords() ? FlightEnricher.findNearestPlace(flight.lastLat, flight.lastLon) : "";
        tvDetailArea.setText(!area.isEmpty() ? area : "No GPS fix");

        String altText = flight.lastAltitude >= 0
                ? String.format(Locale.getDefault(), "%,d ft (max %,d ft)", flight.lastAltitude, Math.max(flight.lastAltitude, flight.maxAltitude))
                : "--";
        tvDetailAltitude.setText(altText);
        tvDetailSpeed.setText(flight.lastSpeed >= 0 ? flight.lastSpeed + " kt" : "--");
        tvDetailCoords.setText(flight.hasCoords()
                ? String.format(Locale.US, "%.4f°, %.4f°", flight.lastLat, flight.lastLon)
                : "No GPS fix");
        tvDetailFirstSeen.setText(fmt.format(new Date(flight.firstSeenAt)));
        tvDetailLastSeen.setText(fmt.format(new Date(flight.lastSeenAt)));

        btnDetailMaps.setEnabled(flight.hasCoords());
        btnDetailMaps.setAlpha(flight.hasCoords() ? 1.0f : 0.45f);
        btnDetailMaps.setOnClickListener(v -> {
            if (!flight.hasCoords()) return;
            Uri uri = Uri.parse(String.format(Locale.US, "geo:%f,%f?q=%f,%f(%s)",
                    flight.lastLat, flight.lastLon, flight.lastLat, flight.lastLon, Uri.encode(displayTitle)));
            Intent mapIntent = new Intent(Intent.ACTION_VIEW, uri);
            try {
                startActivity(Intent.createChooser(mapIntent, "Open in Maps"));
            } catch (Exception e) {
                Toast.makeText(this, "No map app installed", Toast.LENGTH_SHORT).show();
            }
        });

        btnDetailCopy.setOnClickListener(v -> {
            String summary = String.format(Locale.US,
                    "%s | Alt: %s | Speed: %s | Pos: %s",
                    displayTitle, altText,
                    flight.lastSpeed >= 0 ? flight.lastSpeed + " kt" : "--",
                    flight.hasCoords() ? String.format(Locale.US, "%.4f, %.4f", flight.lastLat, flight.lastLon) : "--");
            copyToClipboard("Flight", summary, "Flight details copied");
        });

        btnDetailDelete.setOnClickListener(v -> {
            dialog.dismiss();
            dbExecutor.execute(() -> {
                flightDb.deleteFlight(flight.id);
                mainHandler.post(() -> {
                    refreshAllFlightViews();
                    Toast.makeText(this, "Flight deleted", Toast.LENGTH_SHORT).show();
                });
            });
        });

        dialog.show();
    }

    private void copyToClipboard(String label, String text, String toastMsg) {
        if (text == null || text.trim().isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show();
        }
    }

    private void exportFlightsCsv() {
        if (flightDb == null) return;
        dbExecutor.execute(() -> {
            List<FlightRecord> all = flightDb.queryFlights("", false, false, FlightDatabaseHelper.SortMode.RECENT);
            if (all.isEmpty()) {
                mainHandler.post(() -> Toast.makeText(this, "No flights to export", Toast.LENGTH_SHORT).show());
                return;
            }
            SimpleDateFormat isoFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            StringBuilder csv = new StringBuilder();
            csv.append("callsign,icao,last_altitude_ft,max_altitude_ft,last_speed_kt,lat,lon,packets,first_seen,last_seen\n");
            for (FlightRecord f : all) {
                csv.append(escapeCsv(f.callsign)).append(',')
                        .append(escapeCsv(f.icao)).append(',')
                        .append(f.lastAltitude >= 0 ? f.lastAltitude : "").append(',')
                        .append(f.maxAltitude >= 0 ? f.maxAltitude : "").append(',')
                        .append(f.lastSpeed >= 0 ? f.lastSpeed : "").append(',')
                        .append(f.hasCoords() ? String.format(Locale.US, "%.6f", f.lastLat) : "").append(',')
                        .append(f.hasCoords() ? String.format(Locale.US, "%.6f", f.lastLon) : "").append(',')
                        .append(f.packetCount).append(',')
                        .append(isoFmt.format(new Date(f.firstSeenAt))).append(',')
                        .append(isoFmt.format(new Date(f.lastSeenAt))).append('\n');
            }

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "adsb_flights_" + timeStamp + ".csv";
            try {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, fileName);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(csv.toString().getBytes(StandardCharsets.UTF_8));
                }
                mainHandler.post(() -> Toast.makeText(this, "CSV saved to:\nDownload/" + fileName, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(this, "Error exporting CSV: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
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
        int muted = ContextCompat.getColor(this, R.color.rev_text_tertiary);
        int primaryText = ContextCompat.getColor(this, R.color.rev_text_primary);

        if (isConnected) {
            statusDot.setBackgroundTintList(ColorStateList.valueOf(green));
            btnHeaderSync.setEnabled(true);
            btnHeaderSync.setAlpha(1.0f);
            ivHeaderSync.setImageTintList(ColorStateList.valueOf(blue));
            btnHeaderConnect.setBackgroundResource(R.drawable.bg_circle_green);
            ivHeaderConnect.setImageTintList(ColorStateList.valueOf(green));
        } else if (isScanning || isConnecting) {
            statusDot.setBackgroundTintList(ColorStateList.valueOf(amber));
            btnHeaderSync.setEnabled(false);
            btnHeaderSync.setAlpha(0.45f);
            ivHeaderSync.setImageTintList(ColorStateList.valueOf(primaryText));
            btnHeaderConnect.setBackgroundResource(R.drawable.bg_circle_amber);
            ivHeaderConnect.setImageTintList(ColorStateList.valueOf(amber));
        } else {
            statusDot.setBackgroundTintList(ColorStateList.valueOf(muted));
            btnHeaderSync.setEnabled(false);
            btnHeaderSync.setAlpha(0.45f);
            ivHeaderSync.setImageTintList(ColorStateList.valueOf(primaryText));
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
        if (!isConnected || bleService == null) {
            Toast.makeText(this, "Connect to the board via Bluetooth first", Toast.LENGTH_SHORT).show();
            return;
        }
        bleService.sendBleCommand(cmd);
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMISSION_REQ_CODE);
        } else {
            startBleServiceAndScan();
        }
    }

    private void startBleServiceAndScan() {
        Intent svcIntent = new Intent(this, AdsbBleService.class);
        ContextCompat.startForegroundService(this, svcIntent);
        if (bleService != null) {
            bleService.startScanAndConnect();
        } else {
            bindService(svcIntent, serviceConnection, Context.BIND_AUTO_CREATE);
            mainHandler.postDelayed(() -> {
                if (bleService != null) bleService.startScanAndConnect();
            }, 300L);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            boolean bleGranted = true;
            for (int i = 0; i < permissions.length; i++) {
                if (!Manifest.permission.POST_NOTIFICATIONS.equals(permissions[i])
                        && grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    bleGranted = false;
                }
            }
            if (bleGranted) startBleServiceAndScan();
            else Toast.makeText(this, "Bluetooth permissions are required to scan", Toast.LENGTH_LONG).show();
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
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(logAccumulator.toString().getBytes(StandardCharsets.UTF_8));
            }
            Toast.makeText(this, "File saved to:\nDownload/" + fileName, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error saving file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // =========================================================================
    // AdsbBleService.Listener callbacks
    // =========================================================================

    @Override
    public void onConnectionStateChanged(boolean connected, boolean scanning, boolean connecting, String statusText) {
        isConnected = connected;
        isScanning = scanning;
        isConnecting = connecting;
        tvStatus.setText(statusText);
        if (!connected) {
            lastBatteryPct = -1;
            lastBatteryMv = -1;
            updateBatteryUI();
        }
        updateConnectionUI();
    }

    @Override
    public void onStatusUpdate(long dialHz, long bursts, boolean ready, int batteryPct, int batteryMv) {
        if (dialHz > 0) {
            currentDialHz = dialHz;
            updateFrequencyDisplays();
        }
        lastBatteryPct = batteryPct;
        lastBatteryMv = batteryMv;
        updateBatteryUI();
        tvBoardInfo.setText(String.format(Locale.getDefault(),
                "%.2f MHz · %s", currentDialHz / 1e6, ready ? "Radio Ready" : "Radio Init"));
        tvBurstCount.setText(String.format(Locale.getDefault(), "%d pkt", bursts));
        tvBurstCount.setTextColor(ContextCompat.getColor(this, ready ? R.color.rev_green : R.color.rev_amber));
    }

    private void updateBatteryUI() {
        if (!isConnected || lastBatteryPct < 0) {
            tvBatteryPill.setVisibility(View.GONE);
            return;
        }
        tvBatteryPill.setVisibility(View.VISIBLE);
        tvBatteryPill.setText(String.format(Locale.getDefault(), "%d%%", lastBatteryPct));
        int fgRes, bgRes;
        if (lastBatteryPct >= 50) {
            fgRes = R.color.rev_green;
            bgRes = R.color.rev_green_soft;
        } else if (lastBatteryPct >= 20) {
            fgRes = R.color.rev_amber;
            bgRes = R.color.rev_amber_soft;
        } else {
            fgRes = R.color.rev_red;
            bgRes = R.color.rev_red_soft;
        }
        int fg = ContextCompat.getColor(this, fgRes);
        int bg = ContextCompat.getColor(this, bgRes);
        tvBatteryPill.setTextColor(fg);
        tvBatteryPill.setBackgroundTintList(ColorStateList.valueOf(bg));
        TextViewCompat.setCompoundDrawableTintList(tvBatteryPill, ColorStateList.valueOf(fg));
    }

    @Override
    public void onFlightsUpdated() {
        refreshAllFlightViews();
    }

    @Override
    public void onLogLine(String line) {
        logAccumulator.append(line).append("\n");
        logLineCount++;
        updateLogCountText();
        tvLogs.append(line + "\n");
        scrollLogs.post(() -> scrollLogs.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    public void onBoardMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Do NOT disconnect BLE here — AdsbBleService keeps receiving in background!
        dbExecutor.shutdownNow();
        if (flightDb != null) flightDb.close();
    }
}
