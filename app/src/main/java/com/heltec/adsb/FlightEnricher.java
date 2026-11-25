package com.heltec.adsb;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 100% offline ADS-B flight enrichment:
 * - Airline / operator lookup from 3-letter ICAO callsign prefix (plus Italian GA I-XXXX)
 * - Country of registration from 24-bit ICAO hex allocation ranges
 * - Nearest city / airport (distance + compass direction) from GPS coordinates via Haversine
 */
public final class FlightEnricher {

    private static final Map<String, String> AIRLINES = new HashMap<>();

    static {
        // Italy & Mediterranean
        AIRLINES.put("ITY", "ITA Airways");
        AIRLINES.put("AZA", "Alitalia / ITA");
        AIRLINES.put("DLA", "Air Dolomiti");
        AIRLINES.put("NOS", "Neos");
        AIRLINES.put("VOE", "Volotea");
        AIRLINES.put("SZN", "Aeroitalia");
        AIRLINES.put("AEZ", "Aeroitalia");
        AIRLINES.put("SLM", "SkyAlps");
        AIRLINES.put("SWU", "SkyAlps");
        AIRLINES.put("MSA", "Mistral Air / Poste");
        AIRLINES.put("IAM", "Italian Air Force");
        AIRLINES.put("MMI", "Italian Navy");
        AIRLINES.put("GFI", "Guardia di Finanza");
        AIRLINES.put("POL", "Polizia di Stato");
        AIRLINES.put("VFS", "Vigili del Fuoco");

        // Major European Low-Cost & Flag Carriers
        AIRLINES.put("RYR", "Ryanair");
        AIRLINES.put("RUK", "Ryanair UK");
        AIRLINES.put("MAY", "Malta Air");
        AIRLINES.put("ALX", "Malta Air");
        AIRLINES.put("LDA", "Lauda Europe");
        AIRLINES.put("LDM", "Lauda Europe");
        AIRLINES.put("EJU", "easyJet Europe");
        AIRLINES.put("EZY", "easyJet");
        AIRLINES.put("EZS", "easyJet Switzerland");
        AIRLINES.put("WMT", "Wizz Air Malta");
        AIRLINES.put("WZZ", "Wizz Air");
        AIRLINES.put("WUK", "Wizz Air UK");
        AIRLINES.put("VLG", "Vueling");
        AIRLINES.put("DLH", "Lufthansa");
        AIRLINES.put("CLH", "Lufthansa CityLine");
        AIRLINES.put("OCN", "Discover Airlines");
        AIRLINES.put("EWG", "Eurowings");
        AIRLINES.put("CFG", "Condor");
        AIRLINES.put("AFR", "Air France");
        AIRLINES.put("HOP", "Air France Hop");
        AIRLINES.put("TVF", "Transavia France");
        AIRLINES.put("TRA", "Transavia");
        AIRLINES.put("KLM", "KLM");
        AIRLINES.put("KLC", "KLM Cityhopper");
        AIRLINES.put("BAW", "British Airways");
        AIRLINES.put("CFE", "BA CityFlyer");
        AIRLINES.put("SHT", "BA Shuttle");
        AIRLINES.put("IBE", "Iberia");
        AIRLINES.put("IBS", "Iberia Express");
        AIRLINES.put("ANE", "Air Nostrum");
        AIRLINES.put("AEA", "Air Europa");
        AIRLINES.put("SWR", "SWISS");
        AIRLINES.put("EDW", "Edelweiss Air");
        AIRLINES.put("AUA", "Austrian Airlines");
        AIRLINES.put("BEL", "Brussels Airlines");
        AIRLINES.put("TAP", "TAP Air Portugal");
        AIRLINES.put("EIN", "Aer Lingus");
        AIRLINES.put("AEE", "Aegean Airlines");
        AIRLINES.put("OAL", "Olympic Air");
        AIRLINES.put("SEH", "Sky Express");
        AIRLINES.put("SAS", "SAS Scandinavian");
        AIRLINES.put("NAX", "Norwegian");
        AIRLINES.put("NOZ", "Norwegian");
        AIRLINES.put("FIN", "Finnair");
        AIRLINES.put("LOT", "LOT Polish Airlines");
        AIRLINES.put("CSA", "Czech Airlines");
        AIRLINES.put("TVS", "Smartwings");
        AIRLINES.put("CTN", "Croatia Airlines");
        AIRLINES.put("ASL", "Air Serbia");
        AIRLINES.put("ROT", "TAROM");
        AIRLINES.put("LZB", "Bulgaria Air");
        AIRLINES.put("KMM", "KM Malta Airlines");
        AIRLINES.put("AMC", "Air Malta");
        AIRLINES.put("LGL", "Luxair");
        AIRLINES.put("ICE", "Icelandair");
        AIRLINES.put("BTI", "airBaltic");
        AIRLINES.put("EXS", "Jet2");
        AIRLINES.put("TOM", "TUI Airways");
        AIRLINES.put("TUI", "TUIfly");
        AIRLINES.put("JAF", "TUI fly Belgium");

        // Turkey, Middle East, Africa, Americas & Asia
        AIRLINES.put("THY", "Turkish Airlines");
        AIRLINES.put("PGT", "Pegasus Airlines");
        AIRLINES.put("SXS", "SunExpress");
        AIRLINES.put("UAE", "Emirates");
        AIRLINES.put("QTR", "Qatar Airways");
        AIRLINES.put("ETD", "Etihad Airways");
        AIRLINES.put("SVA", "Saudia");
        AIRLINES.put("RJA", "Royal Jordanian");
        AIRLINES.put("ELY", "El Al");
        AIRLINES.put("MSR", "EgyptAir");
        AIRLINES.put("RAM", "Royal Air Maroc");
        AIRLINES.put("TAR", "Tunisair");
        AIRLINES.put("DAH", "Air Algérie");
        AIRLINES.put("ETH", "Ethiopian Airlines");
        AIRLINES.put("UAL", "United Airlines");
        AIRLINES.put("DAL", "Delta Air Lines");
        AIRLINES.put("AAL", "American Airlines");
        AIRLINES.put("ACA", "Air Canada");
        AIRLINES.put("SIA", "Singapore Airlines");
        AIRLINES.put("CPA", "Cathay Pacific");
        AIRLINES.put("CCA", "Air China");
        AIRLINES.put("CES", "China Eastern");
        AIRLINES.put("CSN", "China Southern");
        AIRLINES.put("ANA", "All Nippon Airways");
        AIRLINES.put("JAL", "Japan Airlines");
        AIRLINES.put("KAL", "Korean Air");
        AIRLINES.put("EVA", "EVA Air");

        // Cargo & Business Aviation
        AIRLINES.put("UPS", "UPS Cargo");
        AIRLINES.put("FDX", "FedEx Express");
        AIRLINES.put("BCS", "DHL (EAT Leipzig)");
        AIRLINES.put("DHK", "DHL Air UK");
        AIRLINES.put("CLX", "Cargolux");
        AIRLINES.put("GEC", "Lufthansa Cargo");
        AIRLINES.put("TAY", "ASL Airlines");
        AIRLINES.put("NJE", "NetJets Europe");
        AIRLINES.put("VJT", "VistaJet");
        AIRLINES.put("VJH", "VistaJet Germany");
        AIRLINES.put("EJA", "NetJets");
        AIRLINES.put("FXI", "Flexjet");
    }

