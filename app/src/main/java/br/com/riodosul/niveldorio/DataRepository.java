package br.com.riodosul.niveldorio;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.TimeZone;

public final class DataRepository {
    private static final String CITY_ID = "4214805";
    private static final String API_BASE = "https://public.asthon.com.br";

    private static final String PANEL_URL =
            API_BASE + "/public/panel?city_id=" + CITY_ID + "&include_geometry=false";
    private static final String LIVE_URL =
            API_BASE + "/public/stations/live?city_id=" + CITY_ID + "&_v=2";
    private static final String DAMS_URL =
            API_BASE + "/public/dams?city_id=" + CITY_ID;

    private DataRepository() {}

    public static Snapshot fetch(Context context) throws Exception {
        Snapshot s = new Snapshot();

        // Fonte principal: painel JSON usado pelo portal atual.
        Object panelRoot = loadJson(PANEL_URL + "&_t=" + System.currentTimeMillis());
        fillRiversFromJson(panelRoot, s);

        // Fallback: endpoint "live", já observado retornando level_m por station_id.
        if (s.rivers.size() < Bridge.values().length) {
            try {
                Object liveRoot = loadJson(LIVE_URL + "&_t=" + System.currentTimeMillis());
                fillRiversFromJson(liveRoot, s);
            } catch (Exception ignored) {
                // Mantemos os dados já obtidos do painel.
            }
        }

        if (s.rivers.isEmpty()) {
            throw new IllegalStateException("API respondeu, mas nenhuma estação conhecida foi encontrada.");
        }

        // Barragens em endpoint JSON separado.
        try {
            Object damsRoot = loadJson(DAMS_URL + "&_t=" + System.currentTimeMillis());
            s.taio = parseDam(findObjectByName(damsRoot,
                    "Barragem Oeste Taió", "Barragem Oeste", "Taió"),
                    "Taió", 7);
            s.ituporanga = parseDam(findObjectByName(damsRoot,
                    "Barragem Sul Ituporanga", "Barragem Sul", "Ituporanga"),
                    "Ituporanga", 5);
        } catch (Exception ignored) {
            // Não descartamos nível dos rios caso apenas a consulta de barragens falhe.
        }

        s.fetchedAt = System.currentTimeMillis();
        s.fromCache = false;
        s.errorMessage = null;

        for (RiverReading r : s.rivers.values()) {
            HistoryStore.add(context, r.bridge, r.levelMeters);
        }

        AppCache.save(context, s);
        return s;
    }

    public static Snapshot fetchOrCache(Context context) {
        try {
            return fetch(context);
        } catch (Exception e) {
            String error = friendlyError(e);
            Snapshot cached = AppCache.load(context);
            if (cached != null) {
                cached.fromCache = true;
                cached.errorMessage = error;
                return cached;
            }

            Snapshot empty = new Snapshot();
            empty.errorMessage = error;
            return empty;
        }
    }

    private static void fillRiversFromJson(Object root, Snapshot s) {
        for (Bridge b : Bridge.values()) {
            if (s.rivers.containsKey(b)) continue;

            JSONObject station = findObjectById(root, b.stationId);
            if (station == null) continue;

            Double level = firstDouble(station,
                    "level_m", "level", "water_level_m", "river_level_m");
            if (level == null || level <= 0) continue;

            String readingAt = firstString(station,
                    "last_reading_at", "reading_at", "last_reading",
                    "updated_at", "timestamp", "datetime");

            String status = firstString(station, "status", "alert_status", "level_status");
            if (status == null || status.trim().isEmpty()) {
                status = statusFromLevel(level);
            }

            s.rivers.put(b, new RiverReading(
                    b,
                    level,
                    normalizeStatus(status),
                    formatReadingTime(readingAt)
            ));
        }
    }

