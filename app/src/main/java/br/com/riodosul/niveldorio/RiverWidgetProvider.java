package br.com.riodosul.niveldorio;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RiverWidgetProvider extends AppWidgetProvider {
    public static final String ACTION_NEXT = "br.com.riodosul.niveldorio.NEXT_BRIDGE";
    public static final String ACTION_REFRESH = "br.com.riodosul.niveldorio.REFRESH_WIDGET";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Locale BR = new Locale("pt", "BR");

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) updateAsync(context, manager, id);
    }

    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (ACTION_NEXT.equals(action)) {
            // Trocar de ponte não precisa de rede: a última consulta já tem as três.
            AppPrefs.nextBridge(context);
            AppWidgetManager m = AppWidgetManager.getInstance(context);
            int[] ids = m.getAppWidgetIds(new ComponentName(context, RiverWidgetProvider.class));
            boolean hasCache = AppCache.load(context) != null;
            for (int id : ids) {
                if (hasCache) renderFromCache(context, m, id);
                else updateAsync(context, m, id);
            }
        } else if (ACTION_REFRESH.equals(action)) {
            refreshAll(context);
        }
    }

    private void refreshAll(Context context) {
        AppWidgetManager m = AppWidgetManager.getInstance(context);
        int[] ids = m.getAppWidgetIds(new ComponentName(context, RiverWidgetProvider.class));
        for (int id : ids) updateAsync(context, m, id);
    }

    /** Consulta a rede e redesenha. */
    public static void updateAsync(Context context, AppWidgetManager manager, int id) {
        final Context app = context.getApplicationContext();
        showLoading(app, manager, id);
        EXECUTOR.execute(() -> {
            try {
                Snapshot s = DataRepository.fetchOrCache(app);
                int visits = DailyCounter.update(app);
                render(app, manager, id, s, visits);
            } catch (Throwable t) {
                try { render(app, manager, id, AppCache.load(app), -1); } catch (Throwable ignored) {}
            }
        });
    }

    /** Redesenha com a última consulta guardada, sem ir à rede (usado quando o app acabou de atualizar). */
    public static void renderFromCache(Context context, AppWidgetManager manager, int id) {
        Context app = context.getApplicationContext();
        try {
            String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
            int visits = AppPrefs.prefs(app).getInt("counter_value_" + day, -1);
            render(app, manager, id, AppCache.load(app), visits);
        } catch (RuntimeException ignored) {
            // O widget fica como estava.
        }
    }

    private static void showLoading(Context c, AppWidgetManager m, int id) {
        RemoteViews rv = baseViews(c);
        rv.setTextViewText(R.id.widget_updated, "Atualizando…");
        m.partiallyUpdateAppWidget(id, rv);
    }

    private static RemoteViews baseViews(Context c) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_river);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

        Intent next = new Intent(c, RiverWidgetProvider.class).setAction(ACTION_NEXT);
        PendingIntent piNext = PendingIntent.getBroadcast(c, 100, next, flags);
        rv.setOnClickPendingIntent(R.id.widget_next, piNext);
        rv.setOnClickPendingIntent(R.id.widget_bridge, piNext);

        Intent refresh = new Intent(c, RiverWidgetProvider.class).setAction(ACTION_REFRESH);
        rv.setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(c, 101, refresh, flags));

        Intent open = new Intent(c, MainActivity.class);
        PendingIntent piOpen = PendingIntent.getActivity(c, 102, open, flags);
        rv.setOnClickPendingIntent(R.id.widget_title, piOpen);
        rv.setOnClickPendingIntent(R.id.widget_chart, piOpen);
        return rv;
    }

    private static void render(Context c, AppWidgetManager m, int id, Snapshot s, int count) {
        RemoteViews rv = baseViews(c);
        Bridge b = AppPrefs.getSelectedBridge(c);
        RiverReading r = s == null ? null : s.rivers.get(b);
        Forecast f = s == null ? null : s.forecasts.get(b);
        List<Sample> samples = s == null ? null : s.riverHistory.get(b);

        rv.setTextViewText(R.id.widget_bridge, "📍 " + b.displayName);

        if (r == null) {
            rv.setTextViewText(R.id.widget_level, "--,-- m");
            rv.setTextViewText(R.id.widget_status, "Sem dados");
            rv.setTextViewText(R.id.widget_trend, "→");
        } else {
            rv.setTextViewText(R.id.widget_level, String.format(BR, "%.2f m", r.levelMeters));
            rv.setTextViewText(R.id.widget_status, r.cotas.situacao(r.levelMeters));
            String trend = f != null && f.available
                    ? String.format(BR, "%s em 1h: %.2f m", f.arrow(), f.predictedLevel)
                    : HistoryStore.trend(samples);
            rv.setTextViewText(R.id.widget_trend, trend);
        }

        rv.setImageViewBitmap(R.id.widget_chart,
                ChartRenderer.render(samples, r == null ? null : r.cotas, f, 600, 170));

        String taio = s != null && s.taio != null
                ? String.format(BR, "Taió %.1f%% • %d/%d", s.taio.capacityPercent, s.taio.gatesOpen, s.taio.gatesTotal)
                : "Taió --";
        String itup = s != null && s.ituporanga != null
                ? String.format(BR, "Ituporanga %.1f%% • %d/%d", s.ituporanga.capacityPercent, s.ituporanga.gatesOpen, s.ituporanga.gatesTotal)
                : "Ituporanga --";
        rv.setTextViewText(R.id.widget_dams, "Barragens: " + taio + "  •  " + itup);

        if (s != null && s.fetchedAt > 0) {
            String stamp = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(s.fetchedAt));
            boolean stale = s.fromCache && s.errorMessage != null;
            rv.setTextViewText(R.id.widget_updated, (stale ? "⚠ Último dado " : "Atualizado ") + stamp);
        } else if (s != null && s.errorMessage != null && !s.errorMessage.isEmpty()) {
            rv.setTextViewText(R.id.widget_updated, "API indisponível • toque para atualizar");
        } else {
            rv.setTextViewText(R.id.widget_updated, "Dados indisponíveis");
        }

        if (count >= 0) {
            String n = NumberFormat.getIntegerInstance(BR).format(count);
            rv.setTextViewText(R.id.widget_visits, "👥 " + n + (count == 1 ? " acesso hoje" : " acessos hoje"));
        } else {
            rv.setTextViewText(R.id.widget_visits, "👥 contador indisponível");
        }

        m.updateAppWidget(id, rv);
    }
}
