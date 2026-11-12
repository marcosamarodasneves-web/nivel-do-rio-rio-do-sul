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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Leitura dos dados públicos da Defesa Civil de Rio do Sul (mesma API usada pelo portal
 * https://defesacivil.riodosul.sc.gov.br/) e da previsão horária de chuva do Open-Meteo.
 */
public final class DataRepository {
    private static final String CITY_ID = "4214805";
    private static final String API_BASE = "https://public.asthon.com.br";

    private static final String PANEL_URL =
            API_BASE + "/public/panel?city_id=" + CITY_ID + "&include_geometry=false";
    private static final String LIVE_URL =
            API_BASE + "/public/stations/live?city_id=" + CITY_ID + "&_v=2";
    private static final String DAMS_URL =
            API_BASE + "/public/dams?city_id=" + CITY_ID;
    private static final String BULLETIN_URL =
            API_BASE + "/public/cities/" + CITY_ID + "/forecast/bulletin";

    /** Pontos da bacia: Rio do Sul, Taió, Ituporanga, Pouso Redondo e Alfredo Wagner. */
    private static final String WEATHER_URL =
            "https://api.open-meteo.com/v1/forecast"
                    + "?latitude=-27.2142,-27.1164,-27.4144,-27.2581,-27.7003"
                    + "&longitude=-49.6431,-49.9989,-49.6017,-49.9342,-49.3336"
                    + "&hourly=precipitation,precipitation_probability"
                    + "&past_hours=1&forecast_hours=5&timeformat=unixtime&timezone=GMT";

    /** Estações a montante usadas só na previsão (nome sem acento, como vem no painel). */
    private static final String UP_LAURENTINO = "laurentino";
    private static final String UP_AGRONOMICA = "agronomica";

    private static final long HOUR = 3600000L;
    private static final Locale BR = new Locale("pt", "BR");

    private DataRepository() {}

    public static Snapshot fetchOrCache(Context context) {
        try {
            return fetch(context);
        } catch (Exception e) {
            String error = friendlyError(e);
            Snapshot cached = AppCache.load(context);
            if (cached != null) {
                cached.fromCache = true;
                cached.errorMessage = error;
                // Mesmo sem rede dá para conferir previsões de horas atrás com o histórico guardado.
                try {
                    ForecastStore.recordAndResolve(context, cached);
                } catch (Exception ignored) {}
                return cached;
            }
            Snapshot empty = new Snapshot();
            empty.errorMessage = error;
            return empty;
        }
    }

    public static Snapshot fetch(Context context) throws Exception {
        final long now = System.currentTimeMillis();
        final Snapshot s = new Snapshot();

        // 1) Painel: nível atual, cotas e chuva de todas as estações da cidade.
        JSONArray stations = null;
        Exception panelError = null;
        try {
            Object panelRoot = loadJson(PANEL_URL + "&_t=" + now);
            stations = stationsOf(panelRoot);
            fillRivers(stations, s);
        } catch (Exception e) {
            panelError = e;
        }

        // Reserva: endpoint "live" (não traz nome, só id).
        if (s.rivers.size() < Bridge.values().length) {
            try {
                fillRivers(stationsOf(loadJson(LIVE_URL + "&_t=" + now)), s);
            } catch (Exception ignored) {
                // Mantemos o que o painel já entregou.
            }
        }
        if (s.rivers.isEmpty()) {
            if (panelError != null) throw panelError;
            throw new IllegalStateException("API respondeu, mas nenhuma das pontes foi encontrada.");
        }

        // 2) Consultas complementares em paralelo. Nenhuma delas derruba a leitura principal.
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            final String start = isoUtc(now - 24 * HOUR), end = isoUtc(now);
            final String startShort = isoUtc(now - 4 * HOUR);

            List<Future<List<Sample>>> histories = new ArrayList<>();
            for (final Bridge b : Bridge.values()) {
                final RiverReading r = s.rivers.get(b);
                histories.add(r == null || r.stationId == null ? null : pool.submit(new Callable<List<Sample>>() {
                    @Override public List<Sample> call() throws Exception {
                        return loadHistory(r.stationId, start, end);
                    }
                }));
            }
            final String laurentinoId = stationIdByName(stations, UP_LAURENTINO);
            final String agronomicaId = stationIdByName(stations, UP_AGRONOMICA);
            Future<List<Sample>> laurentino = laurentinoId == null ? null : pool.submit(new Callable<List<Sample>>() {
                @Override public List<Sample> call() throws Exception { return loadHistory(laurentinoId, startShort, end); }
            });
            Future<List<Sample>> agronomica = agronomicaId == null ? null : pool.submit(new Callable<List<Sample>>() {
                @Override public List<Sample> call() throws Exception { return loadHistory(agronomicaId, startShort, end); }
            });
            Future<Object> dams = pool.submit(new Callable<Object>() {
                @Override public Object call() throws Exception { return loadJson(DAMS_URL + "&_t=" + now); }
            });
            Future<Object> bulletin = pool.submit(new Callable<Object>() {
                @Override public Object call() throws Exception { return loadJson(BULLETIN_URL); }
            });
            Future<Object> weather = pool.submit(new Callable<Object>() {
                @Override public Object call() throws Exception { return loadJson(WEATHER_URL); }
            });
            Future<Object> stationList = pool.submit(new Callable<Object>() {
                @Override public Object call() throws Exception { return loadJson(STATIONS_LIST_URL); }
            });

            // Histórico de 24 h de cada ponte (mesma série do gráfico do portal).
            Bridge[] bridges = Bridge.values();
            EnumHistories full = new EnumHistories();
            for (int i = 0; i < bridges.length; i++) {
                List<Sample> list = await(histories.get(i));
                RiverReading r = s.rivers.get(bridges[i]);
                if (list == null || list.size() < 2) {
                    // Sem histórico remoto: usa o que o próprio aparelho coletou.
                    list = HistoryStore.read(context, bridges[i]);
                }
                if (r != null) appendCurrent(list, r, now);
                full.put(bridges[i], list);
                s.riverHistory.put(bridges[i], thin(list, 5 * 60000L));
            }

            // Barragens e manobras de comportas.
            try {
                Object damsRoot = dams.get(25, TimeUnit.SECONDS);
                JSONObject taio = findObjectByName(damsRoot, "Barragem Oeste Taió", "Barragem Oeste", "Taió");
                JSONObject itup = findObjectByName(damsRoot, "Barragem Sul Ituporanga", "Barragem Sul", "Ituporanga");
                s.taio = parseDam(taio, "Taió", 7);
                s.ituporanga = parseDam(itup, "Ituporanga", 5);
                final String taioId = taio == null ? null : taio.optString("station_id", null);
                final String itupId = itup == null ? null : itup.optString("station_id", null);
                Future<Object> taioGates = gateHistory(pool, taioId);
                Future<Object> itupGates = gateHistory(pool, itupId);
                applyGateHistory(s.taio, await(taioGates), now);
                applyGateHistory(s.ituporanga, await(itupGates), now);
                // Chuva das últimas 24 h sobre o ponto de cada barragem (Open-Meteo, hora a hora).
                final String damRainUrl = damRainUrl(taio, itup);
                Future<Object> damRain = damRainUrl == null ? null : pool.submit(new Callable<Object>() {
                    @Override public Object call() throws Exception { return loadJson(damRainUrl); }
                });
                applyDamRain(s.taio, s.ituporanga, await(damRain), now);
                // Preferência: o pluviômetro da própria barragem (medição a cada 10 min da Defesa Civil).
                Future<List<Sample>> taioRain = rainHistory(pool, taioId, start, end);
                Future<List<Sample>> itupRain = rainHistory(pool, itupId, start, end);
                applyGaugeRain(s.taio, await(taioRain), now);
                applyGaugeRain(s.ituporanga, await(itupRain), now);
            } catch (Exception ignored) {
                // Não descartamos o nível dos rios caso apenas a consulta de barragens falhe.
            }

            // Chuva das últimas 24 h por município (pluviômetros; estimativa do modelo onde estão calados).
            try {
                Object listRoot = await(stationList);
                List<MunicipalRain> cities = municipalRainFromGauges(stations,
                        listRoot instanceof JSONArray ? (JSONArray) listRoot : stationsOf(listRoot), now);
                final String estUrl = estimateUrl(cities);
                if (estUrl != null) {
                    Future<Object> est = pool.submit(new Callable<Object>() {
                        @Override public Object call() throws Exception { return loadJson(estUrl); }
                    });
                    applyEstimates(cities, await(est), now);
                }
                // Medidos primeiro (maior chuva no topo), depois as estimativas e por fim quem está sem dado.
                Collections.sort(cities, new Comparator<MunicipalRain>() {
                    @Override public int compare(MunicipalRain a, MunicipalRain b) {
                        int ra = a.measured() ? 0 : a.hasValue() ? 1 : 2, rb = b.measured() ? 0 : b.hasValue() ? 1 : 2;
                        if (ra != rb) return Integer.compare(ra, rb);
                        double x = a.hasValue() ? a.mm24 : -1, y = b.hasValue() ? b.mm24 : -1;
                        if (x != y) return Double.compare(y, x);
                        return a.city.compareToIgnoreCase(b.city);
                    }
                });
                s.municipalRain.addAll(cities);
            } catch (Exception ignored) {
                // A grade de chuva por município é complemento; a tela segue sem ela.
            }

            // 3) Previsão de 1, 2 e 3 horas para cada ponte.
            double[] rain = basinRain(stations);
            double[] wx = parseWeather(await(weather), now);
            String bulletinText = parseBulletin(await(bulletin), now);
            List<Sample> lau = await(laurentino), agr = await(agronomica);
            for (Bridge b : bridges) {
                RiverReading r = s.rivers.get(b);
                ForecastEngine.Inputs in = new ForecastEngine.Inputs();
                in.bridge = b;
                in.now = now;
                in.samples = full.get(b);
                if (r != null) {
                    in.currentLevel = r.levelMeters;
                    in.readingMillis = r.readingMillis;
                    in.cotas = r.cotas;
                }
                if (b == Bridge.DOM_TITO) {
                    in.upstream.add(new ForecastEngine.Upstream("Itajaí do Sul (" + Bridge.RICARDO_KANITZ.displayName + ")",
                            full.get(Bridge.RICARDO_KANITZ)));
                    in.upstream.add(new ForecastEngine.Upstream("Itajaí do Oeste (" + Bridge.BR470.displayName + ")",
                            full.get(Bridge.BR470)));
                } else if (b == Bridge.BR470) {
                    in.upstream.add(new ForecastEngine.Upstream("Itajaí do Oeste em Laurentino", lau));
                    in.upstream.add(new ForecastEngine.Upstream("Rio Trombudo em Agronômica", agr));
                }
                in.taio = s.taio;
                in.ituporanga = s.ituporanga;
                in.rainBasin1hMean = rain[0];
                in.rainBasin1hMax = rain[1];
                in.rainBasin24hMean = rain[2];
                in.rainStations = (int) rain[3];
                in.rainNextHourMm = wx[0];
                in.rainNext2hMm = wx[1];
                in.rainNext3hMm = wx[2];
                in.rainProbability = (int) wx[3];
                in.bulletin = bulletinText;
                try {
                    s.forecasts.put(b, ForecastEngine.compute(in));
                } catch (Exception ignored) {
                    // A previsão nunca pode impedir a exibição da leitura atual.
                }
            }
        } finally {
            pool.shutdownNow();
        }

        s.fetchedAt = now;
        s.fromCache = false;
        s.errorMessage = null;
        for (RiverReading r : s.rivers.values()) {
            HistoryStore.add(context, r.bridge, r.levelMeters);
        }
        AppCache.save(context, s);

        // Registro das previsões de 1, 2 e 3 h: espelho local (SQLite) + Supabase.
        try {
            ForecastStore.recordAndResolve(context, s);
        } catch (Exception ignored) {
            // Gravar a previsão é histórico: nunca pode atrapalhar a tela.
        }
        try {
            SupabaseForecast.sync(context);
        } catch (Exception ignored) {}

        return s;
    }

    /** Mapa simples para não depender de EnumMap com genéricos aninhados. */
    private static final class EnumHistories {
        private final List<Sample>[] data;
        @SuppressWarnings("unchecked")
        EnumHistories() { data = new List[Bridge.values().length]; }
        void put(Bridge b, List<Sample> l) { data[b.ordinal()] = l; }
        List<Sample> get(Bridge b) { return data[b.ordinal()]; }
    }

    private static <T> T await(Future<T> future) {
        if (future == null) return null;
        try {
            return future.get(25, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    private static Future<List<Sample>> rainHistory(ExecutorService pool, final String stationId,
                                                    final String start, final String end) {
        if (stationId == null || stationId.isEmpty()) return null;
        return pool.submit(new Callable<List<Sample>>() {
            @Override public List<Sample> call() throws Exception {
                Object root = loadJson(API_BASE + "/public/station-history?station_id=" + stationId
                        + "&start=" + start + "&end=" + end + "&fields=rain");
                List<Sample> out = new ArrayList<>();
                if (!(root instanceof JSONObject)) return out;
                JSONArray rows = ((JSONObject) root).optJSONArray("rain");
                if (rows == null) return out;
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null || row.isNull("value")) continue;
                    long t = parseTimestamp(row.optString("timestamp", ""));
                    double v = row.optDouble("value", Double.NaN);
                    if (t > 0 && !Double.isNaN(v) && v >= 0) out.add(new Sample(t, v));
                }
                return out;
            }
        });
    }

    /**
     * Soma as leituras de 10 min do pluviômetro da barragem em 24 barras de uma hora (o horário de
     * cada barra é o fim da hora, como no Open-Meteo). Só substitui a estimativa do modelo quando a
     * série cobre pelo menos 12 das últimas 24 horas.
     */
    private static void applyGaugeRain(DamReading dam, List<Sample> raw, long now) {
        if (dam == null || raw == null || raw.isEmpty()) return;
        long lastHourEnd = ((now - 1) / HOUR + 1) * HOUR;
        double[] buckets = new double[24];
        boolean[] seen = new boolean[24];
        double total = 0;
        for (Sample s : raw) {
            if (s.timeMillis <= now - 24 * HOUR || s.timeMillis > now) continue;
            long hourEnd = ((s.timeMillis - 1) / HOUR + 1) * HOUR;
            int idx = 23 - (int) ((lastHourEnd - hourEnd) / HOUR);
            if (idx < 0 || idx > 23) continue;
            buckets[idx] += s.value;
            seen[idx] = true;
            total += s.value;
        }
        int covered = 0;
        for (boolean b : seen) if (b) covered++;
        if (covered < 12) return;
        dam.rainHourly.clear();
        for (int i = 0; i < 24; i++) {
            long end = lastHourEnd - (23 - i) * HOUR;
            dam.rainHourly.add(new Sample(end, Math.round(buckets[i] * 100.0) / 100.0));
        }
        dam.rain24hMm = Math.round(total * 10.0) / 10.0;
        dam.rainFromGauge = true;
    }

    private static Future<Object> gateHistory(ExecutorService pool, final String damId) {
        if (damId == null || damId.isEmpty()) return null;
        return pool.submit(new Callable<Object>() {
            @Override public Object call() throws Exception {
                return loadJson(API_BASE + "/public/dams/" + damId + "/gate-history?city_id=" + CITY_ID + "&days=1");
            }
        });
    }

    // ---------------------------------------------------------------- rios

    private static JSONArray stationsOf(Object root) {
        if (root instanceof JSONArray) return (JSONArray) root;
        if (root instanceof JSONObject) {
            JSONObject o = (JSONObject) root;
            JSONArray a = o.optJSONArray("stations");
            if (a == null) a = o.optJSONArray("data");
            if (a != null) return a;
        }
        return new JSONArray();
    }

    private static void fillRivers(JSONArray stations, Snapshot s) {
        for (Bridge b : Bridge.values()) {
            if (s.rivers.containsKey(b)) continue;
            JSONObject station = findStation(stations, b);
            if (station == null) continue;

            Double level = firstDouble(station, "level_m", "level", "water_level_m", "river_level_m");
            if (level == null || level <= 0) continue;

            String readingAt = firstString(station, "last_reading_at", "reading_at", "last_reading",
                    "updated_at", "timestamp", "datetime");
            Cotas cotas = parseCotas(station);

            RiverReading r = new RiverReading(b, level, cotas.situacao(level), formatReadingTime(readingAt));
            r.readingMillis = Math.max(0, parseTimestamp(readingAt));
            r.stationId = station.optString("station_id", station.optString("id", null));
            r.cotas = cotas;
            s.rivers.put(b, r);
        }
    }

    /** Procura a estação pelo id conhecido; se o id mudou, pelo nome; por fim, pelo rio. */
    private static JSONObject findStation(JSONArray stations, Bridge b) {
        if (stations == null) return null;
        for (String id : b.stationIds) {
            for (int i = 0; i < stations.length(); i++) {
                JSONObject o = stations.optJSONObject(i);
                if (o == null) continue;
                if (id.equalsIgnoreCase(o.optString("station_id", "")) || id.equalsIgnoreCase(o.optString("id", ""))) {
                    if (hasLevel(o)) return o;
                }
            }
        }
        for (String key : b.nameKeys) {
            for (int i = 0; i < stations.length(); i++) {
                JSONObject o = stations.optJSONObject(i);
                if (o != null && normalize(o.optString("name", "")).contains(key) && hasLevel(o)) return o;
            }
        }
        if (b.riverKey != null) {
            for (int i = 0; i < stations.length(); i++) {
                JSONObject o = stations.optJSONObject(i);
                if (o == null) continue;
                String name = normalize(o.optString("name", ""));
                String river = normalize(o.optString("river_name", ""));
                if (name.contains("ponte") && (river.contains(b.riverKey) || name.contains(b.riverKey)) && hasLevel(o)) return o;
            }
        }
        return null;
    }

    private static boolean hasLevel(JSONObject o) {
        Double level = firstDouble(o, "level_m", "level", "water_level_m", "river_level_m");
        return level != null && level > 0;
    }

    private static String stationIdByName(JSONArray stations, String key) {
        if (stations == null) return null;
        for (int i = 0; i < stations.length(); i++) {
            JSONObject o = stations.optJSONObject(i);
            if (o != null && normalize(o.optString("name", "")).contains(key) && hasLevel(o)) {
                String id = o.optString("station_id", "");
                if (!id.isEmpty()) return id;
            }
        }
        return null;
    }

    /** Cotas oficiais publicadas pela Defesa Civil para a estação ("band_thresholds"). */
    private static Cotas parseCotas(JSONObject station) {
        Cotas padrao = new Cotas();
        JSONArray bands = station.optJSONArray("band_thresholds");
        if (bands == null) return padrao;
        double at = Double.NaN, al = Double.NaN, em = Double.NaN;
        for (int i = 0; i < bands.length(); i++) {
            JSONObject band = bands.optJSONObject(i);
            if (band == null) continue;
            double cota = band.optDouble("cota_m", Double.NaN);
            String key = normalize(band.optString("band_key", band.optString("label", "")));
            if (key.contains("aten")) at = cota;
            else if (key.contains("alert")) al = cota;
            else if (key.contains("emerg")) em = cota;
        }
        Cotas c = new Cotas(at, al, em, true);
        return !Double.isNaN(at) && !Double.isNaN(al) && !Double.isNaN(em) && c.valida() ? c : padrao;
    }

    private static List<Sample> loadHistory(String stationId, String start, String end) throws Exception {
        Object root = loadJson(API_BASE + "/public/station-history?station_id=" + stationId
                + "&start=" + start + "&end=" + end + "&fields=level");
        List<Sample> out = new ArrayList<>();
        if (!(root instanceof JSONObject)) return out;
        JSONArray rows = ((JSONObject) root).optJSONArray("level");
        if (rows == null) return out;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || row.isNull("value")) continue;
            long t = parseTimestamp(row.optString("timestamp", ""));
            double v = row.optDouble("value", Double.NaN);
            if (t > 0 && !Double.isNaN(v) && v > 0) out.add(new Sample(t, v));
        }
        Collections.sort(out, new Comparator<Sample>() {
            @Override public int compare(Sample a, Sample b) { return Long.compare(a.timeMillis, b.timeMillis); }
        });
        return out;
    }

    /** Garante que a leitura atual do painel seja o último ponto da série. */
    private static void appendCurrent(List<Sample> list, RiverReading r, long now) {
        long t = r.readingMillis > 0 ? r.readingMillis : now;
        if (list.isEmpty() || t - list.get(list.size() - 1).timeMillis > 60000L) {
            list.add(new Sample(t, r.levelMeters));
        }
    }

    /** Reduz séries muito densas (há sensor que lê a cada 2 min) para o gráfico e o cache. */
    private static List<Sample> thin(List<Sample> list, long minGapMs) {
        List<Sample> out = new ArrayList<>();
        if (list == null) return out;
        long lastKept = 0;
        for (int i = 0; i < list.size(); i++) {
            Sample s = list.get(i);
            if (i == 0 || i == list.size() - 1 || s.timeMillis - lastKept >= minGapMs) {
                out.add(s);
                lastKept = s.timeMillis;
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- chuva

    /** {média 1 h, máximo 1 h, média 24 h, nº de pluviômetros} das estações da bacia. */
    private static double[] basinRain(JSONArray stations) {
        double sum1 = 0, max1 = 0, sum24 = 0;
        int n = 0;
        if (stations != null) {
            for (int i = 0; i < stations.length(); i++) {
                JSONObject o = stations.optJSONObject(i);
                if (o == null || o.optInt("rainfall_sensor", 0) != 1 || o.isNull("rainfall_1h")) continue;
                double r1 = o.optDouble("rainfall_1h", Double.NaN);
                if (Double.isNaN(r1) || r1 < 0) continue;
                double r24 = o.optDouble("rainfall_24h", 0);
                sum1 += r1;
                max1 = Math.max(max1, r1);
                sum24 += Double.isNaN(r24) ? 0 : Math.max(0, r24);
                n++;
            }
        }
        if (n == 0) return new double[] { Double.NaN, Double.NaN, Double.NaN, 0 };
        return new double[] { sum1 / n, max1, sum24 / n, n };
    }

    /** {mm na próxima hora, em 2 h, em 3 h, probabilidade máxima (%)} — média dos pontos da bacia. */
    private static double[] parseWeather(Object root, long now) {
        double[] none = { Double.NaN, Double.NaN, Double.NaN, -1 };
        if (root == null) return none;
        JSONArray places = root instanceof JSONArray ? (JSONArray) root : new JSONArray().put(root);
        double sum1 = 0, sum2 = 0, sum3 = 0;
        int n = 0, prob = -1;
        for (int p = 0; p < places.length(); p++) {
            JSONObject hourly = places.optJSONObject(p) == null ? null : places.optJSONObject(p).optJSONObject("hourly");
            if (hourly == null) continue;
            JSONArray time = hourly.optJSONArray("time");
            JSONArray rain = hourly.optJSONArray("precipitation");
            JSONArray pp = hourly.optJSONArray("precipitation_probability");
            if (time == null || rain == null) continue;
            double[] acc = new double[3];
            int used = 0;
            for (int i = 0; i < time.length() && used < 3; i++) {
                // Cada valor é o acumulado da hora que termina no horário informado.
                long endOfHour = time.optLong(i, 0) * 1000L;
                if (endOfHour <= now) continue;
                double mm = rain.optDouble(i, Double.NaN);
                if (Double.isNaN(mm)) continue;
                if (pp != null && !pp.isNull(i)) prob = Math.max(prob, pp.optInt(i, -1));
                for (int k = used; k < 3; k++) acc[k] += mm;
                used++;
            }
            if (used > 0) {
                sum1 += acc[0];
                sum2 += acc[1];
                sum3 += acc[2];
                n++;
            }
        }
        if (n == 0) return none;
        return new double[] { sum1 / n, sum2 / n, sum3 / n, prob };
    }

    // ---------------------------------------------------------------- chuva por município

    private static final String STATIONS_LIST_URL = API_BASE + "/public/stations/list?city_id=" + CITY_ID;
    /** Pluviômetro sem leitura há mais que isto não entra na conta (fica como "sem leitura"). */
    private static final long GAUGE_MAX_AGE = 3L * 3600000L;

    /**
     * Agrupa os pluviômetros da Defesa Civil pelo município onde estão instalados
     * ({@code spatial_city_name} da lista de estações) e devolve a média das últimas 24 h.
     * Municípios cujo pluviômetro não está enviando dados ficam marcados para receber uma estimativa.
     */
    private static List<MunicipalRain> municipalRainFromGauges(JSONArray panel, JSONArray list, long now) {
        java.util.LinkedHashMap<String, MunicipalRain> byCity = new java.util.LinkedHashMap<>();
        java.util.Map<String, double[]> sums = new java.util.HashMap<>();
        if (list == null) return new ArrayList<>();
        java.util.Map<String, JSONObject> live = new java.util.HashMap<>();
        if (panel != null) {
            for (int i = 0; i < panel.length(); i++) {
                JSONObject o = panel.optJSONObject(i);
                if (o != null) live.put(o.optString("station_id", ""), o);
            }
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject st = list.optJSONObject(i);
            if (st == null || st.optInt("station_rainfall_sensor", 0) != 1 || st.optInt("enabled", 1) != 1) continue;
            String city = st.isNull("spatial_city_name") ? "" : st.optString("spatial_city_name", "").trim();
            if (city.isEmpty()) city = st.optString("name", "").trim();
            if (city.isEmpty()) continue;
            MunicipalRain m = byCity.get(city);
            if (m == null) {
                m = new MunicipalRain(city);
                byCity.put(city, m);
                sums.put(city, new double[] { 0, 0, 0, 0, 0, 0 });
            }
            double[] acc = sums.get(city);
            double la = st.optDouble("latitude", Double.NaN), lo = st.optDouble("longitude", Double.NaN);
            if (!Double.isNaN(la) && !Double.isNaN(lo)) {
                acc[3] += la;
                acc[4] += lo;
                acc[5]++;
            }
            JSONObject p = live.get(st.optString("station_id", ""));
            long last = p == null ? -1 : parseTimestamp(p.optString("last_reading_at", ""));
            boolean fresh = p != null && last > 0 && now - last <= GAUGE_MAX_AGE && !p.isNull("rainfall_24h");
            double r24 = fresh ? p.optDouble("rainfall_24h", Double.NaN) : Double.NaN;
            if (Double.isNaN(r24) || r24 < 0) {
                m.silentGauges++;
                continue;
            }
            acc[0] += r24;
            double r1 = p.optDouble("rainfall_1h", Double.NaN);
            if (!Double.isNaN(r1) && r1 >= 0) {
                acc[1] += r1;
                acc[2]++;
            }
            m.gauges++;
        }
        List<MunicipalRain> out = new ArrayList<>();
        for (MunicipalRain m : byCity.values()) {
            double[] acc = sums.get(m.city);
            if (acc[5] > 0) {
                m.latitude = acc[3] / acc[5];
                m.longitude = acc[4] / acc[5];
            }
            if (m.gauges > 0) {
                m.mm24 = Math.round(acc[0] / m.gauges * 10.0) / 10.0;
                if (acc[2] > 0) m.mm1h = Math.round(acc[1] / acc[2] * 10.0) / 10.0;
                m.source = MunicipalRain.FONTE_PLUVIOMETRO;
            }
            out.add(m);
        }
        return out;
    }

    /** URL do Open-Meteo para estimar as últimas 24 h onde o pluviômetro está calado. */
    private static String estimateUrl(List<MunicipalRain> list) {
        StringBuilder lat = new StringBuilder(), lon = new StringBuilder();
        for (MunicipalRain m : list) {
            if (m.gauges > 0 || Double.isNaN(m.latitude) || Double.isNaN(m.longitude)) continue;
            if (lat.length() > 0) {
                lat.append(',');
                lon.append(',');
            }
            lat.append(String.format(Locale.US, "%.4f", m.latitude));
            lon.append(String.format(Locale.US, "%.4f", m.longitude));
        }
        if (lat.length() == 0) return null;
        return "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&hourly=precipitation&past_days=1&forecast_days=1&timeformat=unixtime&timezone=GMT";
    }

    private static void applyEstimates(List<MunicipalRain> list, Object root, long now) {
        if (root == null) return;
        JSONArray places = root instanceof JSONArray ? (JSONArray) root : new JSONArray().put(root);
        int p = 0;
        for (MunicipalRain m : list) {
            if (m.gauges > 0 || Double.isNaN(m.latitude) || Double.isNaN(m.longitude)) continue;
            JSONObject holder = places.optJSONObject(p++);
            JSONObject hourly = holder == null ? null : holder.optJSONObject("hourly");
            if (hourly == null) continue;
            JSONArray time = hourly.optJSONArray("time");
            JSONArray mm = hourly.optJSONArray("precipitation");
            if (time == null || mm == null) continue;
            double sum = 0;
            int hours = 0;
            for (int i = 0; i < time.length() && i < mm.length(); i++) {
                long t = time.optLong(i, 0L) * 1000L;
                double v = mm.optDouble(i, Double.NaN);
                if (t <= now - 24 * HOUR || t > now || Double.isNaN(v) || v < 0) continue;
                sum += v;
                hours++;
            }
            if (hours >= 12) {
                m.mm24 = Math.round(sum * 10.0) / 10.0;
                m.source = MunicipalRain.FONTE_ESTIMATIVA;
            }
        }
    }

    /** Resumo do boletim diário da Defesa Civil de Rio do Sul para hoje. */
    private static String parseBulletin(Object root, long now) {
        if (!(root instanceof JSONObject)) return "";
        JSONArray days = ((JSONObject) root).optJSONArray("days");
        if (days == null) return "";
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("America/Sao_Paulo"));
        String today = fmt.format(new Date(now));
        for (int i = 0; i < days.length(); i++) {
            JSONObject day = days.optJSONObject(i);
            if (day == null || !today.equals(day.optString("forecast_date", ""))) continue;
            StringBuilder sb = new StringBuilder();
            JSONObject band = day.optJSONObject("precipitation_band");
            String label = band == null ? "" : band.optString("label", "");
            if (!label.isEmpty()) sb.append(label);
            if (!day.isNull("rainfall_mm")) {
                if (sb.length() > 0) sb.append(" — ");
                sb.append(String.format(BR, "até %.0f mm no dia", day.optDouble("rainfall_max_mm", day.optDouble("rainfall_mm", 0))));
            }
            String summary = day.isNull("summary") ? "" : day.optString("summary", "").trim();
            if (!summary.isEmpty()) {
                if (sb.length() > 0) sb.append(". ");
                sb.append(summary);
            }
            return sb.toString();
        }
        return "";
    }

    // ---------------------------------------------------------------- barragens

    private static DamReading parseDam(JSONObject dam, String name, int defaultTotalGates) {
        if (dam == null) return null;

        Double level = firstDouble(dam, "nivel_m", "level_m", "level", "water_level_m", "upstream_level_m",
                "upstream_level", "montante_m", "montante");
        Double pct = firstDouble(dam, "percent_use", "capacidade_atual", "capacity_percent", "capacity_percentage",
                "percentage", "percent", "usage_percent", "utilization_percent", "occupancy_percent");
        Integer open = firstInt(dam, "comportas_abertas", "gates_open", "open_gates", "open_count",
                "opened_gates", "gates_open_count");
        Integer total = firstInt(dam, "comportas_total", "gates_total", "total_gates", "gate_count", "gates_count");

        boolean[] gateStates = gateStatesFromArray(dam);
        if (gateStates != null) {
            int count = 0;
            for (boolean g : gateStates) if (g) count++;
            if (open == null) open = count;
            if (total == null && gateStates.length > 0) total = gateStates.length;
        }
        if (total == null || total <= 0) total = defaultTotalGates;
        if (open == null || open < 0) open = 0;

        String readingAt = firstString(dam, "measured_at", "last_reading_at", "reading_at", "updated_at",
                "timestamp", "datetime");

        DamReading d = new DamReading(name, level == null ? 0.0 : level, pct == null ? 0.0 : pct,
                Math.min(open, total), total, formatReadingTime(readingAt), gateStates);
        String sid = firstString(dam, "station_id", "id");
        d.stationId = sid == null ? "" : sid;
        d.latitude = dam.optDouble("latitude", Double.NaN);
        d.longitude = dam.optDouble("longitude", Double.NaN);
        return d;
    }

    /** Manobras de comportas e variação do reservatório nas últimas horas. */
    private static void applyGateHistory(DamReading dam, Object root, long now) {
        if (dam == null || !(root instanceof JSONObject)) return;
        JSONObject o = (JSONObject) root;
        JSONArray events = o.optJSONArray("events");
        long lastEvent = 0;
        if (events != null) {
            for (int i = 0; i < events.length(); i++) {
                JSONObject e = events.optJSONObject(i);
                if (e == null) continue;
                long t = parseTimestamp(e.optString("time", ""));
                if (t <= 0 || now - t > 24 * HOUR) continue;
                boolean opened = e.optBoolean("aberta", false);
                if (opened) dam.gatesOpened24h++;
                else dam.gatesClosed24h++;
                // Manobras de 2 a 12 h atrás: a água delas pode estar chegando agora.
                long age = now - t;
                if (age >= 2 * HOUR && age <= 12 * HOUR) {
                    if (opened) dam.gatesOpenedArriving++;
                    else dam.gatesClosedArriving++;
                }
                lastEvent = Math.max(lastEvent, t);
            }
        }
        if (lastEvent > 0) dam.hoursSinceLastGateEvent = (now - lastEvent) / (double) HOUR;

        JSONArray series = o.optJSONArray("series");
        if (series != null && series.length() >= 2) {
            double latest = Double.NaN, before = Double.NaN;
            long latestT = 0;
            for (int i = series.length() - 1; i >= 0; i--) {
                JSONObject p = series.optJSONObject(i);
                if (p == null || p.isNull("nivel_m")) continue;
                long t = parseTimestamp(p.optString("time", ""));
                if (t <= 0) continue;
                if (Double.isNaN(latest)) {
                    latest = p.optDouble("nivel_m", Double.NaN);
                    latestT = t;
                } else if (latestT - t >= 3 * HOUR - 10 * 60000L) {
                    before = p.optDouble("nivel_m", Double.NaN);
                    break;
                }
            }
            if (!Double.isNaN(latest) && !Double.isNaN(before)) dam.levelChange3hCm = (latest - before) * 100.0;
        }

        // Série de 24 h do reservatório: nível, ocupação e comportas abertas, em passos de ~15 min.
        if (series != null) {
            long lastKept = 0;
            for (int i = 0; i < series.length(); i++) {
                JSONObject p = series.optJSONObject(i);
                if (p == null || p.isNull("nivel_m")) continue;
                long t = parseTimestamp(p.optString("time", ""));
                double level = p.optDouble("nivel_m", Double.NaN);
                if (t <= 0 || Double.isNaN(level) || level <= 0) continue;
                boolean lastOne = i == series.length() - 1;
                if (!dam.history.isEmpty() && !lastOne && t - lastKept < 15 * 60000L) continue;
                lastKept = t;
                int openNow = (int) Math.max(0, p.optDouble("gates_open", 0));
                int totalNow = (int) Math.max(0, p.optDouble("gates_known", p.optDouble("gates_total", 0)));
                dam.history.add(new DamPoint(t, level, p.optDouble("percent_use", Double.NaN), openNow, totalNow));
            }
        }
    }

    /** Últimos 24 h de chuva horária sobre o ponto das duas barragens. */
    private static String damRainUrl(JSONObject taio, JSONObject itup) {
        StringBuilder lat = new StringBuilder();
        StringBuilder lon = new StringBuilder();
        appendRainPoint(lat, lon, taio);
        appendRainPoint(lat, lon, itup);
        if (lat.length() == 0) return null;
        return "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&hourly=precipitation&past_days=1&forecast_days=1&timeformat=unixtime&timezone=GMT";
    }

    private static void appendRainPoint(StringBuilder lat, StringBuilder lon, JSONObject dam) {
        if (dam == null) return;
        double la = dam.optDouble("latitude", Double.NaN);
        double lo = dam.optDouble("longitude", Double.NaN);
        if (Double.isNaN(la) || Double.isNaN(lo)) return;
        if (lat.length() > 0) {
            lat.append(',');
            lon.append(',');
        }
        lat.append(String.format(Locale.US, "%.4f", la));
        lon.append(String.format(Locale.US, "%.4f", lo));
    }

    /** Distribui a chuva do modelo (mm por hora) sobre cada barragem, nas últimas 24 h. */
    private static void applyDamRain(DamReading taio, DamReading itup, Object root, long now) {
        if (root == null) return;
        JSONArray places = root instanceof JSONArray ? (JSONArray) root : new JSONArray().put(root);
        DamReading[] dams = { taio, itup };
        for (int p = 0; p < places.length() && p < dams.length; p++) {
            DamReading d = dams[p];
            JSONObject holder = places.optJSONObject(p);
            JSONObject hourly = holder == null ? null : holder.optJSONObject("hourly");
            if (d == null || hourly == null) continue;
            JSONArray time = hourly.optJSONArray("time");
            JSONArray mm = hourly.optJSONArray("precipitation");
            if (time == null || mm == null) continue;

            List<Sample> buckets = new ArrayList<>();
            for (int i = 0; i < time.length() && i < mm.length(); i++) {
                long t = time.optLong(i, 0L) * 1000L;
                double rain = mm.optDouble(i, Double.NaN);
                if (t <= 0 || Double.isNaN(rain) || rain < 0) continue;
                if (t > now || t < now - 24 * HOUR) continue;
                buckets.add(new Sample(t, Math.round(rain * 100.0) / 100.0));
            }
            if (buckets.isEmpty()) continue;
            int from = Math.max(0, buckets.size() - 24);
            double sum = 0;
            for (int i = from; i < buckets.size(); i++) {
                d.rainHourly.add(buckets.get(i));
                sum += buckets.get(i).value;
            }
            d.rain24hMm = Math.round(sum * 10.0) / 10.0;
    }
    }

    private static boolean isGateOpen(JSONObject g) {
        if (g.has("aberta")) return g.optBoolean("aberta", false);
        if (g.has("open")) return g.optBoolean("open", false);
        if (g.has("is_open")) return g.optBoolean("is_open", false);
        if (g.has("opened")) return g.optBoolean("opened", false);
        String status = firstString(g, "status", "state", "situation");
        if (status == null) return false;
        String x = status.toLowerCase(Locale.ROOT);
        return x.contains("open") || x.contains("abert");
    }

    private static boolean[] gateStatesFromArray(JSONObject dam) {
        String[] keys = { "comportas", "gates", "sluice_gates", "floodgates" };
        for (String key : keys) {
            JSONArray arr = dam.optJSONArray(key);
            if (arr == null) continue;
            boolean[] states = new boolean[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.opt(i);
                if (item instanceof JSONObject) states[i] = isGateOpen((JSONObject) item);
                else if (item instanceof Boolean) states[i] = (Boolean) item;
            }
            return states;
        }
        return null;
    }

    // ---------------------------------------------------------------- JSON

    private static Object loadJson(String url) throws Exception {
        String body = httpGet(url);
        Object root = new JSONTokener(body).nextValue();
        if (!(root instanceof JSONObject) && !(root instanceof JSONArray)) {
            throw new IllegalStateException("Resposta da API não é JSON.");
        }
        return root;
    }

    private static JSONObject findObjectByName(Object node, String... wantedNames) {
        JSONArray list = node instanceof JSONArray ? (JSONArray) node : stationsOf(node);
        for (String wanted : wantedNames) {
            String target = normalize(wanted);
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                String value = normalize(o.optString("name", ""));
                if (!value.isEmpty() && (value.equals(target) || value.contains(target))) return o;
            }
        }
        return null;
    }

    private static Double firstDouble(JSONObject o, String... keys) {
        for (String key : keys) {
            if (!o.has(key) || o.isNull(key)) continue;
            Double parsed = toDouble(o.opt(key));
            if (parsed != null) return parsed;
        }
        return null;
    }

    private static Integer firstInt(JSONObject o, String... keys) {
        Double d = firstDouble(o, keys);
        return d == null ? null : (int) Math.round(d);
    }

    private static Double toDouble(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
        }
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
        return null;
    }

    // ---------------------------------------------------------------- datas

    private static final Pattern ISO = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2})(?::(\\d{2}))?(?:[.,]\\d+)?\\s*(Z|[+-]\\d{2}(?::?\\d{2})?)?");

    /**
     * Converte os horários da API em epoch ms. Aceita "2026-11-11T17:12:44.549818Z",
     * "2026-11-11 17:10:00+00" etc. Feito à mão porque SimpleDateFormat lê os microssegundos como
     * milissegundos (adiantando o horário em vários minutos) e o padrão "X" exige Android 7.
     */
    static long parseTimestamp(String value) {
        if (value == null) return -1;
        Matcher m = ISO.matcher(value.trim());
        if (!m.find()) return -1;
        try {
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            c.clear();
            c.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)),
                    m.group(6) == null ? 0 : Integer.parseInt(m.group(6)));
            long t = c.getTimeInMillis();
            String zone = m.group(7);
            if (zone != null && !zone.equals("Z")) {
                int sign = zone.charAt(0) == '-' ? -1 : 1;
                String digits = zone.substring(1).replace(":", "");
                int hours = Integer.parseInt(digits.substring(0, 2));
                int minutes = digits.length() >= 4 ? Integer.parseInt(digits.substring(2, 4)) : 0;
                t -= sign * (hours * 60L + minutes) * 60000L;
            }
            return t;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String isoUtc(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(millis));
    }

    private static String formatReadingTime(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "horário não informado";
        long t = parseTimestamp(raw);
        if (t > 0) return new SimpleDateFormat("dd/MM HH:mm", BR).format(new Date(t));
        String s = raw.trim();
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    // ---------------------------------------------------------------- rede

    private static String httpGet(String urlString) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlString).openConnection();
        try {
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

            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);

            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) >= 0) sb.append(buf, 0, n);
            br.close();

            String body = sb.toString().trim();
            if (body.isEmpty()) throw new IllegalStateException("Resposta vazia da API.");
            return body;
        } finally {
            c.disconnect();
        }
    }

    private static String friendlyError(Exception e) {
        String type = e.getClass().getSimpleName();
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) return type;
        msg = msg.replace('\n', ' ').replace('\r', ' ').trim();
        if (msg.length() > 90) msg = msg.substring(0, 90);
        return type + ": " + msg;
    }

    static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replace("á", "a").replace("à", "a").replace("â", "a").replace("ã", "a")
                .replace("é", "e").replace("ê", "e").replace("í", "i")
                .replace("ó", "o").replace("ô", "o").replace("õ", "o")
                .replace("ú", "u").replace("ç", "c")
                .replace("-", " ").replace("–", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
