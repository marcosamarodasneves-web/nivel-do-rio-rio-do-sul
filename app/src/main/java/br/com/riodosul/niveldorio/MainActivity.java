package br.com.riodosul.niveldorio;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
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
    private TextView visits;
    private TextView statusLine;
    private RiverStatusCardView riverCard;
    private ForecastCardView forecastCard;
    private RiverChartView riverChart;
    private DamSummaryView dams;
    private PullRefreshLayout pullRefresh;
    private volatile boolean spinnerReady;
    private volatile boolean refreshing;
    private volatile boolean destroyed;
    /** Última consulta exibida: trocar de ponte redesenha a partir dela, com histórico e previsão. */
    private Snapshot current;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applyStatusBarInset();

        pullRefresh = findViewById(R.id.main_root);
        spinner = findViewById(R.id.bridge_spinner);
        riverCard = findViewById(R.id.main_river_card);
        forecastCard = findViewById(R.id.main_forecast_card);
        riverChart = findViewById(R.id.main_river_chart);
        dams = findViewById(R.id.main_dams);
        visits = findViewById(R.id.main_visits);
        statusLine = findViewById(R.id.main_status);
        pullRefresh.setOnRefreshListener(() -> refreshData(true));

        forecastCard.setOnClickListener(v -> {
            Intent i = new Intent(MainActivity.this, ForecastActivity.class);
            i.putExtra(ForecastActivity.EXTRA_BRIDGE, AppPrefs.getSelectedBridge(MainActivity.this).name());
            startActivity(i);
        });

        // Dois toques sobre o card de uma barragem abrem a tendência de 24 h com a chuva do período.
        dams.setOnDamDoubleTapListener(new DamSummaryView.OnDamDoubleTapListener() {
            @Override public void onDamDoubleTap(String damKey) {
                Intent i = new Intent(MainActivity.this, DamActivity.class);
                i.putExtra(DamActivity.EXTRA_DAM, damKey);
                startActivity(i);
            }
        });

        String[] labels = new String[Bridge.values().length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = "📍 " + Bridge.values()[i].label();
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        spinner.setAdapter(adapter);
        spinner.setSelection(AppPrefs.getSelectedBridge(this).ordinal());
        spinnerReady = true;

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!spinnerReady) return;
                Bridge b = Bridge.values()[position];
                if (b == AppPrefs.getSelectedBridge(MainActivity.this) && current != null) return;
                AppPrefs.setSelectedBridge(MainActivity.this, b);
                render(current != null ? current : AppCache.load(MainActivity.this));
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
            v.setPadding(left, top + insets.getSystemWindowInsetTop(), right, bottom + insets.getSystemWindowInsetBottom());
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
        if (refreshing || destroyed) {
            if (fromPull && !refreshing) pullRefresh.setRefreshing(false);
            return;
        }
        refreshing = true;

        try {
            executor.execute(() -> {
                Snapshot s;
                int count = -1;
                try {
                    s = DataRepository.fetchOrCache(MainActivity.this);
                    count = DailyCounter.update(MainActivity.this);
                } catch (Throwable t) {
                    // Nenhuma falha de rede ou de dados pode fechar o app.
                    s = AppCache.load(MainActivity.this);
                }
                final Snapshot result = s;
                final int visitsToday = count;
                runOnUiThread(() -> {
                    refreshing = false;
                    if (destroyed) return;
                    pullRefresh.setRefreshing(false);
                    render(result);
                    if (visitsToday >= 0) visits.setText(formatAccessCount(visitsToday));
                    refreshWidgets();
                });
            });
        } catch (RuntimeException rejected) {
            refreshing = false;
            pullRefresh.setRefreshing(false);
        }
    }

    private String formatAccessCount(int count) {
        String n = NumberFormat.getIntegerInstance(new Locale("pt", "BR")).format(count);
        return "👥 " + n + (count == 1 ? " acesso hoje" : " acessos hoje");
    }

    private void render(Snapshot s) {
        if (s != null) current = s;
        Bridge b = AppPrefs.getSelectedBridge(this);
        RiverReading r = s == null ? null : s.rivers.get(b);
        Forecast f = s == null ? null : s.forecasts.get(b);

        riverCard.setData(b, r);
        forecastCard.setForecast(f, s == null || (refreshing && f == null));
        riverChart.setData(s == null ? null : s.riverHistory.get(b), r == null ? null : r.cotas, f,
                r == null || r.levelMeters <= 0 ? Double.NaN : r.levelMeters);
        dams.setData(s == null ? null : s.taio, s == null ? null : s.ituporanga);

        if (s == null) {
            statusLine.setText("Buscando dados da Defesa Civil…");
        } else if (s.fetchedAt <= 0) {
            statusLine.setText("⚠ Sem conexão com a Defesa Civil. Puxe a tela para baixo para tentar de novo.");
        } else {
            String stamp = new SimpleDateFormat("dd/MM HH:mm", new Locale("pt", "BR")).format(new Date(s.fetchedAt));
            if (s.fromCache && s.errorMessage != null) {
                statusLine.setText("⚠ Sem conexão agora — mostrando os dados de " + stamp + ".");
            } else if (r == null) {
                statusLine.setText("⚠ A Defesa Civil não enviou leitura desta ponte agora (consulta de " + stamp + ").");
            } else {
                statusLine.setText("Atualizado em " + stamp + " • atualiza sozinho a cada 5 min");
            }
        }
    }

    private void refreshWidgets() {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(this);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(this, RiverWidgetProvider.class));
            for (int id : ids) {
                RiverWidgetProvider.renderFromCache(this, mgr, id);
            }
        } catch (RuntimeException ignored) {
            // Launcher sem suporte a widgets.
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
        destroyed = true;
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable);
        executor.shutdownNow();
        super.onDestroy();
    }
}
