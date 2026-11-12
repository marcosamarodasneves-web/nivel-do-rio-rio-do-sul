package br.com.riodosul.niveldorio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Vai ao Supabase pela API REST (PostgREST) mantendo o princípio do app: só
 * {@link HttpURLConnection}, sem bibliotecas de terceiros.
 *
 * Tabela: {@code public.river_forecasts} (veja supabase/river_forecasts.sql).
 * Credenciais: {@code BuildConfig.SUPABASE_URL} e {@code BuildConfig.SUPABASE_ANON_KEY}.
 * Sem elas o app segue gravando apenas no espelho local, na fila para subir depois.
 */
public final class SupabaseForecast {
    private static final Locale BR = new Locale("pt", "BR");
    private static final String TABLE = "river_forecasts";
    private static final String SELECT = "bridge,target_time,base_time,station_id,device_bucket,"
            + "current_level,predicted_level,band_cm,tolerance_cm,confidence,actual_level,"
            + "actual_time,error_cm,outcome";

    private static final String PREF_SYNCED_AT = "sb_synced_at";
    private static final String PREF_ERROR = "sb_last_error";
    private static final String PREF_REMOTE_TOTAL = "sb_remote_total";
    /** true enquanto a tabela remota ainda não tem a coluna horizon_h. */
    private static final String PREF_LEGACY = "sb_legacy_schema";

    private SupabaseForecast() {}

    public static boolean enabled() {
        String url = BuildConfig.SUPABASE_URL;
        String key = BuildConfig.SUPABASE_ANON_KEY;
        return url != null && url.startsWith("http") && key != null && !key.trim().isEmpty();
    }

    private static String base() {
        String url = BuildConfig.SUPABASE_URL == null ? "" : BuildConfig.SUPABASE_URL.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    // ------------------------------------------------------------- sincronizar

    /**
     * Sobe o que ainda não foi enviado. Chamar sempre fora da thread de interface.
     *
     * A tabela ganhou a coluna {@code horizon_h} (previsões de 1, 2 e 3 h). Enquanto a migração
     * {@code supabase/migracao_1.2.0_horizontes.sql} não for executada no projeto, o envio cai para o
     * formato antigo: só a previsão de +1 h sobe e as de +2 h e +3 h esperam na fila deste aparelho.
     */
    public static boolean sync(Context c) {
        if (!enabled()) {
            remember(c, "não configurado", -1);
            return false;
        }
        try {
            String error = upload(c, false);
            if (error != null && missingHorizonColumn(error)) {
                AppPrefs.prefs(c).edit().putBoolean(PREF_LEGACY, true).apply();
                error = upload(c, true);
            } else if (error == null) {
                AppPrefs.prefs(c).edit().putBoolean(PREF_LEGACY, false).apply();
            }
            remember(c, error == null ? "" : error, -1);
            return error == null;
        } catch (Exception e) {
            remember(c, e.getClass().getSimpleName(), -1);
            return false;
        }
    }

    /** Envia a fila em lotes. Devolve null quando deu tudo certo ou a mensagem do primeiro erro. */
    private static String upload(Context c, boolean legacy) throws Exception {
        List<ForecastStore.Row> pending = ForecastStore.pendingSync(c, 120, legacy);
        String conflict = legacy ? "bridge,target_time" : "bridge,target_time,horizon_h";
        // O PostgREST só aceita um lote em que todos os objetos têm as mesmas chaves: as
        // previsões já conferidas vão juntas e as que ainda esperam o rio vão noutro lote.
        for (Batch batch : batches(pending, legacy)) {
            if (batch.json.length() == 0) continue;
            Response r = request("POST", base() + "/rest/v1/" + TABLE + "?on_conflict=" + conflict,
                    batch.json.toString(), "resolution=merge-duplicates,return=minimal");
            if (!r.ok()) return r.describe();
            ForecastStore.markSynced(c, batch.rows);
        }
        return null;
    }

    /** Erro do PostgREST quando a coluna horizon_h (ou a chave única nova) ainda não existe. */
    private static boolean missingHorizonColumn(String error) {
        String e = error.toLowerCase(Locale.ROOT);
        return e.contains("horizon_h") || e.contains("pgrst204") || e.contains("42703") || e.contains("42p10");
    }

    /** Busca as linhas mais recentes da ponte no Supabase e junta ao espelho local. */
    public static List<ForecastStore.Row> pull(Context c, Bridge b, int limit) {
        List<ForecastStore.Row> out = new ArrayList<>();
        if (!enabled() || b == null) return out;
        try {
            boolean legacy = c != null && AppPrefs.prefs(c).getBoolean(PREF_LEGACY, false);
            String url = base() + "/rest/v1/" + TABLE + "?select=" + SELECT + (legacy ? "" : ",horizon_h")
                    + "&bridge=eq." + b.name() + "&order=target_time.desc&limit=" + Math.max(1, limit);
            Response r = request("GET", url, null, "");
            if (!r.ok() && !legacy && missingHorizonColumn(r.describe())) {
                url = base() + "/rest/v1/" + TABLE + "?select=" + SELECT
                        + "&bridge=eq." + b.name() + "&order=target_time.desc&limit=" + Math.max(1, limit);
                r = request("GET", url, null, "");
            }
            if (!r.ok()) {
                remember(c, r.describe(), -1);
                return out;
            }
            JSONArray arr = jsonOf(r.body);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                ForecastStore.Row row = fromJson(b, o);
                out.add(row);
                if (c != null) ForecastStore.mergeRemote(c, row);
            }
            remember(c, "", -1);
        } catch (Exception ignored) {
            // Sem resposta do Supabase: a tela usa o espelho local.
        }
        return out;
    }

