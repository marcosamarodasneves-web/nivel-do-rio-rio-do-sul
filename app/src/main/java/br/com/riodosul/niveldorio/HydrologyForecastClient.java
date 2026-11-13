package br.com.riodosul.niveldorio;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Cliente somente leitura da previsão centralizada. Não grava previsões em banco nem envia dados. */
public final class HydrologyForecastClient {
    private static final String BASE = BuildConfig.HYDROLOGY_API_BASE;
    static final String MODEL_VERSION = "deterministic-v2";
    private static final String CACHE_KEY = "hydrology_forecasts_json_v2";
    private static final long STALE_AFTER_MS = 30L * 60L * 1000L;

    private HydrologyForecastClient() {}

    public interface Callback {
        void onComplete(Response response, Exception error);
    }

    /** Linha histórica devolvida pelo servidor, sem persistência local. */
    public static final class HistoryRow {
        public String bridge = "";
        public int horizonH;
        public long baseTime;
        public long targetTime;
        public double currentLevel = Double.NaN;
        public double predictedLevel = Double.NaN;
        public double bandCm = Double.NaN;
        public double toleranceCm = Double.NaN;
        public int confidence;
        public String outcome = "pendente";
        public long actualTime;
        public double actualLevel = Double.NaN;
        public double errorCm = Double.NaN;
        public String modelVersion = "";
    }

    /** Estatística de acurácia calculada pelo servidor para os últimos sete dias. */
    public static final class AccuracyRow {
        public String bridge = "";
        public int horizonH;
        public int previsoes;
        public int conferidas;
        public int dentroDaMargem;
        public int quase;
        public int fora;
        public int semMedicao;
        public Double pctDentro;
        public Double erroMedioCm;
    }

    /** Uma linha da grade agrupada por momento de geração, mantendo os três horizontes. */
    public static final class HistoryGroup {
        public long baseTime;
        public double currentLevel = Double.NaN;
        public final HistoryRow[] rows = new HistoryRow[3];
    }

    public static final class Response {
        public final EnumMap<Bridge, Forecast> latest = new EnumMap<>(Bridge.class);
        public final List<HistoryRow> history = new ArrayList<>();
        public final List<AccuracyRow> accuracy = new ArrayList<>();
        public final Set<Bridge> requested = new HashSet<>();
        public String asOf = "";
        public String disclaimer = "";
        public String modelVersion = "";
        public boolean contractV2;
        public long fetchedAt;

        public List<HistoryGroup> groupsFor(Bridge bridge, int maxGroups) {
            List<HistoryRow> rows = new ArrayList<>();
            String id = bridgeId(bridge);
            for (HistoryRow row : history) if (id.equals(row.bridge)) rows.add(row);
            Collections.sort(rows, new Comparator<HistoryRow>() {
                @Override public int compare(HistoryRow a, HistoryRow b) {
                    int byBase = Long.compare(b.baseTime, a.baseTime);
                    return byBase != 0 ? byBase : Integer.compare(a.horizonH, b.horizonH);
                }
            });
            List<HistoryGroup> out = new ArrayList<>();
            for (HistoryRow row : rows) {
                HistoryGroup group = null;
                for (HistoryGroup candidate : out) {
                    if (candidate.baseTime == row.baseTime) {
                        group = candidate;
                        break;
                    }
                }
                if (group == null) {
                    if (out.size() >= maxGroups) break;
                    group = new HistoryGroup();
                    group.baseTime = row.baseTime;
                    group.currentLevel = row.currentLevel;
                    out.add(group);
                }
                if (row.horizonH >= 1 && row.horizonH <= 3) group.rows[row.horizonH - 1] = row;
            }
            return out;
        }

        public AccuracyRow accuracyFor(Bridge bridge, int horizon) {
            String id = bridgeId(bridge);
            for (AccuracyRow row : accuracy) {
                if (id.equals(row.bridge) && row.horizonH == horizon) return row;
            }
            return null;
        }
    }

