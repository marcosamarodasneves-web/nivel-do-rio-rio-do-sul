package br.com.riodosul.niveldorio;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
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
    private TextView level, status, trend, updated, visits;
    private HydroChartView chart;
    private PullRefreshLayout pullRefresh;
    private volatile boolean spinnerReady;
    private volatile boolean refreshing;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applyStatusBarInset();

        pullRefresh = findViewById(R.id.main_root);
        spinner = findViewById(R.id.bridge_spinner);
        level = findViewById(R.id.main_level);
        status = findViewById(R.id.main_status);
        trend = findViewById(R.id.main_trend);
        updated = findViewById(R.id.main_updated);
        visits = findViewById(R.id.main_visits);
        chart = findViewById(R.id.main_chart);
        pullRefresh.setOnRefreshListener(() -> refreshData(true));

        String[] labels = new String[Bridge.values().length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = "📍 " + Bridge.values()[i].label();
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                labels
        );
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
        refreshData(false);
    }

    private void refreshData(boolean fromPull) {
        if (refreshing) return;
        refreshing = true;
        updated.setText("Atualizando dados…");

        executor.execute(() -> {
            Snapshot s = DataRepository.fetchOrCache(MainActivity.this);
            int count = DailyCounter.update(MainActivity.this);

            runOnUiThread(() -> {
                refreshing = false;
                if (fromPull) pullRefresh.setRefreshing(false);
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
            styleStatusChip("Sem dados");
            trend.setText("→");
        } else {
            level.setText(String.format(new Locale("pt", "BR"), "%.2f m", r.levelMeters));
            status.setText(r.status);
            styleStatusChip(r.status);
            trend.setText(HistoryStore.trend(this, b, r.levelMeters));
        }

        chart.setData(HistoryStore.read(this, b),
                r == null ? null : r.levelMeters,
                s == null ? null : s.taio,
                s == null ? null : s.ituporanga);

        if (s != null && s.fetchedAt > 0) {
            String stamp = new SimpleDateFormat(
                    "HH:mm",
                    Locale.getDefault()
            ).format(new Date(s.fetchedAt));

            String station = r == null ? "" : "\nLeitura da estação: " + r.readingTime;
            String base = (s.fromCache ? "⚠ Último dado salvo • " : "Atualizado às ") + stamp + station;

            if (s.errorMessage != null && !s.errorMessage.trim().isEmpty()) {
                base += "\nFalha na API: " + s.errorMessage;
            }
            updated.setText(base);
        } else {
            String msg = "Dados indisponíveis";
            if (s != null && s.errorMessage != null && !s.errorMessage.trim().isEmpty()) {
                msg += "\nFalha na API: " + s.errorMessage;
            }
            msg += "\nNova tentativa automática em até 5 min";
            updated.setText(msg);
        }
    }

    private void styleStatusChip(String value) {
        String s = value == null ? "" : value.toLowerCase(new Locale("pt", "BR"));
        int fill;
        int foreground;
        if (s.contains("emerg")) {
            fill = Color.rgb(255, 226, 226); foreground = Color.rgb(177, 32, 32);
        } else if (s.contains("alert")) {
            fill = Color.rgb(255, 235, 213); foreground = Color.rgb(184, 87, 13);
        } else if (s.contains("aten")) {
            fill = Color.rgb(255, 247, 194); foreground = Color.rgb(133, 100, 0);
        } else {
            fill = Color.rgb(228, 247, 236); foreground = Color.rgb(20, 124, 77);
        }
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(18 * getResources().getDisplayMetrics().density);
        status.setBackground(background);
        status.setTextColor(foreground);
    }

    private void refreshWidgets() {
        AppWidgetManager mgr = AppWidgetManager.getInstance(this);
        int[] ids = mgr.getAppWidgetIds(
                new ComponentName(this, RiverWidgetProvider.class)
        );
        for (int id : ids) {
            RiverWidgetProvider.updateAsync(this, mgr, id);
        }
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
