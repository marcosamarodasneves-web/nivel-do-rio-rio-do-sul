package br.com.riodosul.niveldorio;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Histórico coletado pelo próprio aparelho. Hoje é só reserva: o gráfico usa a série de 24 h da
 * Defesa Civil e recorre a esta apenas quando aquela consulta falha.
 */
public final class HistoryStore {
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final long MIN_SAMPLE_GAP = 4L * 60L * 1000L;

    private HistoryStore() {}

    private static String key(Bridge b) { return "history_" + b.name(); }

    public static synchronized void add(Context c, Bridge bridge, double value) {
        if (Double.isNaN(value) || value <= 0) return;
        long now = System.currentTimeMillis();
        List<Sample> list = read(c, bridge);
        if (!list.isEmpty() && now - list.get(list.size() - 1).timeMillis < MIN_SAMPLE_GAP) return;
        list.add(new Sample(now, value));
        save(c, bridge, list, now);
    }

    public static synchronized List<Sample> read(Context c, Bridge bridge) {
        SharedPreferences p = AppPrefs.prefs(c);
        String raw = p.getString(key(bridge), "");
        List<Sample> out = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - DAY_MS;
        if (raw == null || raw.isEmpty()) return out;
        String[] rows = raw.split(";");
        for (String row : rows) {
            String[] parts = row.split(",");
            if (parts.length != 2) continue;
            try {
                long t = Long.parseLong(parts[0]);
                double v = Double.parseDouble(parts[1]);
                if (t >= cutoff && v > 0) out.add(new Sample(t, v));
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static void save(Context c, Bridge bridge, List<Sample> list, long now) {
        long cutoff = now - DAY_MS;
        StringBuilder sb = new StringBuilder();
        for (Sample s : list) {
            if (s.timeMillis < cutoff) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(s.timeMillis).append(',').append(String.format(Locale.US, "%.3f", s.value));
        }
        AppPrefs.prefs(c).edit().putString(key(bridge), sb.toString()).apply();
    }

    /** Tendência da última hora a partir de uma série (a do gráfico). */
    public static String trend(List<Sample> list) {
        if (list == null || list.size() < 2) return "→ Estável";
        Sample last = list.get(list.size() - 1);
        Sample ref = list.get(0);
        for (int i = list.size() - 1; i >= 0; i--) {
            ref = list.get(i);
            if (last.timeMillis - ref.timeMillis >= 55L * 60L * 1000L) break;
        }
        double delta = last.value - ref.value;
        if (delta > 0.02) return "↗ Subindo";
        if (delta < -0.02) return "↘ Descendo";
        return "→ Estável";
    }
}
