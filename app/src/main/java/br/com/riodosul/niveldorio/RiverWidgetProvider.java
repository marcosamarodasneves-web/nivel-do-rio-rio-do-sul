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

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) updateAsync(context, manager, id);
    }

    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();

        if (ACTION_NEXT.equals(action)) {
            AppPrefs.nextBridge(context);
            refreshAll(context);
        } else if (ACTION_REFRESH.equals(action)) {
            refreshAll(context);
        }
    }

    private void refreshAll(Context context) {
        AppWidgetManager m = AppWidgetManager.getInstance(context);
        int[] ids = m.getAppWidgetIds(
                new ComponentName(context, RiverWidgetProvider.class)
        );
        for (int id : ids) updateAsync(context, m, id);
    }

    public static void updateAsync(Context context, AppWidgetManager manager, int id) {
        Context app = context.getApplicationContext();
        showLoading(app, manager, id);

        EXECUTOR.execute(() -> {
            Snapshot s = DataRepository.fetchOrCache(app);
            int visits = DailyCounter.update(app);
            render(app, manager, id, s, visits);
        });
    }

    private static void showLoading(Context c, AppWidgetManager m, int id) {
        RemoteViews rv = baseViews(c);
        rv.setTextViewText(R.id.widget_updated, "Atualizando…");
        m.updateAppWidget(id, rv);
    }

    private static RemoteViews baseViews(Context c) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_river);

        Intent next = new Intent(c, RiverWidgetProvider.class).setAction(ACTION_NEXT);
        PendingIntent piNext = PendingIntent.getBroadcast(
                c, 100, next,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        rv.setOnClickPendingIntent(R.id.widget_next, piNext);
        rv.setOnClickPendingIntent(R.id.widget_bridge, piNext);

        Intent refresh = new Intent(c, RiverWidgetProvider.class).setAction(ACTION_REFRESH);
        PendingIntent piRefresh = PendingIntent.getBroadcast(
                c, 101, refresh,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        rv.setOnClickPendingIntent(R.id.widget_refresh, piRefresh);

        Intent open = new Intent(c, MainActivity.class);
        PendingIntent piOpen = PendingIntent.getActivity(
                c, 102, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        rv.setOnClickPendingIntent(R.id.widget_title, piOpen);
        rv.setOnClickPendingIntent(R.id.widget_chart, piOpen);

        return rv;
    }

    private static void render(Context c, AppWidgetManager m, int id, Snapshot s, int count) {
        RemoteViews rv = baseViews(c);
        Bridge b = AppPrefs.getSelectedBridge(c);
        RiverReading r = s == null ? null : s.rivers.get(b);

        rv.setTextViewText(R.id.widget_bridge, "📍 " + b.displayName);

        if (r == null) {
            rv.setTextViewText(R.id.widget_level, "--,-- m");
            rv.setTextViewText(R.id.widget_status, "Sem dados");
            rv.setTextViewText(R.id.widget_trend, "→");
        } else {
            rv.setTextViewText(
                    R.id.widget_level,
                    String.format(new Locale("pt", "BR"), "%.2f m", r.levelMeters)
            );
            rv.setTextViewText(R.id.widget_status, r.status);
            rv.setTextViewText(
                    R.id.widget_trend,
                    HistoryStore.trend(c, b, r.levelMeters)
            );
        }

        List<Sample> samples = HistoryStore.read(c, b);
        rv.setImageViewBitmap(
                R.id.widget_chart,
                ChartRenderer.render(samples, 600, 160)
        );

        String taio = s != null && s.taio != null
                ? String.format(new Locale("pt", "BR"), "Taió %.1f%%", s.taio.capacityPercent)
                : "Taió --";

        String itup = s != null && s.ituporanga != null
                ? String.format(new Locale("pt", "BR"), "Ituporanga %.1f%%", s.ituporanga.capacityPercent)
                : "Ituporanga --";

        rv.setTextViewText(
                R.id.widget_dams,
                "Barragens: " + taio + "  •  " + itup
        );

        if (s != null && s.fetchedAt > 0) {
            String stamp = new SimpleDateFormat(
                    "HH:mm",
                    Locale.getDefault()
            ).format(new Date(s.fetchedAt));

            rv.setTextViewText(
                    R.id.widget_updated,
                    (s.fromCache ? "⚠ Último dado " : "Atualizado ") + stamp
            );
        } else if (s != null && s.errorMessage != null && !s.errorMessage.isEmpty()) {
            rv.setTextViewText(R.id.widget_updated, "API indisponível • toque para atualizar");
        } else {
            rv.setTextViewText(R.id.widget_updated, "Dados indisponíveis");
        }

        if (count >= 0) {
            String n = NumberFormat.getIntegerInstance(
                    new Locale("pt", "BR")
            ).format(count);
            rv.setTextViewText(
                    R.id.widget_visits,
                    "👥 " + n + (count == 1 ? " acesso hoje" : " acessos hoje")
            );
        } else {
            rv.setTextViewText(R.id.widget_visits, "👥 contador indisponível");
        }

        m.updateAppWidget(id, rv);
    }
}
