package br.com.riodosul.niveldorio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Guarda a última consulta completa (níveis, cotas, histórico, barragens e previsão), para a tela
 * abrir na hora e continuar funcionando sem rede.
 */
public final class AppCache {
    private static final String KEY = "snapshot_v2";
    /** Última consulta em memória: trocar de ponte não precisa reler o armazenamento. */
    private static Snapshot memory;

    private AppCache() {}

    public static synchronized void save(Context c, Snapshot s) {
        memory = s;
        try {
            AppPrefs.prefs(c).edit().putString(KEY, toJson(s).toString()).apply();
        } catch (Exception ignored) {
            // Cache é conveniência: uma falha aqui não pode derrubar a atualização.
        }
    }

    public static synchronized Snapshot load(Context c) {
        if (memory != null) return memory;
        SharedPreferences p = AppPrefs.prefs(c);
        String raw = p.getString(KEY, null);
        if (raw == null || raw.isEmpty()) return null;
        try {
            Snapshot s = fromJson(new JSONObject(raw));
            s.fromCache = true;
            // O cache geral não guarda histórico de previsões: apenas reidrata o último JSON
            // centralizado, explicitamente marcado como resposta salva/offline.
            HydrologyForecastClient.Response forecasts = HydrologyForecastClient.loadCached(c);
            if (forecasts != null) HydrologyForecastClient.applyToSnapshot(s, forecasts, true);
            memory = s;
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- gravação

    private static JSONObject toJson(Snapshot s) throws Exception {
        JSONObject root = new JSONObject();
        root.put("time", s.fetchedAt);
        JSONObject rivers = new JSONObject();
        for (Bridge b : Bridge.values()) {
            RiverReading r = s.rivers.get(b);
            JSONObject o = new JSONObject();
            if (r != null) {
                o.put("level", r.levelMeters);
                o.put("status", r.status == null ? "" : r.status);
                o.put("time", r.readingTime == null ? "" : r.readingTime);
                o.put("millis", r.readingMillis);
                o.put("station", r.stationId == null ? "" : r.stationId);
                o.put("cotas", cotas(r.cotas));
            }
            List<Sample> history = s.riverHistory.get(b);
            if (history != null) {
                JSONArray h = new JSONArray();
                for (Sample x : history) {
                    h.put(x.timeMillis);
                    h.put(Math.round(x.value * 1000.0) / 1000.0);
                }
                o.put("history", h);
            }
            rivers.put(b.name(), o);
        }
        root.put("rivers", rivers);
        if (!s.municipalRain.isEmpty()) {
            JSONArray cities = new JSONArray();
            for (MunicipalRain m : s.municipalRain) {
                JSONObject c = new JSONObject();
                c.put("city", m.city);
                putNumber(c, "mm24", m.mm24);
                putNumber(c, "mm1", m.mm1h);
                c.put("g", m.gauges);
                c.put("sg", m.silentGauges);
                c.put("src", m.source == null ? "" : m.source);
                putNumber(c, "lat", m.latitude);
                putNumber(c, "lon", m.longitude);
                cities.put(c);
            }
            root.put("cities", cities);
        }
        if (s.taio != null) root.put("taio", dam(s.taio));
        if (s.ituporanga != null) root.put("ituporanga", dam(s.ituporanga));
        return root;
    }

    private static JSONObject cotas(Cotas c) throws Exception {
        JSONObject o = new JSONObject();
        o.put("at", c.atencao);
        o.put("al", c.alerta);
        o.put("em", c.emergencia);
        o.put("of", c.oficial);
        return o;
    }

    private static JSONObject dam(DamReading d) throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", d.name);
        o.put("level", d.levelMeters);
        o.put("pct", d.capacityPercent);
        o.put("open", d.gatesOpen);
        o.put("total", d.gatesTotal);
        o.put("age", d.age == null ? "" : d.age);
        if (d.gateStates != null) {
            StringBuilder states = new StringBuilder();
            for (boolean open : d.gateStates) states.append(open ? '1' : '0');
            o.put("states", states.toString());
        }
        putNumber(o, "chg", d.levelChange3hCm);
        o.put("op24", d.gatesOpened24h);
        o.put("cl24", d.gatesClosed24h);
        o.put("opArr", d.gatesOpenedArriving);
        o.put("clArr", d.gatesClosedArriving);
        putNumber(o, "ev", d.hoursSinceLastGateEvent);
        o.put("sid", d.stationId == null ? "" : d.stationId);
        putNumber(o, "lat", d.latitude);
        putNumber(o, "lon", d.longitude);
        putNumber(o, "rain24", d.rain24hMm);
        o.put("rgauge", d.rainFromGauge);
        if (!d.history.isEmpty()) {
            JSONArray hist = new JSONArray();
            for (DamPoint q : d.history) {
                hist.put(q.timeMillis);
                putOrNaN(hist, q.levelMeters);
                putOrNaN(hist, q.capacityPercent);
                hist.put(q.gatesOpen);
                hist.put(q.gatesTotal);
            }
            o.put("hist", hist);
        }
        if (!d.rainHourly.isEmpty()) {
            JSONArray rain = new JSONArray();
            for (Sample x : d.rainHourly) {
                rain.put(x.timeMillis);
                putOrNaN(rain, x.value);
            }
            o.put("rain", rain);
        }
        return o;
    }

    /** JSON não aceita NaN: valores desconhecidos simplesmente não são gravados. */
    private static void putNumber(JSONObject o, String key, double v) throws Exception {
        if (!Double.isNaN(v) && !Double.isInfinite(v)) o.put(key, v);
    }

    /** Em arrays, o desconhecido vira null para manter as posições da série. */
    private static void putOrNaN(JSONArray arr, double v) throws Exception {
        if (Double.isNaN(v) || Double.isInfinite(v)) arr.put(JSONObject.NULL);
        else arr.put(Math.round(v * 10000.0) / 10000.0);
    }

    // ---------------------------------------------------------------- leitura

    private static Snapshot fromJson(JSONObject root) {
        Snapshot s = new Snapshot();
        s.fetchedAt = root.optLong("time", 0L);
        JSONObject rivers = root.optJSONObject("rivers");
        for (Bridge b : Bridge.values()) {
            JSONObject o = rivers == null ? null : rivers.optJSONObject(b.name());
            if (o == null) continue;
            if (o.has("level")) {
                RiverReading r = new RiverReading(b, o.optDouble("level", 0), o.optString("status", ""), o.optString("time", ""));
                r.readingMillis = o.optLong("millis", 0L);
                r.stationId = o.optString("station", "");
                r.cotas = cotas(o.optJSONObject("cotas"));
                s.rivers.put(b, r);
            }
            JSONArray h = o.optJSONArray("history");
            if (h != null) {
                List<Sample> list = new ArrayList<>();
                for (int i = 0; i + 1 < h.length(); i += 2) {
                    list.add(new Sample(h.optLong(i, 0L), h.optDouble(i + 1, 0)));
                }
                s.riverHistory.put(b, list);
            }
        }
        s.taio = dam(root.optJSONObject("taio"), "Taió");
        s.ituporanga = dam(root.optJSONObject("ituporanga"), "Ituporanga");
        JSONArray cities = root.optJSONArray("cities");
        if (cities != null) {
            for (int i = 0; i < cities.length(); i++) {
                JSONObject c = cities.optJSONObject(i);
                if (c == null) continue;
                MunicipalRain m = new MunicipalRain(c.optString("city", ""));
                m.mm24 = c.optDouble("mm24", Double.NaN);
                m.mm1h = c.optDouble("mm1", Double.NaN);
                m.gauges = c.optInt("g", 0);
                m.silentGauges = c.optInt("sg", 0);
                m.source = c.optString("src", "");
                m.latitude = c.optDouble("lat", Double.NaN);
                m.longitude = c.optDouble("lon", Double.NaN);
                s.municipalRain.add(m);
            }
        }
        return s;
    }

    private static Cotas cotas(JSONObject o) {
        if (o == null) return new Cotas();
        Cotas c = new Cotas(o.optDouble("at", Cotas.PADRAO_ATENCAO), o.optDouble("al", Cotas.PADRAO_ALERTA),
                o.optDouble("em", Cotas.PADRAO_EMERGENCIA), o.optBoolean("of", false));
        return c.valida() ? c : new Cotas();
    }

    private static DamReading dam(JSONObject o, String fallbackName) {
        if (o == null) return null;
        String raw = o.optString("states", "");
        boolean[] states = null;
        if (!raw.isEmpty()) {
            states = new boolean[raw.length()];
            for (int i = 0; i < raw.length(); i++) states[i] = raw.charAt(i) == '1';
        }
        DamReading d = new DamReading(o.optString("name", fallbackName), o.optDouble("level", 0), o.optDouble("pct", 0),
                o.optInt("open", 0), o.optInt("total", 0), o.optString("age", ""), states);
        d.levelChange3hCm = o.optDouble("chg", Double.NaN);
        d.gatesOpened24h = o.optInt("op24", 0);
        d.gatesClosed24h = o.optInt("cl24", 0);
        d.gatesOpenedArriving = o.optInt("opArr", 0);
        d.gatesClosedArriving = o.optInt("clArr", 0);
        d.hoursSinceLastGateEvent = o.optDouble("ev", Double.NaN);
        d.stationId = o.optString("sid", "");
        d.latitude = o.optDouble("lat", Double.NaN);
        d.longitude = o.optDouble("lon", Double.NaN);
        d.rain24hMm = o.optDouble("rain24", Double.NaN);
        d.rainFromGauge = o.optBoolean("rgauge", false);
        JSONArray hist = o.optJSONArray("hist");
        if (hist != null) {
            for (int i = 0; i + 4 < hist.length(); i += 5) {
                long t = hist.optLong(i, 0L);
                if (t <= 0) continue;
                d.history.add(new DamPoint(t, hist.optDouble(i + 1, Double.NaN),
                        hist.optDouble(i + 2, Double.NaN), hist.optInt(i + 3, 0), hist.optInt(i + 4, 0)));
            }
        }
        JSONArray rain = o.optJSONArray("rain");
        if (rain != null) {
            for (int i = 0; i + 1 < rain.length(); i += 2) {
                long t = rain.optLong(i, 0L);
                if (t <= 0) continue;
                d.rainHourly.add(new Sample(t, rain.optDouble(i + 1, 0)));
            }
        }
        return d;
    }


}