    private static DamReading parseDam(JSONObject dam, String name, int defaultTotalGates) {
        if (dam == null) return null;

        Double level = firstDouble(dam,
                "level_m", "level", "water_level_m", "upstream_level_m",
                "upstream_level", "montante_m", "montante");

        Double pct = firstDouble(dam,
                "percent_use", "capacidade_atual",
                "capacity_percent", "capacity_percentage", "percentage",
                "percent", "usage_percent", "utilization_percent",
                "occupancy_percent");

        Integer open = firstInt(dam,
                "comportas_abertas", "gates_open", "open_gates", "open_count",
                "opened_gates", "gates_open_count");

        Integer total = firstInt(dam,
                "comportas_total", "gates_total", "total_gates", "gate_count", "gates_count");

        int[] gateCounts = countGatesFromArray(dam);
        boolean[] gateStates = gateStatesFromArray(dam);
        if (open == null && gateCounts[0] >= 0) open = gateCounts[0];
        if (total == null && gateCounts[1] > 0) total = gateCounts[1];

        if (total == null || total <= 0) total = defaultTotalGates;
        if (open == null || open < 0) open = 0;

        String readingAt = firstString(dam,
                "last_reading_at", "reading_at", "updated_at",
                "timestamp", "datetime");

        // Se o endpoint mudar algum nome de campo, preferimos ainda retornar
        // uma barragem parcial em vez de descartar tudo.
        double safeLevel = level == null ? 0.0 : level;
        double safePct = pct == null ? 0.0 : pct;

        return new DamReading(
                name,
                safeLevel,
                safePct,
                Math.min(open, total),
                total,
                formatReadingTime(readingAt),
                gateStates
        );
    }

