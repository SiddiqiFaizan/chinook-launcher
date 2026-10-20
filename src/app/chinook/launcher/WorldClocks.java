package app.chinook.launcher;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Helpers for the world-clock row: zone list, display names and day offsets. */
final class WorldClocks {
    private WorldClocks() {}

    static String city(String zoneId) {
        int i = zoneId.lastIndexOf('/');
        return zoneId.substring(i + 1).replace('_', ' ');
    }

    static String region(String zoneId) {
        int i = zoneId.indexOf('/');
        return i > 0 ? zoneId.substring(0, i).replace('_', ' ') : "";
    }

    static String gmtOffset(String zoneId) {
        int mins = TimeZone.getTimeZone(zoneId).getOffset(System.currentTimeMillis()) / 60000;
        String sign = mins < 0 ? "−" : "+";
        mins = Math.abs(mins);
        return mins % 60 == 0
                ? String.format(Locale.US, "GMT%s%d", sign, mins / 60)
                : String.format(Locale.US, "GMT%s%d:%02d", sign, mins / 60, mins % 60);
    }

    /** -1, 0 or +1: the zone's calendar day relative to the local day. */
    static int dayOffset(String zoneId) {
        long now = System.currentTimeMillis();
        Calendar local = Calendar.getInstance();
        local.setTimeInMillis(now);
        Calendar there = Calendar.getInstance(TimeZone.getTimeZone(zoneId));
        there.setTimeInMillis(now);
        int a = local.get(Calendar.YEAR) * 1000 + local.get(Calendar.DAY_OF_YEAR);
        int b = there.get(Calendar.YEAR) * 1000 + there.get(Calendar.DAY_OF_YEAR);
        return Integer.signum(b - a);
    }

    /** City-style zone ids ("Area/City"), sorted by city name. */
    static List<String> allZones() {
        List<String> zones = new ArrayList<>();
        for (String id : TimeZone.getAvailableIDs()) {
            if (!id.contains("/")) continue;
            if (id.startsWith("Etc/") || id.startsWith("SystemV/") || id.startsWith("US/")
                    || id.startsWith("Canada/") || id.startsWith("Brazil/") || id.startsWith("Mexico/")
                    || id.startsWith("Chile/")) continue;
            zones.add(id);
        }
        Collections.sort(zones, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                return city(a).compareToIgnoreCase(city(b));
            }
        });
        return zones;
    }
}
