package com.heltec.adsb;

/**
 * Represents an ADS-B flight sighting aggregated in the local SQLite database.
 */
public class FlightRecord {
    public static final long LIVE_THRESHOLD_MS = 2 * 60 * 1000L;

    public long id;
    public String callsign;
    public String icao;
    public int lastAltitude;
    public int maxAltitude;
    public int lastSpeed;
    public double lastLat;
    public double lastLon;
    public long firstSeenAt;
    public long lastSeenAt;
    public int packetCount;

    public FlightRecord() {
        this.lastAltitude = -1;
        this.maxAltitude = -1;
        this.lastSpeed = -1;
        this.lastLat = Double.NaN;
        this.lastLon = Double.NaN;
    }

    public boolean hasCoords() {
        return !Double.isNaN(lastLat) && !Double.isNaN(lastLon)
                && (lastLat != 0.0 || lastLon != 0.0);
    }

    public boolean isLive(long nowMs) {
        return (nowMs - lastSeenAt) <= LIVE_THRESHOLD_MS;
    }
}
