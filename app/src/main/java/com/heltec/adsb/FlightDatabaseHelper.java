package com.heltec.adsb;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Local SQLite storage for detected ADS-B flights.
 * Deduplicates repeated bursts from the same aircraft within a 30-minute session window.
 */
public class FlightDatabaseHelper extends SQLiteOpenHelper {

    public enum SortMode {
        RECENT,
        ALTITUDE,
        PACKETS
    }

    private static final String DB_NAME = "adsb_flights.db";
    private static final int DB_VERSION = 1;
    private static final long SESSION_WINDOW_MS = 30 * 60 * 1000L;
    private static final long SYNC_DEDUP_WINDOW_MS = 12 * 60 * 60 * 1000L;

    private static final String TABLE_FLIGHTS = "flights";
    private static final String COL_ID = "id";
    private static final String COL_CALLSIGN = "callsign";
    private static final String COL_ICAO = "icao";
    private static final String COL_LAST_ALT = "last_altitude";
    private static final String COL_MAX_ALT = "max_altitude";
    private static final String COL_LAST_SPEED = "last_speed";
    private static final String COL_LAST_LAT = "last_lat";
    private static final String COL_LAST_LON = "last_lon";
    private static final String COL_FIRST_SEEN = "first_seen_at";
    private static final String COL_LAST_SEEN = "last_seen_at";
    private static final String COL_PACKET_COUNT = "packet_count";