    private static final class CountryRange {
        final int start;
        final int end;
        final String name;

        CountryRange(int start, int end, String name) {
            this.start = start;
            this.end = end;
            this.name = name;
        }
    }

    private static final CountryRange[] COUNTRY_RANGES = new CountryRange[]{
            new CountryRange(0x300000, 0x33FFFF, "Italy"),
            new CountryRange(0x340000, 0x37FFFF, "Spain"),
            new CountryRange(0x380000, 0x3BFFFF, "France"),
            new CountryRange(0x3C0000, 0x3FFFFF, "Germany"),
            new CountryRange(0x400000, 0x43FFFF, "United Kingdom"),
            new CountryRange(0x440000, 0x447FFF, "Austria"),
            new CountryRange(0x448000, 0x44FFFF, "Belgium"),
            new CountryRange(0x450000, 0x457FFF, "Bulgaria"),
            new CountryRange(0x458000, 0x45FFFF, "Denmark"),
            new CountryRange(0x460000, 0x467FFF, "Finland"),
            new CountryRange(0x468000, 0x46FFFF, "Greece"),
            new CountryRange(0x470000, 0x477FFF, "Hungary"),
            new CountryRange(0x478000, 0x47FFFF, "Norway"),
            new CountryRange(0x480000, 0x487FFF, "Netherlands"),
            new CountryRange(0x488000, 0x48FFFF, "Poland"),
            new CountryRange(0x490000, 0x497FFF, "Portugal"),
            new CountryRange(0x498000, 0x49FFFF, "Czechia"),
            new CountryRange(0x4A0000, 0x4A7FFF, "Romania"),
            new CountryRange(0x4A8000, 0x4AFFFF, "Sweden"),
            new CountryRange(0x4B0000, 0x4B7FFF, "Switzerland"),
            new CountryRange(0x4B8000, 0x4BFFFF, "Turkey"),
            new CountryRange(0x4C0000, 0x4C7FFF, "Serbia"),
            new CountryRange(0x4CA000, 0x4CAFFF, "Ireland"),
            new CountryRange(0x4CC000, 0x4CCFFF, "Iceland"),
            new CountryRange(0x4D0000, 0x4D03FF, "Luxembourg"),
            new CountryRange(0x4D2000, 0x4D2FFF, "Malta"),
            new CountryRange(0x501C00, 0x501FFF, "Croatia"),
            new CountryRange(0x502C00, 0x502FFF, "Latvia"),
            new CountryRange(0x505C00, 0x505FFF, "Slovakia"),
            new CountryRange(0x506C00, 0x506FFF, "Slovenia"),
            new CountryRange(0x010000, 0x017FFF, "Egypt"),
            new CountryRange(0x020000, 0x027FFF, "Morocco"),
            new CountryRange(0x028000, 0x02FFFF, "Tunisia"),
            new CountryRange(0x06A000, 0x06A3FF, "Qatar"),
            new CountryRange(0x710000, 0x717FFF, "Saudi Arabia"),
            new CountryRange(0x738000, 0x73FFFF, "Israel"),
            new CountryRange(0x768000, 0x76FFFF, "Singapore"),
            new CountryRange(0x780000, 0x7BFFFF, "China"),
            new CountryRange(0x840000, 0x87FFFF, "Japan"),
            new CountryRange(0x896000, 0x896FFF, "UAE"),
            new CountryRange(0xA00000, 0xAFFFFF, "United States"),
            new CountryRange(0xC00000, 0xC3FFFF, "Canada")
    };