    /** Faz uma consulta por ponte em paralelo; cada consulta é um GET público e independente. */
    public static void fetchAsync(final Context context, final Callback callback) {
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                Response merged = new Response();
                merged.fetchedAt = System.currentTimeMillis();
                for (Bridge bridge : Bridge.values()) merged.requested.add(bridge);
                Exception firstError = null;
                ExecutorService pool = Executors.newFixedThreadPool(Bridge.values().length);
                try {
                    List<Future<Response>> futures = new ArrayList<>();
                    for (final Bridge bridge : Bridge.values()) {
                        futures.add(pool.submit(new Callable<Response>() {
                            @Override public Response call() throws Exception {
                                return fetchOne(bridge);
                            }
                        }));
                    }
                    for (Future<Response> future : futures) {
                        try {
                            merge(merged, future.get(20, TimeUnit.SECONDS));
                        } catch (Exception e) {
                            if (firstError == null) firstError = unwrap(e);
                        }
                    }
                } finally {
                    pool.shutdownNow();
                }
                if (!merged.contractV2) {
                    // Entrega uma resposta explícita para a UI marcar o card como indisponível.
                    // Níveis, chuva e barragens continuam independentes desta falha.
                    merged.modelVersion = "";
                    if (callback != null) callback.onComplete(merged, firstError == null
                            ? new IllegalStateException("Contrato de previsão v2 indisponível.") : firstError);
                    return;
                }
                try {
                    // Um payload v2 vazio é válido: a UI deve mostrar indisponível, nunca calcular fallback.
                    JSONObject cache = toJson(merged);
                    // Apenas o último snapshot. História e acurácia permanecem no servidor.
                    cache.remove("forecasts");
                    cache.remove("accuracy");
                    saveCachedJson(app, cache.toString());
                    if (callback != null) callback.onComplete(merged, firstError);
                } catch (Exception e) {
                    if (callback != null) callback.onComplete(merged, firstError == null ? e : firstError);
                }
            }
        }, "hydrology-forecasts").start();
    }

    /** Mescla a resposta no snapshot e preserva as cotas oficiais dos níveis diretos. */
    public static void applyToSnapshot(Snapshot snapshot, Response response, boolean fromCache) {
        if (snapshot == null || response == null) return;
        snapshot.forecastResponse = response;
        for (Bridge bridge : Bridge.values()) {
            Forecast forecast = response.latest.get(bridge);
            if (forecast == null) {
                forecast = unavailable(bridge, "O servidor não retornou uma previsão para esta ponte.");
            }
            forecast.fromCache = fromCache;
            RiverReading reading = snapshot.rivers.get(bridge);
            if (reading != null && reading.cotas != null) forecast.cotas = reading.cotas;
            snapshot.forecasts.put(bridge, forecast);
        }
    }

    /** Carrega somente o último JSON salvo, marcado como cache e submetido à regra de 30 minutos. */
    static Response loadCached(Context context) {
        String raw = AppPrefs.prefs(context).getString(CACHE_KEY, "");
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            Object root = new JSONTokener(raw).nextValue();
            if (!(root instanceof JSONObject)) return null;
            return parseResponse((JSONObject) root, System.currentTimeMillis(), true);
        } catch (Exception ignored) {
            return null;
        }
    }

    static Response parseResponse(JSONObject root, long now, boolean fromCache) {
        Response response = new Response();
        response.fetchedAt = now;
        response.asOf = root.optString("as_of", "");
        response.disclaimer = root.optString("disclaimer", "");
        response.modelVersion = root.optString("model_version", "");
        response.contractV2 = MODEL_VERSION.equals(response.modelVersion);
        if (!response.contractV2) return response;
        JSONArray latest = root.optJSONArray("latest");
        if (latest != null) {
            for (int i = 0; i < latest.length(); i++) {
                JSONObject item = latest.optJSONObject(i);
                if (item == null) continue;
                Bridge bridge = bridgeFromId(item.optString("bridge", ""));
                if (bridge == null) continue;
                response.requested.add(bridge);
                if (!MODEL_VERSION.equals(item.optString("model_version", ""))) continue;
                Forecast forecast = parseLatest(bridge, item, now, fromCache);
                if (response.modelVersion.isEmpty()) response.modelVersion = forecast.modelVersion;
                response.latest.put(bridge, forecast);
            }
        }
        JSONArray history = root.optJSONArray("forecasts");
        if (history != null) {
            for (int i = 0; i < history.length(); i++) {
                JSONObject item = history.optJSONObject(i);
                if (item != null && rowIsV2(item)) response.history.add(parseHistory(item));
            }
        }
        JSONArray accuracy = root.optJSONArray("accuracy");
        if (accuracy != null) {
            for (int i = 0; i < accuracy.length(); i++) {
                JSONObject item = accuracy.optJSONObject(i);
                if (item != null && rowIsV2(item)) response.accuracy.add(parseAccuracy(item));
            }
        }
        return response;
    }

    static String bridgeId(Bridge bridge) {
        if (bridge == Bridge.DOM_TITO) return "dom-tito";
        if (bridge == Bridge.BR470) return "br-470";
        return "ricardo-kanitz";
    }

    private static Response fetchOne(Bridge bridge) throws Exception {
        String query = "?bridge=" + URLEncoder.encode(bridgeId(bridge), "UTF-8") + "&hours=24";
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(BASE + "/api/hydrology/forecasts" + query).openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder body = new StringBuilder();
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) >= 0) body.append(buffer, 0, count);
            reader.close();
            Object root = new JSONTokener(body.toString().trim()).nextValue();
            if (!(root instanceof JSONObject)) throw new IllegalStateException("Resposta de previsão não é JSON.");
            Response parsed = parseResponse((JSONObject) root, System.currentTimeMillis(), false);
            if (!parsed.contractV2) throw new IllegalStateException("Resposta de previsão não usa deterministic-v2.");
            return parsed;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void merge(Response target, Response source) {
        if (source == null) return;
        target.asOf = newer(target.asOf, source.asOf);
        target.contractV2 = target.contractV2 || source.contractV2;
        if (target.disclaimer.isEmpty()) target.disclaimer = source.disclaimer;
        if (target.modelVersion.isEmpty()) target.modelVersion = source.modelVersion;
        target.requested.addAll(source.requested);
        target.latest.putAll(source.latest);
        for (HistoryRow row : source.history) {
            if (!containsHistory(target.history, row)) target.history.add(row);
        }
        for (AccuracyRow row : source.accuracy) {
            if (!containsAccuracy(target.accuracy, row)) target.accuracy.add(row);
        }
    }

    private static boolean containsHistory(List<HistoryRow> rows, HistoryRow candidate) {
        for (HistoryRow row : rows) {
            if (row.bridge.equals(candidate.bridge) && row.horizonH == candidate.horizonH
                    && row.baseTime == candidate.baseTime && row.targetTime == candidate.targetTime) return true;
        }
        return false;
    }

    private static boolean containsAccuracy(List<AccuracyRow> rows, AccuracyRow candidate) {
        for (AccuracyRow row : rows) if (row.bridge.equals(candidate.bridge) && row.horizonH == candidate.horizonH) return true;
        return false;
    }

    private static Forecast parseLatest(Bridge bridge, JSONObject item, long now, boolean fromCache) {
        Forecast forecast = new Forecast();
        forecast.bridge = bridge;
        forecast.modelVersion = item.optString("model_version", "");
        forecast.generatedAt = item.optString("generated_at", "");
        forecast.available = item.optBoolean("available", false);
        forecast.unavailableReason = item.optString("unavailable_reason", "");
        if (!MODEL_VERSION.equals(forecast.modelVersion)) {
            forecast.available = false;
            forecast.unavailableReason = "Versão de modelo não suportada nesta versão do app.";
        }
        forecast.baseTime = timestamp(item.optString("base_time", ""));
        forecast.currentLevel = nullableNumber(item, "current_level", Double.NaN);
        forecast.rate60 = nullableNumber(item, "rate60", Double.NaN);
        forecast.rate180 = nullableNumber(item, "rate180", Double.NaN);
        forecast.noiseCm = nullableNumber(item, "noise_cm", Double.NaN);
        forecast.samplesUsed = item.optInt("samples_used", 0);
        forecast.windowMinutes = item.optInt("window_minutes", 0);
        forecast.fromCache = fromCache;
        forecast.stale = forecast.baseTime <= 0 || forecast.baseTime > now || now - forecast.baseTime > STALE_AFTER_MS;
        if (forecast.stale && forecast.available) {
            forecast.available = false;
            forecast.unavailableReason = "Previsão antiga ou com horário inválido; aguardando atualização do servidor.";
        }
        if (!forecast.available && forecast.unavailableReason.isEmpty()) {
            forecast.unavailableReason = "O servidor ainda não tem uma previsão atual para esta ponte.";
        }
        forecast.freshnessLabel = forecast.stale ? "antiga" : fromCache ? "última resposta salva" : "atualizada";
        parseRain(item, forecast);
        parseFactors(item.optJSONArray("factors"), forecast);
        parseNotes(item.optJSONArray("notes"), forecast);
        forecast.upstreamSummary = item.optString("upstream_summary", "");
        forecast.damSummary = item.optString("dam_summary", "");
        forecast.rainObservedSummary = item.optString("rain_observed_summary", "");
        forecast.rainForecastSummary = item.optString("rain_forecast_summary", "");
        JSONArray horizons = item.optJSONArray("horizons");
        if (horizons != null) {
            for (int i = 0; i < horizons.length(); i++) {
                JSONObject value = horizons.optJSONObject(i);
                if (value == null) continue;
                Forecast.Horizon horizon = new Forecast.Horizon();
                horizon.hours = value.optInt("hours", 0);
                horizon.targetTime = timestamp(value.optString("target_time", ""));
                horizon.predictedLevel = nullableNumber(value, "predicted_level", Double.NaN);
                horizon.deltaCm = numberOr(value, "delta_cm", 0);
                horizon.bandCm = numberOr(value, "band_cm", Double.NaN);
                horizon.confidence = value.optInt("confidence", 0);
                horizon.trendCm = numberOr(value, "trend_cm", 0);
                horizon.upstreamCm = numberOr(value, "upstream_cm", 0);
                horizon.damCm = numberOr(value, "dam_cm", 0);
                // Chuva nula significa ausência de dado/licença, nunca zero medido.
                horizon.rainObservedCm = numberOr(value, "rain_observed_cm", Double.NaN);
                horizon.rainForecastCm = numberOr(value, "rain_forecast_cm", Double.NaN);
                if (horizon.hours >= 1 && horizon.hours <= 3) forecast.horizons.add(horizon);
            }
        }
        Collections.sort(forecast.horizons, new Comparator<Forecast.Horizon>() {
            @Override public int compare(Forecast.Horizon a, Forecast.Horizon b) { return Integer.compare(a.hours, b.hours); }
        });
        Forecast.Horizon first = forecast.horizon(1);
        if (first != null && !Double.isNaN(first.predictedLevel) && first.targetTime > 0) {
            forecast.targetTime = first.targetTime;
            forecast.predictedLevel = first.predictedLevel;
            forecast.deltaCm = first.deltaCm;
            forecast.bandCm = first.bandCm;
            forecast.confidence = first.confidence;
        } else {
            forecast.targetTime = 0;
            forecast.predictedLevel = Double.NaN;
            forecast.deltaCm = 0;
            forecast.bandCm = Double.NaN;
            forecast.confidence = 0;
            if (forecast.available) {
                forecast.available = false;
                forecast.unavailableReason = "O servidor não retornou o horizonte de 1 hora.";
            }
        }
        return forecast;
    }

    private static void parseRain(JSONObject item, Forecast forecast) {
        // Campos meteo opcionais permanecem NaN quando o servidor retorna null.
        forecast.rainBasin1hMm = nullableNumber(item, "rain_basin_1h_mm", Double.NaN);
        forecast.rainBasin24hMm = nullableNumber(item, "rain_basin_24h_mm", Double.NaN);
        forecast.rainNextHourMm = nullableNumber(item, "rain_next_hour_mm", Double.NaN);
        forecast.rainNext2hMm = nullableNumber(item, "rain_next_2h_mm", Double.NaN);
        forecast.rainNext3hMm = nullableNumber(item, "rain_next_3h_mm", Double.NaN);
        Double probability = nullableNumber(item, "rain_probability");
        forecast.rainProbability = probability == null ? -1 : (int) Math.round(probability);
    }

    private static void parseFactors(JSONArray values, Forecast forecast) {
        if (values == null) return;
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            if (value == null) continue;
            forecast.factors.add(new Forecast.Factor(value.optString("key", ""), value.optString("title", ""),
                    numberOr(value, "cm", 0), value.optString("detail", "")));
        }
    }

    private static void parseNotes(JSONArray values, Forecast forecast) {
        if (values == null) return;
        for (int i = 0; i < values.length(); i++) {
            String note = values.optString(i, "").trim();
            if (!note.isEmpty()) forecast.notes.add(note);
        }
    }

    private static boolean rowIsV2(JSONObject item) {
        String version = item.optString("model_version", MODEL_VERSION);
        return MODEL_VERSION.equals(version);
    }

    private static HistoryRow parseHistory(JSONObject item) {
        HistoryRow row = new HistoryRow();
        row.bridge = item.optString("bridge", "");
        row.horizonH = item.optInt("horizon_h", 0);
        row.baseTime = timestamp(item.optString("base_time", ""));
        row.targetTime = timestamp(item.optString("target_time", ""));
        row.currentLevel = nullableNumber(item, "current_level", Double.NaN);
        row.predictedLevel = nullableNumber(item, "predicted_level", Double.NaN);
        row.bandCm = nullableNumber(item, "band_cm", Double.NaN);
        row.toleranceCm = nullableNumber(item, "tolerance_cm", Double.NaN);
        row.confidence = item.optInt("confidence", 0);
        row.outcome = item.optString("outcome", "pendente");
        row.actualTime = timestamp(item.optString("actual_time", ""));
        row.actualLevel = nullableNumber(item, "actual_level", Double.NaN);
        row.errorCm = nullableNumber(item, "error_cm", Double.NaN);
        row.modelVersion = item.optString("model_version", "");
        return row;
    }

    private static AccuracyRow parseAccuracy(JSONObject item) {
        AccuracyRow row = new AccuracyRow();
        row.bridge = item.optString("bridge", "");
        row.horizonH = item.optInt("horizon_h", 0);
        row.previsoes = item.optInt("previsoes", 0);
        row.conferidas = item.optInt("conferidas", 0);
        row.dentroDaMargem = item.optInt("dentro_da_margem", 0);
        row.quase = item.optInt("quase", 0);
        row.fora = item.optInt("fora", 0);
        row.semMedicao = item.optInt("sem_medicao", 0);
        row.pctDentro = nullableNumber(item, "pct_dentro");
        row.erroMedioCm = nullableNumber(item, "erro_medio_cm");
        return row;
    }

    private static JSONObject toJson(Response response) throws Exception {
        JSONObject root = new JSONObject();
        root.put("as_of", response.asOf);
        root.put("model_version", response.modelVersion);
        root.put("disclaimer", response.disclaimer);
        JSONArray latest = new JSONArray();
        for (Forecast forecast : response.latest.values()) latest.put(latestJson(forecast));
        root.put("latest", latest);
        JSONArray history = new JSONArray();
        for (HistoryRow row : response.history) {
            JSONObject value = new JSONObject();
            value.put("bridge", row.bridge);
            value.put("horizon_h", row.horizonH);
            putTime(value, "base_time", row.baseTime);
            putTime(value, "target_time", row.targetTime);
            putNumber(value, "current_level", row.currentLevel);
            putNumber(value, "predicted_level", row.predictedLevel);
            putNumber(value, "band_cm", row.bandCm);
            putNumber(value, "tolerance_cm", row.toleranceCm);
            value.put("confidence", row.confidence);
            value.put("outcome", row.outcome);
            putTime(value, "actual_time", row.actualTime);
            putNumber(value, "actual_level", row.actualLevel);
            putNumber(value, "error_cm", row.errorCm);
            value.put("model_version", row.modelVersion);
            history.put(value);
        }
        root.put("forecasts", history);
        JSONArray accuracy = new JSONArray();
        for (AccuracyRow row : response.accuracy) {
            JSONObject value = new JSONObject();
            value.put("bridge", row.bridge);
            value.put("horizon_h", row.horizonH);
            value.put("previsoes", row.previsoes);
            value.put("conferidas", row.conferidas);
            value.put("dentro_da_margem", row.dentroDaMargem);
            value.put("quase", row.quase);
            value.put("fora", row.fora);
            value.put("sem_medicao", row.semMedicao);
            putNumber(value, "pct_dentro", row.pctDentro == null ? Double.NaN : row.pctDentro);
            putNumber(value, "erro_medio_cm", row.erroMedioCm == null ? Double.NaN : row.erroMedioCm);
            accuracy.put(value);
        }
        root.put("accuracy", accuracy);
        return root;
    }

    private static JSONObject latestJson(Forecast forecast) throws Exception {
        JSONObject value = new JSONObject();
        value.put("bridge", bridgeId(forecast.bridge));
        value.put("available", forecast.available);
        value.put("unavailable_reason", forecast.unavailableReason);
        value.put("model_version", forecast.modelVersion);
        value.put("generated_at", forecast.generatedAt);
        putTime(value, "base_time", forecast.baseTime);
        putNumber(value, "current_level", forecast.currentLevel);
        putNumber(value, "rate60", forecast.rate60);
        putNumber(value, "rate180", forecast.rate180);
        putNumber(value, "noise_cm", forecast.noiseCm);
        value.put("samples_used", forecast.samplesUsed);
        value.put("window_minutes", forecast.windowMinutes);
        value.put("upstream_summary", forecast.upstreamSummary);
        value.put("dam_summary", forecast.damSummary);
        value.put("rain_observed_summary", forecast.rainObservedSummary);
        value.put("rain_forecast_summary", forecast.rainForecastSummary);
        putNumber(value, "rain_basin_1h_mm", forecast.rainBasin1hMm);
        putNumber(value, "rain_basin_24h_mm", forecast.rainBasin24hMm);
        putNumber(value, "rain_next_hour_mm", forecast.rainNextHourMm);
        putNumber(value, "rain_next_2h_mm", forecast.rainNext2hMm);
        putNumber(value, "rain_next_3h_mm", forecast.rainNext3hMm);
        if (forecast.rainProbability >= 0) value.put("rain_probability", forecast.rainProbability);
        JSONArray horizons = new JSONArray();
        for (Forecast.Horizon horizon : forecast.horizons) {
            JSONObject h = new JSONObject();
            h.put("hours", horizon.hours);
            putTime(h, "target_time", horizon.targetTime);
            putNumber(h, "predicted_level", horizon.predictedLevel);
            putNumber(h, "delta_cm", horizon.deltaCm);
            putNumber(h, "band_cm", horizon.bandCm);
            h.put("confidence", horizon.confidence);
            putNumber(h, "trend_cm", horizon.trendCm);
            putNumber(h, "upstream_cm", horizon.upstreamCm);
            putNumber(h, "dam_cm", horizon.damCm);
            putNumber(h, "rain_observed_cm", horizon.rainObservedCm);
            putNumber(h, "rain_forecast_cm", horizon.rainForecastCm);
            horizons.put(h);
        }
        value.put("horizons", horizons);
        JSONArray factors = new JSONArray();
        for (Forecast.Factor factor : forecast.factors) {
            JSONObject f = new JSONObject();
            f.put("key", factor.key);
            f.put("title", factor.title);
            f.put("cm", factor.cm);
            f.put("detail", factor.detail);
            factors.put(f);
        }
        value.put("factors", factors);
        JSONArray notes = new JSONArray();
        for (String note : forecast.notes) notes.put(note);
        value.put("notes", notes);
        return value;
    }

    private static Forecast unavailable(Bridge bridge, String reason) {
        Forecast forecast = new Forecast();
        forecast.bridge = bridge;
        forecast.available = false;
        forecast.unavailableReason = reason;
        forecast.freshnessLabel = "indisponível";
        return forecast;
    }

    private static void saveCachedJson(Context context, String json) {
        AppPrefs.prefs(context).edit().putString(CACHE_KEY, json).apply();
    }

    private static void putTime(JSONObject object, String key, long value) throws Exception {
        if (value > 0) object.put(key, isoUtc(value));
    }

    private static void putNumber(JSONObject object, String key, double value) throws Exception {
        if (!Double.isNaN(value) && !Double.isInfinite(value)) object.put(key, value);
    }

    private static long timestamp(String value) {
        return DataRepository.parseTimestamp(value);
    }

    private static Double nullableNumber(JSONObject object, String key) {
        return nullableNumber(object, key, null);
    }

    private static double nullableNumber(JSONObject object, String key, double fallback) {
        Double value = nullableNumber(object, key);
        return value == null ? fallback : value;
    }

    private static Double nullableNumber(JSONObject object, String key, Double fallback) {
        if (!object.has(key) || object.isNull(key)) return fallback;
        Object raw = object.opt(key);
        if (raw instanceof Number) {
            double value = ((Number) raw).doubleValue();
            return Double.isNaN(value) || Double.isInfinite(value) ? fallback : value;
        }
        try {
            return Double.valueOf(String.valueOf(raw));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double numberOr(JSONObject object, String key, double fallback) {
        Double value = nullableNumber(object, key);
        return value == null ? fallback : value;
    }

    private static Bridge bridgeFromId(String value) {
        String id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("dom-tito".equals(id)) return Bridge.DOM_TITO;
        if ("br-470".equals(id)) return Bridge.BR470;
        if ("ricardo-kanitz".equals(id)) return Bridge.RICARDO_KANITZ;
        return null;
    }

    private static String newer(String first, String second) {
        return second != null && !second.isEmpty() ? second : first;
    }

    private static String isoUtc(long millis) {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return format.format(new java.util.Date(millis));
    }

    private static Exception unwrap(Exception error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause instanceof Exception ? (Exception) cause : error;
    }
}