    // ------------------------------------------------------------- JSON

    /** Um lote de envio: o array JSON e as linhas que ele representa, na mesma ordem. */
    private static final class Batch {
        final JSONArray json = new JSONArray();
        final List<ForecastStore.Row> rows = new ArrayList<>();
    }

    /** [0] previsões sem nível real ainda; [1] previsões já conferidas. */
    private static Batch[] batches(List<ForecastStore.Row> rows, boolean legacy) throws Exception {
        Batch open = new Batch();
        Batch closed = new Batch();
        for (ForecastStore.Row r : rows) {
            if (r == null) continue;
            boolean withActual = hasActual(r);
            Batch to = withActual ? closed : open;
            JSONObject o = object(r, withActual);
            if (!legacy) o.put("horizon_h", Math.max(1, r.horizon));
            to.json.put(o);
            to.rows.add(r);
        }
        return new Batch[] { open, closed };
    }

    private static boolean hasActual(ForecastStore.Row r) {
        return !Double.isNaN(r.actualLevel) && r.actualLevel > 0;
    }

    private static JSONObject object(ForecastStore.Row r, boolean withActual) throws Exception {
        JSONObject o = new JSONObject();
        o.put("bridge", r.bridge);
        o.put("target_time", iso(r.targetTime));
        // Toda linha do lote precisa ter as mesmas chaves, então o horário de referência nunca some:
        // na falta dele usa-se o alvo menos o horizonte, que é como a previsão é calculada.
        o.put("base_time", iso(r.baseTime > 0 ? r.baseTime : r.targetTime - Math.max(1, r.horizon) * 3600000L));
        o.put("station_id", r.stationId == null ? "" : r.stationId);
        o.put("device_bucket", r.deviceBucket == null ? "" : r.deviceBucket);
        o.put("current_level", round(r.currentLevel));
        o.put("predicted_level", round(r.predictedLevel));
        o.put("band_cm", round(r.bandCm));
        o.put("tolerance_cm", round(r.toleranceCm));
        o.put("confidence", r.confidence);
        o.put("outcome", ForecastStore.outcomeText(r.outcome));
        if (withActual) {
            o.put("actual_level", round(r.actualLevel));
            o.put("actual_time", iso(r.actualTime > 0 ? r.actualTime : r.targetTime));
            o.put("error_cm", Double.isNaN(r.errorCm) ? 0 : round(r.errorCm));
        }
        return o;
    }