    public FlightDatabaseHelper(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_FLIGHTS + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_CALLSIGN + " TEXT NOT NULL, "
                + COL_ICAO + " TEXT, "
                + COL_LAST_ALT + " INTEGER NOT NULL DEFAULT -1, "
                + COL_MAX_ALT + " INTEGER NOT NULL DEFAULT -1, "
                + COL_LAST_SPEED + " INTEGER NOT NULL DEFAULT -1, "
                + COL_LAST_LAT + " REAL, "
                + COL_LAST_LON + " REAL, "
                + COL_FIRST_SEEN + " INTEGER NOT NULL, "
                + COL_LAST_SEEN + " INTEGER NOT NULL, "
                + COL_PACKET_COUNT + " INTEGER NOT NULL DEFAULT 1"
                + ")");
        db.execSQL("CREATE INDEX idx_flights_last_seen ON " + TABLE_FLIGHTS + "(" + COL_LAST_SEEN + " DESC)");
        db.execSQL("CREATE INDEX idx_flights_callsign ON " + TABLE_FLIGHTS + "(" + COL_CALLSIGN + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_FLIGHTS);
        onCreate(db);
    }

    /**
     * Inserts a new flight sighting or updates an existing active session.
     * Uses a wider symmetric window for board syncs so repeated syncs never create duplicates.
     */
    public synchronized void upsertFlight(
            String callsign,
            String icao,
            int altitude,
            int speed,
            double lat,
            double lon,
            long timestampMs,
            int packetDelta
    ) {
        upsertFlight(callsign, icao, altitude, speed, lat, lon, timestampMs, packetDelta, false);
    }

    public synchronized void upsertFlight(
            String callsign,
            String icao,
            int altitude,
            int speed,
            double lat,
            double lon,
            long timestampMs,
            int packetDelta,
            boolean isFromSync
    ) {
        String cleanCallsign = callsign != null ? callsign.trim().toUpperCase(java.util.Locale.US) : "";
        String cleanIcao = icao != null ? icao.trim().toUpperCase(java.util.Locale.US) : "";

        if (!ModeSDecoder.isValidAircraftIdentifier(cleanCallsign)) {
            cleanCallsign = "";
        }
        if (!ModeSDecoder.isValidIcao(cleanIcao)) {
            cleanIcao = "";
        }
        if (cleanCallsign.isEmpty() && cleanIcao.isEmpty()) {
            return;
        }
        if (cleanCallsign.isEmpty()) {
            cleanCallsign = cleanIcao;
        }
        int delta = Math.max(0, packetDelta);

        SQLiteDatabase db = getWritableDatabase();
        boolean hasTimestamp = timestampMs > 0L;
        long nowMs = System.currentTimeMillis();
        long refTime = hasTimestamp ? timestampMs : nowMs;
        long window = isFromSync ? SYNC_DEDUP_WINDOW_MS : SESSION_WINDOW_MS;
        long minTime = refTime - window;
        long maxTime = refTime + window;

        String selection;
        String[] selectionArgs;
        if (!cleanIcao.isEmpty()) {
            selection = "(" + COL_ICAO + " = ? OR " + COL_CALLSIGN + " = ? OR " + COL_CALLSIGN + " = ?) AND "
                    + COL_LAST_SEEN + " >= ? AND " + COL_LAST_SEEN + " <= ?";
            selectionArgs = new String[]{cleanIcao, cleanCallsign, cleanIcao,
                    String.valueOf(minTime), String.valueOf(maxTime)};
        } else {
            selection = "(" + COL_CALLSIGN + " = ? OR " + COL_ICAO + " = ?) AND "
                    + COL_LAST_SEEN + " >= ? AND " + COL_LAST_SEEN + " <= ?";
            selectionArgs = new String[]{cleanCallsign, cleanCallsign,
                    String.valueOf(minTime), String.valueOf(maxTime)};
        }

        try (Cursor c = db.query(
                TABLE_FLIGHTS,
                new String[]{COL_ID, COL_MAX_ALT, COL_PACKET_COUNT, COL_CALLSIGN, COL_ICAO, COL_FIRST_SEEN, COL_LAST_SEEN},
                selection,
                selectionArgs,
                null,
                null,
                COL_LAST_SEEN + " DESC",
                "1"
        )) {
            if (c.moveToFirst()) {
                long existingId = c.getLong(0);
                int existingMaxAlt = c.getInt(1);
                int existingCount = c.getInt(2);
                String existingCallsign = c.getString(3);
                String existingIcao = c.getString(4);
                long existingFirstSeen = c.getLong(5);
                long existingLastSeen = c.getLong(6);

                ContentValues cv = new ContentValues();
                // Prefer a real callsign over an ICAO fallback if one becomes available.
                boolean incomingIsRealCallsign = !cleanCallsign.isEmpty() && !cleanCallsign.equals(cleanIcao);
                boolean existingIsIcaoOnly = existingCallsign == null || existingCallsign.isEmpty()
                        || existingCallsign.equalsIgnoreCase(existingIcao)
                        || existingCallsign.equalsIgnoreCase(cleanIcao);
                if (incomingIsRealCallsign || existingIsIcaoOnly) {
                    cv.put(COL_CALLSIGN, cleanCallsign);
                }
                if (!cleanIcao.isEmpty() && (existingIcao == null || existingIcao.isEmpty())) {
                    cv.put(COL_ICAO, cleanIcao);
                }
                if (altitude >= 0) {
                    cv.put(COL_LAST_ALT, altitude);
                    cv.put(COL_MAX_ALT, Math.max(existingMaxAlt, altitude));
                }
                if (speed >= 0) {
                    cv.put(COL_LAST_SPEED, speed);
                }
                if (!Double.isNaN(lat) && !Double.isNaN(lon) && (lat != 0.0 || lon != 0.0)) {
                    cv.put(COL_LAST_LAT, lat);
                    cv.put(COL_LAST_LON, lon);
                }
                if (hasTimestamp) {
                    cv.put(COL_FIRST_SEEN, Math.min(existingFirstSeen, timestampMs));
                    cv.put(COL_LAST_SEEN, Math.max(existingLastSeen, timestampMs));
                }
                if (delta > 0) {
                    cv.put(COL_PACKET_COUNT, existingCount + delta);
                }

                if (cv.size() > 0) {
                    db.update(TABLE_FLIGHTS, cv, COL_ID + " = ?", new String[]{String.valueOf(existingId)});
                }
                // If we now know BOTH callsign and ICAO, merge any separate ICAO-only / callsign-only row immediately
                if (incomingIsRealCallsign && !cleanIcao.isEmpty()) {
                    deduplicateFlights();
                }
                return;
            }
        }

        // For old flights from a previous board boot (no age), avoid falsely marking them LIVE
        long insertTs = hasTimestamp ? timestampMs : (nowMs - FlightRecord.LIVE_THRESHOLD_MS - 60_000L);

        ContentValues cv = new ContentValues();
        cv.put(COL_CALLSIGN, cleanCallsign);
        if (!cleanIcao.isEmpty()) {
            cv.put(COL_ICAO, cleanIcao);
        }
        cv.put(COL_LAST_ALT, altitude);
        cv.put(COL_MAX_ALT, altitude);
        cv.put(COL_LAST_SPEED, speed);
        if (!Double.isNaN(lat) && !Double.isNaN(lon) && (lat != 0.0 || lon != 0.0)) {
            cv.put(COL_LAST_LAT, lat);
            cv.put(COL_LAST_LON, lon);
        }
        cv.put(COL_FIRST_SEEN, insertTs);
        cv.put(COL_LAST_SEEN, insertTs);
        cv.put(COL_PACKET_COUNT, Math.max(1, delta));

        db.insert(TABLE_FLIGHTS, null, cv);
    }

    /**
     * Merges any duplicate rows for the same aircraft (matching ICAO or callsign within 12 hours).
     */
    public synchronized int deduplicateFlights() {
        List<FlightRecord> all = queryFlights("", false, false, SortMode.RECENT);
        List<FlightRecord> kept = new ArrayList<>();
        int mergedCount = 0;
        SQLiteDatabase db = getWritableDatabase();

        for (FlightRecord r : all) {
            FlightRecord match = null;
            for (FlightRecord k : kept) {
                boolean sameIcao = r.icao != null && !r.icao.isEmpty()
                        && (r.icao.equalsIgnoreCase(k.icao) || r.icao.equalsIgnoreCase(k.callsign));
                boolean sameCallsign = r.callsign != null && !r.callsign.isEmpty()
                        && (r.callsign.equalsIgnoreCase(k.callsign) || r.callsign.equalsIgnoreCase(k.icao));
                if ((sameIcao || sameCallsign)
                        && Math.abs(k.lastSeenAt - r.lastSeenAt) <= SYNC_DEDUP_WINDOW_MS) {
                    match = k;
                    break;
                }
            }

            if (match == null) {
                kept.add(r);
            } else {
                // Merge useful fields into the newer kept record and delete the duplicate
                ContentValues cv = new ContentValues();
                if ((match.icao == null || match.icao.isEmpty()) && r.icao != null && !r.icao.isEmpty()) {
                    match.icao = r.icao;
                    cv.put(COL_ICAO, r.icao);
                }
                if (match.callsign.equalsIgnoreCase(match.icao)
                        && r.callsign != null && !r.callsign.isEmpty() && !r.callsign.equalsIgnoreCase(r.icao)) {
                    match.callsign = r.callsign;
                    cv.put(COL_CALLSIGN, r.callsign);
                }
                if (match.lastAltitude < 0 && r.lastAltitude >= 0) {
                    match.lastAltitude = r.lastAltitude;
                    cv.put(COL_LAST_ALT, r.lastAltitude);
                }
                int maxAlt = Math.max(match.maxAltitude, r.maxAltitude);
                if (maxAlt > match.maxAltitude) {
                    match.maxAltitude = maxAlt;
                    cv.put(COL_MAX_ALT, maxAlt);
                }
                if (match.lastSpeed < 0 && r.lastSpeed >= 0) {
                    match.lastSpeed = r.lastSpeed;
                    cv.put(COL_LAST_SPEED, r.lastSpeed);
                }
                if (!match.hasCoords() && r.hasCoords()) {
                    match.lastLat = r.lastLat;
                    match.lastLon = r.lastLon;
                    cv.put(COL_LAST_LAT, r.lastLat);
                    cv.put(COL_LAST_LON, r.lastLon);
                }
                if (r.firstSeenAt < match.firstSeenAt) {
                    match.firstSeenAt = r.firstSeenAt;
                    cv.put(COL_FIRST_SEEN, r.firstSeenAt);
                }
                if (cv.size() > 0) {
                    db.update(TABLE_FLIGHTS, cv, COL_ID + " = ?", new String[]{String.valueOf(match.id)});
                }
                db.delete(TABLE_FLIGHTS, COL_ID + " = ?", new String[]{String.valueOf(r.id)});
                mergedCount++;
            }
        }
        return mergedCount;
    }

    /**
     * Removes any previously recorded non-aircraft rows (such as OLED RSSI or Hold lines).
     */
    public synchronized int purgeInvalidFlights() {
        SQLiteDatabase db = getWritableDatabase();
        List<Long> invalidIds = new ArrayList<>();
        try (Cursor c = db.query(TABLE_FLIGHTS, new String[]{COL_ID, COL_CALLSIGN}, null, null, null, null, null)) {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String cs = c.getString(1);
                if (!ModeSDecoder.isValidAircraftIdentifier(cs)) {
                    invalidIds.add(id);
                }
            }
        }
        for (Long id : invalidIds) {
            db.delete(TABLE_FLIGHTS, COL_ID + " = ?", new String[]{String.valueOf(id)});
        }
        return invalidIds.size();
    }

    public synchronized int getTodayFlightCount() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_FLIGHTS + " WHERE " + COL_LAST_SEEN + " >= ?",
                new String[]{String.valueOf(getStartOfTodayMs())}
        )) {
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
        }
        return 0;
    }

    public synchronized List<FlightRecord> getRecentFlights(int limit) {
        List<FlightRecord> all = queryFlights("", false, false, SortMode.RECENT);
        if (all.size() <= limit) return all;
        return new ArrayList<>(all.subList(0, limit));
    }

    public synchronized List<FlightRecord> queryFlights(
            String searchQuery,
            boolean onlyToday,
            boolean onlyWithGps,
            SortMode sortMode
    ) {
        SQLiteDatabase db = getReadableDatabase();
        StringBuilder where = new StringBuilder("1=1");
        List<String> args = new ArrayList<>();

        if (searchQuery != null && !searchQuery.trim().isEmpty()) {
            String like = "%" + searchQuery.trim() + "%";
            where.append(" AND (").append(COL_CALLSIGN).append(" LIKE ? OR ")
                    .append(COL_ICAO).append(" LIKE ?)");
            args.add(like);
            args.add(like);
        }

        if (onlyToday) {
            where.append(" AND ").append(COL_LAST_SEEN).append(" >= ?");
            args.add(String.valueOf(getStartOfTodayMs()));
        }

        if (onlyWithGps) {
            where.append(" AND ").append(COL_LAST_LAT).append(" IS NOT NULL AND ")
                    .append(COL_LAST_LON).append(" IS NOT NULL");
        }

        String orderBy;
        if (sortMode == SortMode.ALTITUDE) {
            orderBy = COL_MAX_ALT + " DESC, " + COL_LAST_SEEN + " DESC";
        } else if (sortMode == SortMode.PACKETS) {
            orderBy = COL_PACKET_COUNT + " DESC, " + COL_LAST_SEEN + " DESC";
        } else {
            orderBy = COL_LAST_SEEN + " DESC";
        }

        List<FlightRecord> results = new ArrayList<>();
        try (Cursor c = db.query(
                TABLE_FLIGHTS,
                null,
                where.toString(),
                args.toArray(new String[0]),
                null,
                null,
                orderBy
        )) {
            int idxId = c.getColumnIndexOrThrow(COL_ID);
            int idxCallsign = c.getColumnIndexOrThrow(COL_CALLSIGN);
            int idxIcao = c.getColumnIndexOrThrow(COL_ICAO);
            int idxLastAlt = c.getColumnIndexOrThrow(COL_LAST_ALT);
            int idxMaxAlt = c.getColumnIndexOrThrow(COL_MAX_ALT);
            int idxLastSpeed = c.getColumnIndexOrThrow(COL_LAST_SPEED);
            int idxLat = c.getColumnIndexOrThrow(COL_LAST_LAT);
            int idxLon = c.getColumnIndexOrThrow(COL_LAST_LON);
            int idxFirstSeen = c.getColumnIndexOrThrow(COL_FIRST_SEEN);
            int idxLastSeen = c.getColumnIndexOrThrow(COL_LAST_SEEN);
            int idxPackets = c.getColumnIndexOrThrow(COL_PACKET_COUNT);

            while (c.moveToNext()) {
                FlightRecord r = new FlightRecord();
                r.id = c.getLong(idxId);
                r.callsign = c.getString(idxCallsign);
                r.icao = c.isNull(idxIcao) ? "" : c.getString(idxIcao);
                r.lastAltitude = c.getInt(idxLastAlt);
                r.maxAltitude = c.getInt(idxMaxAlt);
                r.lastSpeed = c.getInt(idxLastSpeed);
                r.lastLat = c.isNull(idxLat) ? Double.NaN : c.getDouble(idxLat);
                r.lastLon = c.isNull(idxLon) ? Double.NaN : c.getDouble(idxLon);
                r.firstSeenAt = c.getLong(idxFirstSeen);
                r.lastSeenAt = c.getLong(idxLastSeen);
                r.packetCount = c.getInt(idxPackets);
                results.add(r);
            }
        }
        return results;
    }

    public synchronized int getTotalFlightCount() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_FLIGHTS, null)) {
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
        }
        return 0;
    }

    public synchronized void deleteFlight(long id) {
        getWritableDatabase().delete(TABLE_FLIGHTS, COL_ID + " = ?", new String[]{String.valueOf(id)});
    }

    public synchronized void clearAllFlights() {
        getWritableDatabase().delete(TABLE_FLIGHTS, null, null);
    }

    private long getStartOfTodayMs() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }
}
