package br.com.riodosul.niveldorio;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final long AUTO_REFRESH_MS = 5L * 60L * 1000L;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler autoRefreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoRefreshRunnable = new Runnable() {
        @Override public void run() {
            refreshData();
            autoRefreshHandler.postDelayed(this, AUTO_REFRESH_MS);
        }
    };

    private Spinner spinner;
    private TextView level, status, trend, dams, updated, visits;
    private RiverChartView chart;
    private volatile boolean spinnerReady;
    private volatile boolean refreshing;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applyStatusBarInset();

        spinner = findViewById(R.id.bridge_spinner);
        level = findViewById(R.id.main_level);
        status = findViewById(R.id.main_status);
        trend = findViewById(R.id.main_trend);
        dams = findViewById(R.id.main_dams);
        updated = findViewById(R.id.main_updated);
        visits = findViewById(R.id.main_visits);
        chart = findViewById(R.id.main_chart);
        Button refresh = findViewById(R.id.refresh_button);

        String[] labels = new String[Bridge.values().length];
        for (int i = 0; i < labels.length; i++) labels[i] = "📍 " + Bridge.values()[i].label();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        spinner.setAdapter(adapter);
        spinner.setSelection(AppPrefs.getSelectedBridge(this).ordinal());
        spinnerReady = true;
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!spinnerReady) return;
                Bridge b = Bridge.values()[position];
                AppPrefs.setSelectedBridge(MainActivity.this, b);
                render(AppCache.load(MainActivity.this));
                refreshWidgets();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        refresh.setOnClickListener(v -> {
            refreshData();
            scheduleNextAutoRefresh();
        });

        render(AppCache.load(this));
        refreshData();
    }

    private void applyStatusBarInset() {
        View root = findViewById(R.id.main_root);
        if (root == null) return;
        final int left = root.getPaddingLeft();
        final int top = root.getPaddingTop();
        final int right = root.getPaddingRight();
        final int bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int statusBar = insets.getSystemWindowInsetTop();
            v.setPadding(left, top + statusBar, right, bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    private void scheduleNextAutoRefresh() {
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable);
        autoRefreshHandler.postDelayed(autoRefreshRunnable, AUTO_REFRESH_MS);
    }

    private void refreshData() {
        if (refreshing) return;
        refreshing = true;
        updated.setText("Atualizando dados…");
        executor.execute(() -> {
            Snapshot s = DataRepository.fetchOrCache(MainActivity.this);
            int count = DailyCounter.update(MainActivity.this);
            runOnUiThread(() -> {
                refreshing = false;
                render(s);
                if (count >= 0) visits.setText(formatAccessCount(count));
                refreshWidgets();
            });
        });
    }

    private String formatAccessCount(int count) {
        String n = NumberFormat.getIntegerInstance(new Locale("pt", "BR")).format(count);
        return "👥 " + n + (count == 1 ? " acesso hoje" : " acessos hoje");
    }

    private void render(Snapshot s) {
        Bridge b = AppPrefs.getSelectedBridge(this);
        RiverReading r = s == null ? null : s.rivers.get(b);

        if (r == null) {
            level.setText("--,-- m");
            status.setText("Sem dados");
            trend.setText("→");
        } else {
            level.setText(String.format(new Locale("pt", "BR"), "%.2f m", r.levelMeters));
            status.setText(r.status);
            trend.setText(HistoryStore.trend(this, b, r.levelMeters));
        }
        chart.setSamples(HistoryStore.read(this, b));

        StringBuilder ds = new StringBuilder();
        if (s != null && s.taio != null) {
            ds.append(String.format(new Locale("pt", "BR"), "Taió  %.1f%%  •  %.2f m  •  %d/%d comportas abertas",
                    s.taio.capacityPercent, s.taio.levelMeters, s.taio.gatesOpen, s.taio.gatesTotal));
        } else {
            ds.append("Taió  --");
        }
        ds.append("\n");
        if (s != null && s.ituporanga != null) {
            ds.append(String.format(new Locale("pt", "BR"), "Ituporanga  %.1f%%  •  %.2f m  •  %d/%d comportas abertas",
                    s.ituporanga.capacityPercent, s.ituporanga.levelMeters, s.ituporanga.gatesOpen, s.ituporanga.gatesTotal));
        } else {
            ds.append("Ituporanga  --");
        }
        dams.setText(ds.toString());

        if (s != null && s.fetchedAt > 0) {
            String stamp = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(s.fetchedAt));
            String station = r == null ? "" : "\nLeitura da estação: " + r.readingTime;
            updated.setText((s.fromCache ? "⚠ Último dado salvo • " : "Atualizado às ") + stamp + station);
        } else {
            updated.setText("Dados indisponíveis • nova tentativa automática em até 5 min");
        }
    }

    private void refreshWidgets() {
        AppWidgetManager mgr = AppWidgetManager.getInstance(this);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(this, RiverWidgetProvider.class));
        for (int id : ids) RiverWidgetProvider.updateAsync(this, mgr, id);
    }

    @Override protected void onResume() {
        super.onResume();
        scheduleNextAutoRefresh();
    }

    @Override protected void onPause() {
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable);
        super.onPause();
    }

    @Override protected void onDestroy() {
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable);
        executor.shutdownNow();
        super.onDestroy();
    }
}
