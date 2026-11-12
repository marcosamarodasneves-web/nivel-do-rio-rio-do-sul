package br.com.riodosul.niveldorio;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Registro das previsões de 1, 2 e 3 horas e conferência com o nível real.
 *
 * O banco definitivo é o Supabase; esta tabela local existe para a tela de conferência funcionar
 * sem internet e para guardar a fila do que ainda não foi enviado. Cada previsão é gravada com o
 * horário-alvo e o horizonte; quando a série de 24 h da ponte traz o nível real daquele horário, a
 * linha é conferida — dentro da margem, perto ou fora.
 */
public final class ForecastStore {
    public static final int OUTCOME_PENDING = 0;
    public static final int OUTCOME_HIT = 1;
    public static final int OUTCOME_NEAR = 2;
    public static final int OUTCOME_MISS = 3;
    public static final int OUTCOME_NO_DATA = 4;

    public static final int HORIZONS = 3;

    /** Tolerância mínima de acerto, em cm: abaixo disso a conta seria exigente demais. */
    public static final double TOL_MIN_CM = 5.0;
    /** Janela de compatibilidade entre a hora prevista e a leitura do rio (± 20 min). */
    private static final long MATCH_WINDOW_MS = 20L * 60L * 1000L;
    /** Depois de 6 h sem conseguir conferir, a previsão fica "sem medição". */
    private static final long GIVE_UP_MS = 6L * 60L * 60L * 1000L;
    /** Guarda cerca de um trimestre de previsões neste aparelho. */
    private static final long KEEP_MS = 92L * 24L * 60L * 60L * 1000L;
    /** Na grade, uma linha a cada ~hora (as atualizações de 5 em 5 min ficam de fora). */
    private static final long ISSUE_GAP_MS = 50L * 60L * 1000L;

    private ForecastStore() {}

    /** Uma previsão (um horizonte) e a sua conferência. */
    public static final class Row {
        public String bridge;
        public int horizon = 1;
        public String stationId;
        public String deviceBucket;
        public long baseTime;
        public long targetTime;
        public double currentLevel;
        public double predictedLevel;
        public double bandCm;
        public double toleranceCm = TOL_MIN_CM;
        public int confidence;
        public double actualLevel = Double.NaN;
        public long actualTime;
        public double errorCm = Double.NaN;
        public int outcome;
    }

    /** As três previsões feitas no mesmo momento (uma linha da grade). */
    public static final class Issue {
        public long baseTime;
        public double currentLevel = Double.NaN;
        /** Índice 0 = +1 h, 1 = +2 h, 2 = +3 h; null quando aquele horizonte não foi gravado. */
        public final Row[] rows = new Row[HORIZONS];
    }

    /** Resumo do desempenho de um horizonte. */
    public static final class Stats {
        public int horizon = 1;
        public int total;
        public int hit;
        public int near;
        public int miss;
        public int pending;
        public int noData;
        public double meanAbsErrorCm = Double.NaN;

        public int resolved() {
            return hit + near + miss;
        }

        /** % das previsões já conferidas que ficaram dentro da margem. */
        public int hitRatePercent() {
            int r = resolved();
            return r == 0 ? 0 : (int) Math.round(100.0 * hit / r);
        }
    }

    private static final String CREATE = "CREATE TABLE IF NOT EXISTS forecast_history ("
            + "bridge TEXT NOT NULL,"
            + "horizon INTEGER NOT NULL DEFAULT 1,"
            + "target_time INTEGER NOT NULL,"
            + "base_time INTEGER DEFAULT 0,"
            + "station_id TEXT DEFAULT '',"
            + "device_bucket TEXT DEFAULT '',"
            + "current_level REAL DEFAULT 0,"
            + "predicted_level REAL DEFAULT 0,"
            + "band_cm REAL DEFAULT 0,"
            + "tolerance_cm REAL DEFAULT 5,"
            + "confidence INTEGER DEFAULT 0,"
            + "actual_level REAL DEFAULT -1,"
            + "actual_time INTEGER DEFAULT 0,"
            + "error_cm REAL DEFAULT 0,"
            + "outcome INTEGER DEFAULT 0,"
            + "saved_at INTEGER DEFAULT 0,"
            + "synced_at INTEGER DEFAULT 0,"
            + "UNIQUE(bridge, target_time, horizon))";