    private static final class Place {
        final String name;
        final double lat;
        final double lon;

        Place(String name, double lat, double lon) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }

    // Major Italian cities/airports + neighboring European hubs (100% offline)
    private static final Place[] PLACES = new Place[]{
            // Lazio & Center
            new Place("Roma (FCO)", 41.8003, 12.2389),
            new Place("Roma (CIA)", 41.7994, 12.5949),
            new Place("Roma", 41.9028, 12.4964),
            new Place("Latina", 41.4676, 12.9036),
            new Place("Civitavecchia", 42.0937, 11.7969),
            new Place("Viterbo", 42.4207, 12.1077),
            new Place("Perugia (PEG)", 43.0959, 12.5132),
            new Place("Ancona (AOI)", 43.6163, 13.3623),
            new Place("Pescara (PSR)", 42.4317, 14.1811),
            new Place("L'Aquila", 42.3498, 13.3995),
            new Place("Firenze (FLR)", 43.8100, 11.2051),
            new Place("Pisa (PSA)", 43.6839, 10.3927),
            new Place("Grosseto", 42.7635, 11.1124),
            new Place("Siena", 43.3188, 11.3308),

            // North
            new Place("Milano Malpensa (MXP)", 45.6306, 8.7281),
            new Place("Milano Linate (LIN)", 45.4451, 9.2767),
            new Place("Bergamo (BGY)", 45.6739, 9.7042),
            new Place("Milano", 45.4642, 9.1900),
            new Place("Torino (TRN)", 45.2008, 7.6496),
            new Place("Genova (GOA)", 44.4133, 8.8375),
            new Place("Bologna (BLQ)", 44.5354, 11.2887),
            new Place("Rimini (RMI)", 44.0203, 12.6117),
            new Place("Parma (PMF)", 44.8245, 10.2964),
            new Place("Verona (VRN)", 45.3957, 10.8885),
            new Place("Brescia", 45.5416, 10.2118),
            new Place("Venezia (VCE)", 45.5053, 12.3519),
            new Place("Treviso (TSF)", 45.6484, 12.1944),
            new Place("Padova", 45.4064, 11.8768),
            new Place("Trieste (TRS)", 45.8275, 13.4722),
            new Place("Udine", 46.0711, 13.2346),
            new Place("Bolzano (BZO)", 46.4602, 11.3264),
            new Place("Trento", 46.0748, 11.1217),
            new Place("Aosta", 45.7349, 7.3131),

            // South & Islands
            new Place("Napoli (NAP)", 40.8860, 14.2908),
            new Place("Salerno (QSR)", 40.6204, 14.9113),
            new Place("Bari (BRI)", 41.1389, 16.7606),
            new Place("Brindisi (BDS)", 40.6576, 17.9470),
            new Place("Foggia", 41.4622, 15.5446),
            new Place("Taranto", 40.4644, 17.2470),
            new Place("Lecce", 40.3515, 18.1750),
            new Place("Potenza", 40.6404, 15.8056),
            new Place("Lamezia Terme (SUF)", 38.9054, 16.2423),
            new Place("Reggio Calabria (REG)", 38.0712, 15.6516),
            new Place("Catania (CTA)", 37.4668, 15.0664),
            new Place("Palermo (PMO)", 38.1760, 13.0910),
            new Place("Trapani (TPS)", 37.9114, 12.4880),
            new Place("Comiso (CIY)", 36.9946, 14.6072),
            new Place("Cagliari (CAG)", 39.2515, 9.0543),
            new Place("Olbia (OLB)", 40.8987, 9.5176),
            new Place("Alghero (AHO)", 40.6321, 8.2908),

            // Neighboring European Hubs
            new Place("Nice (NCE)", 43.6584, 7.2159),
            new Place("Marseille (MRS)", 43.4393, 5.2214),
            new Place("Ajaccio (AJA)", 41.9236, 8.8029),
            new Place("Bastia (BIA)", 42.5527, 9.4837),
            new Place("Zurich (ZRH)", 47.4647, 8.5492),
            new Place("Geneva (GVA)", 46.2381, 6.1089),
            new Place("Innsbruck (INN)", 47.2602, 11.3440),
            new Place("Munich (MUC)", 48.3537, 11.7750),
            new Place("Vienna (VIE)", 48.1103, 16.5697),
            new Place("Ljubljana (LJU)", 46.2237, 14.4576),
            new Place("Zagreb (ZAG)", 45.7429, 16.0688),
            new Place("Split (SPU)", 43.5389, 16.2980),
            new Place("Dubrovnik (DBV)", 42.5614, 18.2682),
            new Place("Tirana (TIA)", 41.4147, 19.7206),
            new Place("Malta (MLA)", 35.8575, 14.4775),
            new Place("Tunis (TUN)", 36.8510, 10.2272),
            new Place("Athens (ATH)", 37.9364, 23.9445)
    };

