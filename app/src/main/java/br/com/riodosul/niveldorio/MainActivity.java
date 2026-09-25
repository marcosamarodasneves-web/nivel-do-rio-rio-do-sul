package br.com.riodosul.niveldorio;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.AdapterView;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private Spinner spinner;
    private TextView level, status, trend, dams, updated, visits;
    private RiverChartView chart;
    private volatile boolean spinnerReady;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
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
        refresh.setOnClickListener(v -> refreshData());
        render(AppCache.load(this));
        refreshData();
    }

    private void refreshData() {
        updated.setText("Atualizando…");
        executor.execute(() -> {
            Snapshot s = DataRepository.fetchOrCache(MainActivity.this);
            int count = DailyCounter.update(MainActivity.this);
            runOnUiThread(() -> {
                render(s);
                if (count >= 0) visits.setText("👥 " + NumberFormat.getIntegerInstance(new Locale("pt", "BR")).format(count) + " acessos hoje");
                refreshWidgets();
            });
        });
    }

    private void render(Snapshot s) {
        Bridge b = AppPrefs.getSelectedBridge(this);
        if (s == null || s.rivers.get(b) == null) {
            level.setText("--,-- m");
            status.setText("Sem dados");
            trend.setText("→");
            chart.setSamples(HistoryStore.read(this, b));
            return;
        }
        RiverReading r = s.rivers.get(b);
        level.setText(String.format(new Locale("pt", "BR"), "%.2f m", r.levelMeters));
        status.setText(r.status);
        trend.setText(HistoryStore.trend(this, b, r.levelMeters));
        chart.setSamples(HistoryStore.read(this, b));
        StringBuilder ds = new StringBuilder();
        if (s.taio != null) ds.append(String.format(new Locale("pt", "BR"), "Taió  %.1f%%  •  %.2f m  •  %d/%d comportas abertas",
                s.taio.capacityPercent, s.taio.levelMeters, s.taio.gatesOpen, s.taio.gatesTotal));
        else ds.append("Taió  --");
        ds.append("\n");
        if (s.ituporanga != null) ds.append(String.format(new Locale("pt", "BR"), "Ituporanga  %.1f%%  •  %.2f m  •  %d/%d comportas abertas",
                s.ituporanga.capacityPercent, s.ituporanga.levelMeters, s.ituporanga.gatesOpen, s.ituporanga.gatesTotal));
        else ds.append("Ituporanga  --");
        dams.setText(ds.toString());
        String stamp = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(s.fetchedAt));
        updated.setText((s.fromCache ? "⚠ Último dado salvo • " : "Atualizado às ") + stamp + "\nLeitura da estação: " + r.readingTime);
    }

    private void refreshWidgets() {
        AppWidgetManager mgr = AppWidgetManager.getInstance(this);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(this, RiverWidgetProvider.class));
        for (int id : ids) RiverWidgetProvider.updateAsync(this, mgr, id);
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
