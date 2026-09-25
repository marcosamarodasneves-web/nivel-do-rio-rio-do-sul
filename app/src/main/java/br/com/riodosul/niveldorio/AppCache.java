package br.com.riodosul.niveldorio;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppCache {
    private AppCache() {}

    public static void save(Context c, Snapshot s) {
        SharedPreferences.Editor e = AppPrefs.prefs(c).edit();
        e.putLong("cache_time", s.fetchedAt);
        for (Bridge b : Bridge.values()) {
            RiverReading r = s.rivers.get(b);
            if (r != null) {
                String prefix = "river_" + b.name() + "_";
                e.putString(prefix + "level", Double.toString(r.levelMeters));
                e.putString(prefix + "status", r.status == null ? "" : r.status);
                e.putString(prefix + "time", r.readingTime == null ? "" : r.readingTime);
            }
        }
        saveDam(e, "taio_", s.taio);
        saveDam(e, "itup_", s.ituporanga);
        e.apply();
    }

    private static void saveDam(SharedPreferences.Editor e, String p, DamReading d) {
        if (d == null) return;
        e.putString(p + "level", Double.toString(d.levelMeters));
        e.putString(p + "pct", Double.toString(d.capacityPercent));
        e.putInt(p + "open", d.gatesOpen);
        e.putInt(p + "total", d.gatesTotal);
        e.putString(p + "age", d.age == null ? "" : d.age);
        if (d.gateStates != null) {
            StringBuilder states = new StringBuilder();
            for (boolean open : d.gateStates) states.append(open ? '1' : '0');
            e.putString(p + "states", states.toString());
        }
    }

    public static Snapshot load(Context c) {
        SharedPreferences p = AppPrefs.prefs(c);
        long time = p.getLong("cache_time", 0L);
        if (time == 0L) return null;
        Snapshot s = new Snapshot();
        s.fetchedAt = time;
        s.fromCache = true;
        for (Bridge b : Bridge.values()) {
            String prefix = "river_" + b.name() + "_";
            String level = p.getString(prefix + "level", null);
            if (level != null) {
                try {
                    s.rivers.put(b, new RiverReading(b, Double.parseDouble(level),
                            p.getString(prefix + "status", ""), p.getString(prefix + "time", "")));
                } catch (Exception ignored) {}
            }
        }
        s.taio = loadDam(p, "taio_", "Taió");
        s.ituporanga = loadDam(p, "itup_", "Ituporanga");
        return s;
    }

    private static DamReading loadDam(SharedPreferences p, String prefix, String name) {
        String level = p.getString(prefix + "level", null);
        if (level == null) return null;
        try {
            return new DamReading(name,
                    Double.parseDouble(level),
                    Double.parseDouble(p.getString(prefix + "pct", "0")),
                    p.getInt(prefix + "open", 0),
                    p.getInt(prefix + "total", 0),
                    p.getString(prefix + "age", ""),
                    decodeStates(p.getString(prefix + "states", "")));
        } catch (Exception e) { return null; }
    }

    private static boolean[] decodeStates(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        boolean[] result = new boolean[raw.length()];
        for (int i = 0; i < raw.length(); i++) result[i] = raw.charAt(i) == '1';
        return result;
    }
}