    private static final String[] COMPASS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private FlightEnricher() {}

    /**
     * Returns the airline/operator name for a callsign, or "" if unknown.
     */
    public static String lookupAirline(String callsign) {
        if (callsign == null) return "";
        String cs = callsign.trim().toUpperCase(Locale.US);
        if (cs.length() < 3) return "";

        // 3-letter ICAO airline prefix followed by digit/letter flight number (e.g. RYR99JG, DLA292)
        if (cs.length() >= 4 && Character.isLetter(cs.charAt(0))
                && Character.isLetter(cs.charAt(1)) && Character.isLetter(cs.charAt(2))) {
            String prefix = cs.substring(0, 3);
            String airline = AIRLINES.get(prefix);
            if (airline != null) {
                return airline;
            }
        }

        // Italian General Aviation registration (I-XXXX transmitted as 5 letters starting with I, e.g. IASAR)
        if (cs.length() == 5 && cs.charAt(0) == 'I' && isAllLetters(cs)) {
            return "General Aviation (IT)";
        }
        return "";
    }

    /**
     * Returns the registration country from a 6-hex ICAO address, or "" if unknown.
     */
    public static String lookupCountry(String icao) {
        if (!ModeSDecoder.isValidIcao(icao)) return "";
        try {
            int addr = Integer.parseInt(icao.trim(), 16);
            for (CountryRange r : COUNTRY_RANGES) {
                if (addr >= r.start && addr <= r.end) {
                    return r.name;
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    /**
     * Returns a human-readable offline location relative to the nearest known city/airport
     * (e.g. "Near Roma (FCO) · 12 km NE" or "Over Milano"), or "" if coords invalid / >250 km away.
     */
    public static String findNearestPlace(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon) || (lat == 0.0 && lon == 0.0)) {
            return "";
        }
        Place best = null;
        double bestDistKm = Double.MAX_VALUE;
        for (Place p : PLACES) {
            double d = haversineKm(lat, lon, p.lat, p.lon);
            if (d < bestDistKm) {
                bestDistKm = d;
                best = p;
            }
        }
        if (best == null || bestDistKm > 250.0) {
            return "";
        }
        if (bestDistKm < 5.0) {
            return "Over " + best.name;
        }
        String dir = bearingCompass(best.lat, best.lon, lat, lon);
        return String.format(Locale.getDefault(), "Near %s · %d km %s",
                best.name, Math.round(bestDistKm), dir);
    }

    private static boolean isAllLetters(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isLetter(s.charAt(i))) return false;
        }
        return true;
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    }

    private static String bearingCompass(double fromLat, double fromLon, double toLat, double toLon) {
        double lat1 = Math.toRadians(fromLat);
        double lat2 = Math.toRadians(toLat);
        double dLon = Math.toRadians(toLon - fromLon);
        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2)
                - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
        double deg = (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
        int idx = (int) Math.round(deg / 45.0) % 8;
        return COMPASS[idx];
    }
}