    private static final class Db extends SQLiteOpenHelper {
        private static Db instance;

        private Db(Context c) {
            super(c, "nivel_do_rio.db", null, 2);
        }

        static synchronized Db get(Context c) {
            if (instance == null) instance = new Db(c.getApplicationContext());
            return instance;
        }

        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL(CREATE);
        }

        /** Versão 1 → 2: a chave única passa a incluir o horizonte; as linhas antigas viram "+1 h". */
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE forecast_history RENAME TO forecast_history_v1");
                db.execSQL(CREATE);
                db.execSQL("INSERT OR IGNORE INTO forecast_history (bridge, horizon, target_time, base_time, station_id, "
                        + "device_bucket, current_level, predicted_level, band_cm, tolerance_cm, confidence, actual_level, "
                        + "actual_time, error_cm, outcome, saved_at, synced_at) "
                        + "SELECT bridge, 1, target_time, base_time, station_id, device_bucket, current_level, "
                        + "predicted_level, band_cm, tolerance_cm, confidence, actual_level, actual_time, error_cm, "
                        + "outcome, saved_at, synced_at FROM forecast_history_v1");
                db.execSQL("DROP TABLE forecast_history_v1");
            }
        }
    }

    /** Identificação anônima e estável deste aparelho (para agrupar linhas no Supabase). */
    public static synchronized String deviceBucket(Context c) {
        String id = AppPrefs.prefs(c).getString("device_bucket", "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString().substring(0, 8);
            AppPrefs.prefs(c).edit().putString("device_bucket", id).apply();
        }
        return id;
    }

    // ------------------------------------------------------------- gravar

    /** Grava as previsões recém-calculadas e confere as pendentes com o histórico da ponte. */
    public static void recordAndResolve(Context c, Snapshot s) {
        if (s == null) return;
        long now = s.fetchedAt > 0 ? s.fetchedAt : System.currentTimeMillis();
        for (Bridge b : Bridge.values()) {
            Forecast f = s.forecasts.get(b);
            RiverReading r = s.rivers.get(b);
            if (f != null) record(c, f, r == null ? null : r.stationId);
            resolve(c, b, s.riverHistory.get(b), now);
        }
        prune(c);
    }

    /** Guarda os três horizontes de uma previsão. A primeira que chega para um horário-alvo fica valendo. */
    public static void record(Context c, Forecast f, String stationId) {
        if (f == null || !f.available || f.bridge == null) return;
        try {
            SQLiteDatabase db = Db.get(c).getWritableDatabase();
            String device = deviceBucket(c);
            long savedAt = System.currentTimeMillis();
            List<Forecast.Horizon> list = f.horizons;
            if (list.isEmpty()) {
                // Cache de versão anterior: só existe o +1 h.
                Forecast.Horizon h = new Forecast.Horizon();
                h.hours = 1;
                h.targetTime = f.targetTime;
                h.predictedLevel = f.predictedLevel;
                h.bandCm = f.bandCm;
                h.confidence = f.confidence;
                list = new ArrayList<>();
                list.add(h);
            }
            for (Forecast.Horizon h : list) {
                if (h.hours < 1 || h.hours > HORIZONS) continue;
                if (h.targetTime <= 0 || Double.isNaN(h.predictedLevel)) continue;
                ContentValues cv = new ContentValues();
                cv.put("bridge", f.bridge.name());
                cv.put("horizon", h.hours);
                cv.put("target_time", h.targetTime);
                cv.put("base_time", f.baseTime);
                cv.put("station_id", stationId == null ? "" : stationId);
                cv.put("device_bucket", device);
                cv.put("current_level", f.currentLevel);
                cv.put("predicted_level", h.predictedLevel);
                cv.put("band_cm", h.bandCm);
                cv.put("tolerance_cm", Math.max(TOL_MIN_CM, h.bandCm));
                cv.put("confidence", h.confidence);
                cv.put("outcome", OUTCOME_PENDING);
                cv.put("saved_at", savedAt);
                cv.put("synced_at", 0L);
                db.insertWithOnConflict("forecast_history", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
            }
        } catch (Exception ignored) {
            // Conferir previsão é registro histórico: nunca pode derrubar a atualização.
        }
    }

    /** Preenche o nível real das previsões cujo horário já passou. */
    public static void resolve(Context c, Bridge b, List<Sample> samples, long now) {
        if (b == null || samples == null || samples.isEmpty()) return;
        try {
            SQLiteDatabase db = Db.get(c).getWritableDatabase();
            Cursor cur = db.query("forecast_history",
                    new String[] { "target_time", "horizon", "predicted_level", "tolerance_cm" },
                    "bridge = ? AND outcome = ? AND target_time < ?",
                    new String[] { b.name(), String.valueOf(OUTCOME_PENDING), String.valueOf(now - MATCH_WINDOW_MS) },
                    null, null, "target_time ASC", "600");
            List<long[]> keys = new ArrayList<>();
            List<double[]> values = new ArrayList<>();
            try {
                while (cur.moveToNext()) {
                    keys.add(new long[] { cur.getLong(0), cur.getLong(1) });
                    values.add(new double[] { cur.getDouble(2), cur.getDouble(3) });
                }
            } finally {
                cur.close();
            }
            for (int i = 0; i < keys.size(); i++) {
                long target = keys.get(i)[0];
                String[] where = { b.name(), String.valueOf(target), String.valueOf(keys.get(i)[1]) };
                Sample match = nearest(samples, target);
                ContentValues cv = new ContentValues();
                if (match == null) {
                    if (now - target <= GIVE_UP_MS) continue;
                    cv.put("outcome", OUTCOME_NO_DATA);
                } else {
                    double errorCm = (values.get(i)[0] - match.value) * 100.0;
                    cv.put("actual_level", match.value);
                    cv.put("actual_time", match.timeMillis);
                    cv.put("error_cm", errorCm);
                    cv.put("outcome", classify(errorCm, values.get(i)[1]));
                }
                cv.put("synced_at", 0L);
                db.update("forecast_history", cv, "bridge = ? AND target_time = ? AND horizon = ?", where);
            }
        } catch (Exception ignored) {
            // O app continua funcionando mesmo se o banco local falhar.
        }
    }

    /** Aceita o que veio do Supabase (outra conferência, outro aparelho) sem perder o resto. */
    public static void mergeRemote(Context c, Row r) {
        if (r == null || r.bridge == null || r.targetTime <= 0) return;
        try {
            Db.get(c).getWritableDatabase().replace("forecast_history", null, values(r, System.currentTimeMillis()));
        } catch (Exception ignored) {}
    }

    private static ContentValues values(Row r, long syncedAt) {
        ContentValues cv = new ContentValues();
        cv.put("bridge", r.bridge);
        cv.put("horizon", Math.max(1, Math.min(HORIZONS, r.horizon)));
        cv.put("target_time", r.targetTime);
        cv.put("base_time", r.baseTime);
        cv.put("station_id", r.stationId == null ? "" : r.stationId);
        cv.put("device_bucket", r.deviceBucket == null ? "" : r.deviceBucket);
        cv.put("current_level", r.currentLevel);
        cv.put("predicted_level", r.predictedLevel);
        cv.put("band_cm", r.bandCm);
        cv.put("tolerance_cm", r.toleranceCm);
        cv.put("confidence", r.confidence);
        cv.put("actual_level", Double.isNaN(r.actualLevel) ? -1 : r.actualLevel);
        cv.put("actual_time", r.actualTime);
        cv.put("error_cm", Double.isNaN(r.errorCm) ? 0 : r.errorCm);
        cv.put("outcome", r.outcome);
        cv.put("saved_at", syncedAt);
        cv.put("synced_at", syncedAt);
        return cv;
    }

    private static final String[] COLUMNS = {
            "bridge", "target_time", "base_time", "station_id", "device_bucket", "current_level",
            "predicted_level", "band_cm", "tolerance_cm", "confidence", "actual_level",
            "actual_time", "error_cm", "outcome", "horizon"
    };

    // ------------------------------------------------------------- consultar

    /**
     * Previsões feitas para a ponte, agrupadas pelo momento em que foram calculadas (as três juntas),
     * da mais nova para a mais antiga, com no máximo uma linha a cada ~50 min.
     */
    public static List<Issue> recentIssues(Context c, Bridge b, int limit) {
        List<Issue> out = new ArrayList<>();
        if (b == null) return out;
        try {
            Cursor cur = Db.get(c).getReadableDatabase().query("forecast_history", COLUMNS,
                    "bridge = ? AND base_time > 0", new String[] { b.name() }, null, null,
                    "base_time DESC, horizon ASC", "3000");
            Issue current = null;
            long lastKept = Long.MAX_VALUE;
            try {
                while (cur.moveToNext()) {
                    Row r = read(cur);
                    if (current != null && r.baseTime == current.baseTime) {
                        if (r.horizon >= 1 && r.horizon <= HORIZONS) current.rows[r.horizon - 1] = r;
                        continue;
                    }
                    if (out.size() >= limit) break;
                    Issue issue = new Issue();
                    issue.baseTime = r.baseTime;
                    issue.currentLevel = r.currentLevel;
                    if (r.horizon >= 1 && r.horizon <= HORIZONS) issue.rows[r.horizon - 1] = r;
                    current = issue;
                    // Momentos muito próximos do último mostrado são lidos, mas não entram na grade.
                    if (lastKept - r.baseTime >= ISSUE_GAP_MS) {
                        out.add(issue);
                        lastKept = r.baseTime;
                    }
                }
            } finally {
                cur.close();
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Desempenho de um horizonte nos últimos dias. */
    public static Stats stats(Context c, Bridge b, int days, int horizon) {
        Stats st = new Stats();
        st.horizon = horizon;
        if (b == null) return st;
        long from = System.currentTimeMillis() - Math.max(1, days) * 24L * 60L * 60L * 1000L;
        try {
            Cursor cur = Db.get(c).getReadableDatabase().query("forecast_history",
                    new String[] { "outcome", "error_cm" }, "bridge = ? AND horizon = ? AND target_time >= ?",
                    new String[] { b.name(), String.valueOf(horizon), String.valueOf(from) }, null, null, null);
            double sum = 0;
            int counted = 0;
            try {
                while (cur.moveToNext()) {
                    st.total++;
                    int outcome = cur.getInt(0);
                    switch (outcome) {
                        case OUTCOME_HIT: st.hit++; break;
                        case OUTCOME_NEAR: st.near++; break;
                        case OUTCOME_MISS: st.miss++; break;
                        case OUTCOME_NO_DATA: st.noData++; break;
                        default: st.pending++; break;
                    }
                    if (outcome == OUTCOME_HIT || outcome == OUTCOME_NEAR || outcome == OUTCOME_MISS) {
                        sum += Math.abs(cur.getDouble(1));
                        counted++;
                    }
                }
            } finally {
                cur.close();
            }
            if (counted > 0) st.meanAbsErrorCm = sum / counted;
        } catch (Exception ignored) {}
        return st;
    }

    // ------------------------------------------------------------- fila de envio

    /**
     * Linhas locais que ainda não foram aceitas pelo Supabase.
     * {@code onlyFirstHour}: quando a tabela remota ainda não tem a coluna do horizonte.
     */
    public static List<Row> pendingSync(Context c, int limit, boolean onlyFirstHour) {
        List<Row> out = new ArrayList<>();
        try {
            Cursor cur = Db.get(c).getReadableDatabase().query("forecast_history", COLUMNS,
                    onlyFirstHour ? "synced_at = 0 AND horizon = 1" : "synced_at = 0", null, null, null,
                    "target_time ASC", String.valueOf(Math.max(1, limit)));
            try {
                while (cur.moveToNext()) out.add(read(cur));
            } finally {
                cur.close();
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void markSynced(Context c, List<Row> rows) {
        if (rows == null || rows.isEmpty()) return;
        long now = System.currentTimeMillis();
        try {
            SQLiteDatabase db = Db.get(c).getWritableDatabase();
            for (Row r : rows) {
                if (r == null) continue;
                ContentValues cv = new ContentValues();
                cv.put("synced_at", now);
                db.update("forecast_history", cv, "bridge = ? AND target_time = ? AND horizon = ?",
                        new String[] { r.bridge, String.valueOf(r.targetTime), String.valueOf(r.horizon) });
            }
        } catch (Exception ignored) {}
    }

    private static void prune(Context c) {
        try {
            Db.get(c).getWritableDatabase().execSQL("DELETE FROM forecast_history WHERE target_time < "
                    + (System.currentTimeMillis() - KEEP_MS) + " AND outcome <> 0");
        } catch (Exception ignored) {}
    }

    private static Row read(Cursor cur) {
        Row r = new Row();
        r.bridge = cur.getString(0);
        r.targetTime = cur.getLong(1);
        r.baseTime = cur.getLong(2);
        r.stationId = cur.getString(3);
        r.deviceBucket = cur.getString(4);
        r.currentLevel = cur.getDouble(5);
        r.predictedLevel = cur.getDouble(6);
        r.bandCm = cur.getDouble(7);
        r.toleranceCm = cur.getDouble(8);
        r.confidence = cur.getInt(9);
        double actual = cur.getDouble(10);
        r.actualLevel = actual < 0 ? Double.NaN : actual;
        r.actualTime = cur.getLong(11);
        r.errorCm = cur.getDouble(12);
        r.outcome = cur.getInt(13);
        r.horizon = cur.getInt(14);
        return r;
    }

    /** Leitura mais próxima do horário previsto, desde que caiba na janela de ± 20 min. */
    private static Sample nearest(List<Sample> samples, long target) {
        Sample best = null;
        long bestGap = Long.MAX_VALUE;
        for (Sample s : samples) {
            if (s == null || s.timeMillis <= 0 || Double.isNaN(s.value) || s.value <= 0) continue;
            long gap = Math.abs(s.timeMillis - target);
            if (gap < bestGap) {
                bestGap = gap;
                best = s;
            }
        }
        return bestGap <= MATCH_WINDOW_MS ? best : null;
    }

    public static int classify(double errorCm, double toleranceCm) {
        double abs = Math.abs(errorCm);
        double tol = Math.max(TOL_MIN_CM, toleranceCm);
        if (abs <= tol) return OUTCOME_HIT;
        if (abs <= 2 * tol) return OUTCOME_NEAR;
        return OUTCOME_MISS;
    }

    // ------------------------------------------------------------- rótulos

    public static String outcomeText(int outcome) {
        switch (outcome) {
            case OUTCOME_HIT: return "dentro";
            case OUTCOME_NEAR: return "quase";
            case OUTCOME_MISS: return "fora";
            case OUTCOME_NO_DATA: return "sem_medicao";
            default: return "pendente";
        }
    }

    public static int outcomeFromText(String value) {
        if (value == null) return OUTCOME_PENDING;
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.startsWith("dentro") || v.startsWith("hit")) return OUTCOME_HIT;
        if (v.startsWith("quase") || v.startsWith("near")) return OUTCOME_NEAR;
        if (v.startsWith("fora") || v.startsWith("miss")) return OUTCOME_MISS;
        if (v.startsWith("sem")) return OUTCOME_NO_DATA;
        return OUTCOME_PENDING;
    }

    public static String outcomeLabel(int outcome) {
        switch (outcome) {
            case OUTCOME_HIT: return "Dentro da margem";
            case OUTCOME_NEAR: return "Perto";
            case OUTCOME_MISS: return "Fora da margem";
            case OUTCOME_NO_DATA: return "Sem medição";
            default: return "Aguardando";
        }
    }
}