    private static int[] countGatesFromArray(JSONObject dam) {
        String[] keys = { "gates", "sluice_gates", "floodgates", "comportas" };
        for (String key : keys) {
            JSONArray arr = dam.optJSONArray(key);
            if (arr == null) continue;

            int open = 0;
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.opt(i);
                if (item instanceof JSONObject) {
                    JSONObject g = (JSONObject) item;
                    if (isGateOpen(g)) open++;
                } else if (item instanceof Boolean && (Boolean) item) {
                    open++;
                }
            }
            return new int[] { open, arr.length() };
        }
        return new int[] { -1, -1 };
    }

    private static boolean isGateOpen(JSONObject g) {
        if (g.has("open")) return g.optBoolean("open", false);
        if (g.has("is_open")) return g.optBoolean("is_open", false);
        if (g.has("opened")) return g.optBoolean("opened", false);
        if (g.has("aberta")) return g.optBoolean("aberta", false);

        String status = firstString(g, "status", "state", "situation");
        if (status == null) return false;

        String x = status.toLowerCase(Locale.ROOT);
        return x.contains("open") || x.contains("abert");
    }

    private static boolean[] gateStatesFromArray(JSONObject dam) {
        String[] keys = { "gates", "sluice_gates", "floodgates", "comportas" };
        for (String key : keys) {
            JSONArray arr = dam.optJSONArray(key);
            if (arr == null) continue;
            boolean[] states = new boolean[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.opt(i);
                if (item instanceof JSONObject) {
                    states[i] = isGateOpen((JSONObject) item);
                } else if (item instanceof Boolean) {
                    states[i] = (Boolean) item;
                }
            }
            return states;
        }
        return null;
    }

    private static Object loadJson(String url) throws Exception {
        String body = httpGet(url);
        Object root = new JSONTokener(body).nextValue();
        if (!(root instanceof JSONObject) && !(root instanceof JSONArray)) {
            throw new IllegalStateException("Resposta da API não é JSON.");
        }
        return root;
    }

    private static JSONObject findObjectById(Object node, String wantedId) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;

            String[] idKeys = { "station_id", "id", "uuid", "stationId" };
            for (String key : idKeys) {
                String value = o.optString(key, "");
                if (wantedId.equalsIgnoreCase(value)) return o;
            }

            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object child = o.opt(key);
                JSONObject found = findObjectById(child, wantedId);
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                JSONObject found = findObjectById(a.opt(i), wantedId);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JSONObject findObjectByName(Object node, String... wantedNames) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String[] nameKeys = { "name", "station_name", "dam_name", "title", "description" };

            for (String key : nameKeys) {
                String value = normalize(o.optString(key, ""));
                if (value.isEmpty()) continue;

                for (String wanted : wantedNames) {
                    String target = normalize(wanted);
                    if (value.equals(target) || value.contains(target) || target.contains(value)) {
                        return o;
                    }
                }
            }

            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                Object child = o.opt(keys.next());
                JSONObject found = findObjectByName(child, wantedNames);
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                JSONObject found = findObjectByName(a.opt(i), wantedNames);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Double firstDouble(JSONObject o, String... keys) {
        for (String key : keys) {
            if (!o.has(key) || o.isNull(key)) continue;

            Object v = o.opt(key);
            Double parsed = toDouble(v);
            if (parsed != null) return parsed;
        }

        // Alguns serviços agrupam a leitura atual dentro de um objeto.
        String[] containers = { "latest", "reading", "current", "data", "measurements" };
        for (String container : containers) {
            JSONObject child = o.optJSONObject(container);
            if (child == null) continue;
            Double value = firstDoubleDirect(child, keys);
            if (value != null) return value;
        }
        return null;
    }

    private static Double firstDoubleDirect(JSONObject o, String... keys) {
        for (String key : keys) {
            if (!o.has(key) || o.isNull(key)) continue;
            Double value = toDouble(o.opt(key));
            if (value != null) return value;
        }
        return null;
    }

    private static Integer firstInt(JSONObject o, String... keys) {
        Double d = firstDouble(o, keys);
        return d == null ? null : (int) Math.round(d);
    }

    private static Double toDouble(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();

        try {
            String s = String.valueOf(value).trim();
            if (s.isEmpty()) return null;
            s = s.replace("%", "").replace(" m", "").trim();
            if (s.contains(",")) s = s.replace(".", "").replace(',', '.');
            return Double.parseDouble(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String firstString(JSONObject o, String... keys) {
        for (String key : keys) {
            if (!o.has(key) || o.isNull(key)) continue;
            String s = o.optString(key, "").trim();
            if (!s.isEmpty()) return s;
        }

        String[] containers = { "latest", "reading", "current", "data", "measurements" };
        for (String container : containers) {
            JSONObject child = o.optJSONObject(container);
            if (child == null) continue;
            for (String key : keys) {
                String s = child.optString(key, "").trim();
                if (!s.isEmpty()) return s;
            }
        }
        return null;
    }

    private static String formatReadingTime(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "horário não informado";
        String s = raw.trim();

        String[] utcPatterns = {
                "yyyy-MM-dd'T'HH:mm:ss.SSSX",
                "yyyy-MM-dd'T'HH:mm:ssX",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'"
        };

        for (String p : utcPatterns) {
            try {
                SimpleDateFormat parser = new SimpleDateFormat(p, Locale.US);
                parser.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = parser.parse(s);
                if (d != null) {
                    return new SimpleDateFormat("dd/MM HH:mm", new Locale("pt", "BR")).format(d);
                }
            } catch (Exception ignored) {}
        }

        // Caso a API já envie uma descrição ou horário local legível.
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    private static String normalizeStatus(String status) {
        String s = status == null ? "" : status.trim();
        String low = s.toLowerCase(Locale.ROOT);

        if (low.contains("normal")) return "Normal";
        if (low.contains("aten")) return "Atenção";
        if (low.contains("alert")) return "Alerta";
        if (low.contains("emerg")) return "Emergência";
        return s.isEmpty() ? "Normal" : s;
    }

    private static String statusFromLevel(double level) {
        if (level < 4.50) return "Normal";
        if (level < 5.50) return "Atenção";
        if (level < 6.50) return "Alerta";
        return "Emergência";
    }

    private static String httpGet(String urlString) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlString).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        c.setRequestMethod("GET");
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/153.0 Mobile Safari/537.36");
        c.setRequestProperty("Accept", "application/json,text/plain,*/*");
        c.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.6");
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("Pragma", "no-cache");
        c.setRequestProperty("Connection", "close");

        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            c.disconnect();
            throw new IllegalStateException("HTTP " + code);
        }

        BufferedReader br = new BufferedReader(
                new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = br.read(buf)) >= 0) {
            sb.append(buf, 0, n);
        }
        br.close();
        c.disconnect();

        String body = sb.toString().trim();
        if (body.isEmpty()) throw new IllegalStateException("Resposta vazia da API.");
        return body;
    }

    private static String friendlyError(Exception e) {
        String type = e.getClass().getSimpleName();
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) return type;

        msg = msg.replace('\n', ' ').replace('\r', ' ').trim();
        if (msg.length() > 90) msg = msg.substring(0, 90);
        return type + ": " + msg;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replace("á", "a")
                .replace("à", "a")
                .replace("â", "a")
                .replace("ã", "a")
                .replace("é", "e")
                .replace("ê", "e")
                .replace("í", "i")
                .replace("ó", "o")
                .replace("ô", "o")
                .replace("õ", "o")
                .replace("ú", "u")
                .replace("ç", "c")
                .replace("-", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