    private static ForecastStore.Row fromJson(Bridge b, JSONObject o) {
        ForecastStore.Row r = new ForecastStore.Row();
        r.bridge = o.optString("bridge", b == null ? "" : b.name());
        r.horizon = Math.max(1, o.optInt("horizon_h", 1));
        r.targetTime = parseIso(o.optString("target_time", ""));
        r.baseTime = parseIso(o.optString("base_time", ""));
        r.stationId = o.optString("station_id", "");
        r.deviceBucket = o.optString("device_bucket", "");
        r.currentLevel = o.optDouble("current_level", Double.NaN);
        r.predictedLevel = o.optDouble("predicted_level", Double.NaN);
        r.bandCm = o.optDouble("band_cm", 0);
        r.toleranceCm = o.optDouble("tolerance_cm", ForecastStore.TOL_MIN_CM);
        r.confidence = o.optInt("confidence", 0);
        r.actualLevel = o.optDouble("actual_level", Double.NaN);
        r.actualTime = parseIso(o.optString("actual_time", ""));
        r.errorCm = o.optDouble("error_cm", Double.NaN);
        r.outcome = ForecastStore.outcomeFromText(o.optString("outcome", "pendente"));
        return r;
    }

    private static JSONArray jsonOf(String body) throws Exception {
        Object parsed = new JSONTokener(body == null ? "[]" : body).nextValue();
        return parsed instanceof JSONArray ? (JSONArray) parsed : new JSONArray();
    }

    private static String iso(long millis) {
        if (millis <= 0) return null;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'.000Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(millis));
    }

    /** O Supabase devolve microsegundos e deslocamento "+00:00": mesmo formato que a Defesa Civil usa. */
    private static long parseIso(String value) {
        long t = DataRepository.parseTimestamp(value);
        return t > 0 ? t : 0L;
    }

    private static double round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        return Math.round(v * 10000.0) / 10000.0;
    }

    // ------------------------------------------------------------- HTTP

    private static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }

        boolean ok() {
            return code >= 200 && code < 300;
        }

        String describe() {
            if (ok()) return "";
            String b = body == null ? "" : body.trim();
            if (b.length() > 120) b = b.substring(0, 120);
            return "HTTP " + code + (b.isEmpty() ? "" : " " + b);
        }
    }

    private static Response request(String method, String url, String body, String prefer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(12000);
            c.setReadTimeout(15000);
            c.setRequestMethod(method);
            c.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY);
            c.setRequestProperty("Authorization", "Bearer " + BuildConfig.SUPABASE_ANON_KEY);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Accept-Encoding", "identity");
            if (prefer != null && !prefer.isEmpty()) c.setRequestProperty("Prefer", prefer);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(bytes.length);
                OutputStream os = c.getOutputStream();
                os.write(bytes);
                os.flush();
                os.close();
            }
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
            return new Response(code, read(in));
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------- estado para a tela

    private static void remember(Context c, String error, int total) {
        if (c == null) return;
        try {
            SharedPreferences.Editor e = AppPrefs.prefs(c).edit();
            e.putLong(PREF_SYNCED_AT, System.currentTimeMillis());
            e.putString(PREF_ERROR, error == null ? "" : error);
            if (total >= 0) e.putInt(PREF_REMOTE_TOTAL, total);
            e.apply();
        } catch (Exception ignored) {}
    }

    /** Uma linha curta, para o usuário saber se os dados estão subindo e se consolidando. */
    public static String statusLine(Context c) {
        if (!enabled()) return "Supabase ainda não configurado no app: as previsões ficam guardadas só neste aparelho, na fila para subir.";
        long at = AppPrefs.prefs(c).getLong(PREF_SYNCED_AT, 0L);
        String error = AppPrefs.prefs(c).getString(PREF_ERROR, "");
        int total = AppPrefs.prefs(c).getInt(PREF_REMOTE_TOTAL, -1);
        String when = at > 0 ? new SimpleDateFormat("dd/MM HH:mm", BR).format(new Date(at)) : "nunca";
        StringBuilder sb = new StringBuilder("☁ Supabase • último contato ").append(when);
        if (total >= 0) sb.append(String.format(BR, " • %d registros na tabela", total));
        if (error != null && !error.isEmpty()) sb.append(" • ⚠ ").append(error);
        return sb.toString();
    }
}
