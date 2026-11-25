package com.heltec.adsb;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.util.LruCache;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Optional one-time downloadable offline aircraft catalog (ICAO hex -> model, registration, operator, military).
 * Downloads gzipped NDJSON from ADS-B Exchange public open dataset and stores it locally in SQLite.
 */
public class AircraftDbHelper extends SQLiteOpenHelper {

    public static final String DOWNLOAD_URL =
            "https://downloads.adsbexchange.com/downloads/basic-ac-db.json.gz";

    private static final String DB_NAME = "aircraft_catalog.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE_AIRCRAFT = "aircraft";
    private static final String PREF_COUNT = "aircraft_db_count";
    private static final String PREF_UPDATED = "aircraft_db_updated";

    public static final class AircraftInfo {
        public final String icao;
        public final String reg;
        public final String type;
        public final String model;
        public final String operator;
        public final boolean military;

        public AircraftInfo(String icao, String reg, String type, String model, String operator, boolean military) {
            this.icao = icao;
            this.reg = reg;
            this.type = type;
            this.model = model;
            this.operator = operator;
            this.military = military;
        }

        public String displayModel() {
            if (!model.isEmpty()) return model;
            return type;
        }
    }

    public interface ProgressCallback {
        void onProgress(int importedCount);
    }

    private static volatile AircraftDbHelper instance;
    private final Context appContext;
    private final LruCache<String, AircraftInfo> cache = new LruCache<>(256);
    private static final AircraftInfo NOT_FOUND = new AircraftInfo("", "", "", "", "", false);

    public static AircraftDbHelper getInstance(Context context) {
        if (instance == null) {
            synchronized (AircraftDbHelper.class) {
                if (instance == null) {
                    instance = new AircraftDbHelper(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private AircraftDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        this.appContext = context.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_AIRCRAFT + " ("
                + "icao TEXT PRIMARY KEY, "
                + "reg TEXT, "
                + "type TEXT, "
                + "model TEXT, "
                + "operator TEXT, "
                + "mil INTEGER NOT NULL DEFAULT 0"
                + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_AIRCRAFT);
        onCreate(db);
    }

    public int getCachedCount() {
        SharedPreferences prefs = appContext.getSharedPreferences(AdsbBleService.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(PREF_COUNT, 0);
    }

    public long getLastUpdatedMs() {
        SharedPreferences prefs = appContext.getSharedPreferences(AdsbBleService.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getLong(PREF_UPDATED, 0L);
    }

    /**
     * Fast offline lookup by 6-char hex ICAO address. Returns null if DB not downloaded or not found.
     */
    public AircraftInfo lookup(String icao) {
        if (!ModeSDecoder.isValidIcao(icao) || getCachedCount() == 0) {
            return null;
        }
        String key = icao.trim().toUpperCase(Locale.US);
        AircraftInfo cached = cache.get(key);
        if (cached != null) {
            return cached == NOT_FOUND ? null : cached;
        }

        try {
            SQLiteDatabase db = getReadableDatabase();
            try (Cursor c = db.query(
                    TABLE_AIRCRAFT,
                    new String[]{"reg", "type", "model", "operator", "mil"},
                    "icao = ?",
                    new String[]{key},
                    null, null, null, "1"
            )) {
                if (c.moveToFirst()) {
                    AircraftInfo info = new AircraftInfo(
                            key,
                            safe(c.getString(0)),
                            safe(c.getString(1)),
                            safe(c.getString(2)),
                            safe(c.getString(3)),
                            c.getInt(4) == 1
                    );
                    cache.put(key, info);
                    return info;
                }
            }
            cache.put(key, NOT_FOUND);
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Downloads the gzipped NDJSON aircraft catalog and bulk-imports it into local SQLite.
     * Must be called on a background thread.
     */
    public synchronized int downloadAndImport(ProgressCallback callback) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(DOWNLOAD_URL).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "Heltec-ADSB-Companion/1.0");
        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            throw new Exception("HTTP " + code);
        }

        SQLiteDatabase db = getWritableDatabase();
        int count = 0;
        try {
            db.execSQL("PRAGMA synchronous = OFF");
            db.execSQL("DELETE FROM " + TABLE_AIRCRAFT);
            cache.evictAll();

            SQLiteStatement stmt = db.compileStatement(
                    "INSERT OR REPLACE INTO " + TABLE_AIRCRAFT
                            + " (icao, reg, type, model, operator, mil) VALUES (?, ?, ?, ?, ?, ?)");

            db.beginTransaction();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            new GZIPInputStream(new BufferedInputStream(conn.getInputStream(), 32768)),
                            StandardCharsets.UTF_8), 32768)) {

                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) != '{') continue;
                    try {
                        JSONObject obj = new JSONObject(line);
                        String icao = obj.optString("icao", "").trim().toUpperCase(Locale.US);
                        if (icao.length() != 6) continue;

                        String reg = cleanJsonStr(obj, "reg");
                        String type = cleanJsonStr(obj, "icaotype");
                        String mfg = cleanJsonStr(obj, "manufacturer");
                        String mdl = cleanJsonStr(obj, "model");
                        String op = cleanJsonStr(obj, "ownop");
                        boolean mil = obj.optBoolean("mil", false);

                        // Skip ground vehicles / towers with no useful aircraft info
                        if ("GND".equals(type) || "TWR".equals(type)) continue;

                        String fullModel = buildFullModel(mfg, mdl, type);
                        if (reg.isEmpty() && fullModel.isEmpty() && op.isEmpty() && !mil) continue;

                        stmt.clearBindings();
                        stmt.bindString(1, icao);
                        stmt.bindString(2, reg);
                        stmt.bindString(3, type);
                        stmt.bindString(4, fullModel);
                        stmt.bindString(5, op);
                        stmt.bindLong(6, mil ? 1L : 0L);
                        stmt.executeInsert();
                        count++;

                        if (count % 10000 == 0) {
                            db.setTransactionSuccessful();
                            db.endTransaction();
                            db.beginTransaction();
                            if (callback != null) callback.onProgress(count);
                        }
                    } catch (Exception ignored) {}
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            SharedPreferences prefs = appContext.getSharedPreferences(AdsbBleService.PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putInt(PREF_COUNT, count)
                    .putLong(PREF_UPDATED, System.currentTimeMillis())
                    .apply();
        } finally {
            conn.disconnect();
            try { db.execSQL("PRAGMA synchronous = NORMAL"); } catch (Exception ignored) {}
        }
        return count;
    }

    public synchronized void clearDatabase() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_AIRCRAFT, null, null);
        cache.evictAll();
        SharedPreferences prefs = appContext.getSharedPreferences(AdsbBleService.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putInt(PREF_COUNT, 0).putLong(PREF_UPDATED, 0L).apply();
    }

    private static String cleanJsonStr(JSONObject obj, String key) {
        if (!obj.has(key) || obj.isNull(key)) return "";
        String val = obj.optString(key, "").trim();
        return "null".equalsIgnoreCase(val) ? "" : val;
    }

    private static String buildFullModel(String mfg, String mdl, String type) {
        if (!mfg.isEmpty() && !mdl.isEmpty()) {
            if (mdl.toLowerCase(Locale.US).startsWith(mfg.toLowerCase(Locale.US))) {
                return mdl;
            }
            return mfg + " " + mdl;
        }
        if (!mdl.isEmpty()) return mdl;
        return type;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
