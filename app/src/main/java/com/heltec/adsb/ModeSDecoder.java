package com.heltec.adsb;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure-Java decoder for Mode-S DF11 / DF17 raw hex frames emitted by the Heltec V3 firmware
 * in SXOBS and SXLOG JSON records, plus strict aircraft identifier validation.
 */
public final class ModeSDecoder {

    private static final String CALLSIGN_CHARSET =
            "#ABCDEFGHIJKLMNOPQRSTUVWXYZ##### ###############0123456789######";
    private static final Pattern VALID_CALLSIGN = Pattern.compile("^[A-Z0-9]{2,8}$");
    private static final Pattern VALID_ICAO = Pattern.compile("^[0-9A-F]{6}$");

    public static final class DecodedFrame {
        public String icao = "";
        public String callsign = "";
        public int altitude = -1;
        public int speed = -1;
    }

    private ModeSDecoder() {}

    /**
     * Validates that a string is a genuine ADS-B callsign or 6-hex ICAO address,
     * rejecting OLED menu/scope strings such as "A-97 P-78 dBm", "Hold -84 dBm",
     * "1090 MHz", "TEST CRC OK", "Listening", or "NO FLIGHT".
     */
    public static boolean isValidAircraftIdentifier(String raw) {
        if (raw == null) return false;
        String s = raw.trim().toUpperCase(Locale.US);
        if (s.isEmpty()) return false;
        // Reject anything with spaces, minus signs, dBm, or known non-aircraft OLED labels
        if (s.contains(" ") || s.contains("-") || s.contains("DBM") || s.contains("MHZ")) {
            return false;
        }
        if (s.equals("HOLD") || s.equals("LIVE") || s.equals("TEST") || s.equals("NONE")
                || s.equals("LISTENING") || s.equals("TUNING") || s.equals("SAVED")) {
            return false;
        }
        return VALID_CALLSIGN.matcher(s).matches();
    }

    public static boolean isValidIcao(String raw) {
        if (raw == null) return false;
        return VALID_ICAO.matcher(raw.trim().toUpperCase(Locale.US)).matches();
    }

    /**
     * Decodes a 14-char (DF11) or 28-char (DF17) hex Mode-S frame.
     * Returns null if invalid or if it is a DF2 TEST frame.
     */
    public static DecodedFrame decodeHex(String hex) {
        if (hex == null) return null;
        String clean = hex.trim();
        int len = clean.length();
        if (len != 14 && len != 28) return null;

        byte[] w = hexToBytes(clean);
        if (w == null) return null;

        int df = (w[0] & 0xFF) >> 3;
        if (df != 11 && df != 17) return null;

        int icaoInt = ((w[1] & 0xFF) << 16) | ((w[2] & 0xFF) << 8) | (w[3] & 0xFF);
        if (icaoInt == 0 || icaoInt == 0xFFFFFF) return null;

        DecodedFrame frame = new DecodedFrame();
        frame.icao = String.format(Locale.US, "%06X", icaoInt);

        if (df == 17 && w.length == 14) {
            int tc = (w[4] & 0xFF) >> 3;
            if (tc >= 1 && tc <= 4) {
                String cs = decodeCallsign(w);
                if (isValidAircraftIdentifier(cs)) {
                    frame.callsign = cs;
                }
            } else if ((tc >= 9 && tc <= 18) || (tc >= 20 && tc <= 22)) {
                int altCode = ((w[5] & 0xFF) << 4) | ((w[6] & 0xFF) >> 4);
                int alt = decodeAltitude(altCode);
                if (alt >= -1000 && alt <= 65000) {
                    frame.altitude = Math.max(0, alt);
                }
            } else if (tc == 19) {
                int spd = decodeVelocityKt(w);
                if (spd >= 0 && spd <= 1500) {
                    frame.speed = spd;
                }
            }
        }
        return frame;
    }

    private static String decodeCallsign(byte[] w) {
        int[] chars = new int[8];
        chars[0] = (w[5] & 0xFF) >> 2;
        chars[1] = ((w[5] & 0x03) << 4) | ((w[6] & 0xFF) >> 4);
        chars[2] = ((w[6] & 0x0F) << 2) | ((w[7] & 0xFF) >> 6);
        chars[3] = (w[7] & 0x3F);
        chars[4] = (w[8] & 0xFF) >> 2;
        chars[5] = ((w[8] & 0x03) << 4) | ((w[9] & 0xFF) >> 4);
        chars[6] = ((w[9] & 0x0F) << 2) | ((w[10] & 0xFF) >> 6);
        chars[7] = (w[10] & 0x3F);

        StringBuilder sb = new StringBuilder(8);
        for (int c : chars) {
            if (c >= 0 && c < CALLSIGN_CHARSET.length()) {
                char ch = CALLSIGN_CHARSET.charAt(c);
                if (ch != '#') {
                    sb.append(ch);
                }
            }
        }
        return sb.toString().trim();
    }

    private static int gray(int code, int[] positions) {
        int number = 0;
        int bit = 0;
        for (int pos : positions) {
            bit ^= (code >> pos) & 1;
            number = number * 2 + bit;
        }
        return number;
    }

    private static int decodeAltitude(int code) {
        if (code == 0) return -9999;
        if ((code & 16) != 0) {
            return 25 * ((code >> 5) * 16 + (code & 15)) - 1000;
        }
        int fine = gray(code, new int[]{11, 9, 7});
        if (fine == 0 || fine == 5 || fine == 6) return -9999;
        if (fine == 7) fine = 5;
        int coarse = gray(code, new int[]{2, 0, 10, 8, 6, 5, 3, 1});
        return 500 * coarse + 100 * ((coarse & 1) != 0 ? 6 - fine : fine) - 1300;
    }

    private static int decodeVelocityKt(byte[] w) {
        int sub = w[4] & 7;
        if (sub < 1 || sub > 4) return -1;
        int scale = (sub == 2 || sub == 4) ? 4 : 1;
        int ew = ((w[5] & 3) << 8) | (w[6] & 0xFF);
        int ns = ((w[7] & 127) << 3) | ((w[8] & 0xFF) >> 5);
        if (sub <= 2) {
            if (ew == 0 || ns == 0) return -1;
            int east = (ew - 1) * scale * (((w[5] & 4) != 0) ? -1 : 1);
            int north = (ns - 1) * scale * (((w[7] & 128) != 0) ? -1 : 1);
            return (int) Math.round(Math.hypot(east, north));
        } else {
            if (ns == 0) return -1;
            return (ns - 1) * scale;
        }
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi == -1 || lo == -1) return null;
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
