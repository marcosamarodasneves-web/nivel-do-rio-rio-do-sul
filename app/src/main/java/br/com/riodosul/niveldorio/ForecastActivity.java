package br.com.riodosul.niveldorio;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Memorial descritivo da previsão centralizada, com níveis e estatísticas fornecidos pelo servidor. */
public class ForecastActivity extends Activity {
    public static final String EXTRA_BRIDGE = "bridge";
    private static final Locale BR = new Locale("pt", "BR");
    private static final int INK = Color.rgb(28, 65, 94);
    private static final int BODY = Color.rgb(55, 65, 81);
    private static final int GREY = Color.rgb(102, 112, 133);

    private float density;
    private LinearLayout content;
    private Bridge bridge;
    private Snapshot snapshot;
    private boolean forecastRequestStarted;
    private boolean forecastRequestFinished;
    private String forecastRequestError = "";
    private boolean onlyCheckedHistory;
    private int historyLimit = ForecastHistorySelection.PAGE_SIZE;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            onlyCheckedHistory = savedInstanceState.getBoolean("history_only_checked", false);
            historyLimit = Math.max(ForecastHistorySelection.PAGE_SIZE,
                    savedInstanceState.getInt("history_limit", ForecastHistorySelection.PAGE_SIZE));
        }
        density = getResources().getDisplayMetrics().density;
        bridge = AppPrefs.getSelectedBridge(this);
        String name = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_BRIDGE);
        if (name != null) {
            try { bridge = Bridge.valueOf(name); } catch (IllegalArgumentException ignored) {}
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(243, 247, 252));
        scroll.setFitsSystemWindows(true);
        scroll.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(10), dp(16), dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        snapshot = AppCache.load(this);
        build(snapshot == null ? null : snapshot.forecasts.get(bridge));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("history_only_checked", onlyCheckedHistory);
        state.putInt("history_limit", historyLimit);
        super.onSaveInstanceState(state);
    }

    private int dp(float value) { return Math.round(value * density); }

    private void build(Forecast forecast) {
        content.removeAllViews();
        TextView back = label("‹  Voltar", 15, Color.rgb(21, 101, 192), true);
        back.setPadding(0, dp(10), dp(24), dp(10));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        content.addView(back);

        content.addView(label("Previsão centralizada", 21, INK, true));
        TextView subtitle = label("Memorial descritivo — " + bridge.label(), 13, GREY, false);
        subtitle.setPadding(0, dp(2), 0, dp(12));
        content.addView(subtitle);

        List<Sample> samples = snapshot == null ? null : snapshot.riverHistory.get(bridge);
        DamReading taio = snapshot == null ? null : snapshot.taio;
        DamReading itup = snapshot == null ? null : snapshot.ituporanga;
        MemorialAnimView hero = anim(MemorialAnimView.HERO, forecast, samples, taio, itup);
        if (forecast != null && forecast.bridge == null) forecast.bridge = bridge;
        content.addView(hero, params(230, 0));

        if (forecast == null || !forecast.available) {
            LinearLayout unavailable = card();
            unavailable.addView(label("Previsão indisponível agora", 15, INK, true));
            unavailable.addView(body(reason(forecast)));
            if (forecast != null && forecast.fromCache) {
                unavailable.addView(body("Último resultado salvo no aparelho; ele é mostrado como cache e pode estar antigo."));
            }
            addScorecard();
            requestForecastIfNeeded();
            return;
        }

        addSummary(forecast);
        addFactors(forecast, samples, taio, itup);
        addScorecard();
        addSources(forecast);
    }

    private void addSummary(Forecast forecast) {
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", BR);
        LinearLayout summary = card();
        summary.addView(label("Horizontes retornados pelo servidor", 15, INK, true));
        String base = forecast.baseTime > 0 ? hm.format(new Date(forecast.baseTime)) : "horário não informado";
        summary.addView(body("Base de referência: " + base + ". O aplicativo não recalcula, altera ou completa os valores recebidos."));
        for (int hours = 1; hours <= 3; hours++) {
            Forecast.Horizon horizon = forecast.horizon(hours);
            if (horizon == null) {
                summary.addView(body("+" + hours + " h: não retornado pelo servidor."));
                continue;
            }
            String target = horizon.targetTime > 0 ? hm.format(new Date(horizon.targetTime)) : "horário não informado";
            String level = Double.isNaN(horizon.predictedLevel) ? "nível não informado"
                    : String.format(BR, "%.2f m", horizon.predictedLevel);
            String delta = String.format(BR, "%s %s cm", horizon.deltaCm >= 0 ? "+" : "−",
                    Math.abs(horizon.deltaCm));
            String band = Double.isNaN(horizon.bandCm) ? "margem não informada"
                    : String.format(BR, "± %.0f cm", horizon.bandCm);
            summary.addView(body(String.format(BR, "+%d h · %s · %s · %s · %s · confiança %s",
                    hours, target, level, delta, band, horizon.confidenceLabel())));
        }
        String freshness = forecast.freshnessLabel == null || forecast.freshnessLabel.isEmpty()
                ? "resposta do servidor" : forecast.freshnessLabel;
        TextView fresh = body("Freshness: " + freshness + (forecast.fromCache ? " (offline)" : "") + ".");
        fresh.setTextColor(forecast.stale ? Color.rgb(180, 83, 9) : GREY);
        summary.addView(fresh);
        if (!forecast.notes.isEmpty()) {
            final StringBuilder details = new StringBuilder("Diagnóstico do servidor:\n");
            for (String note : forecast.notes) details.append("• ").append(note).append('\n');
            final TextView notesView = body("Diagnóstico do servidor (" + forecast.notes.size()
                    + " observações) · toque para expandir");
            notesView.setTextColor(Color.rgb(180, 83, 9));
            notesView.setOnClickListener(new View.OnClickListener() {
                private boolean expanded;
                @Override public void onClick(View v) {
                    expanded = !expanded;
                    notesView.setText(expanded ? details.toString()
                            : "Diagnóstico do servidor (" + forecast.notes.size()
                            + " observações) · toque para expandir");
                }
            });
            summary.addView(notesView);
        }
    }

    private void addFactors(Forecast forecast, List<Sample> samples, DamReading taio, DamReading itup) {
        LinearLayout trend = section("1", "Dados de nível e tendência enviados pelo servidor");
        trend.addView(anim(MemorialAnimView.TREND, forecast, samples, taio, itup), params(150, 6));
        trend.addView(body("O servidor informa a tendência observada e os demais campos de apoio quando disponíveis."));
        trend.addView(body(detail(forecast, Forecast.Factor.TENDENCIA)));

        LinearLayout upstream = section("2", "Montante, barragens e chuva considerados pelo servidor");
        upstream.addView(anim(MemorialAnimView.UPSTREAM, forecast, samples, taio, itup), params(140, 6));
        upstream.addView(body("Os fatores abaixo são explicações recebidas do serviço centralizado; não são calculados no aparelho."));
        upstream.addView(body(detail(forecast, Forecast.Factor.MONTANTE)));
        upstream.addView(body(detail(forecast, Forecast.Factor.BARRAGENS)));

        LinearLayout rain = section("3", "Chuva e contexto da bacia");
        final MemorialAnimView rainAnim = anim(MemorialAnimView.RAIN, forecast, samples, taio, itup);
        rain.addView(rainAnim, params(130, 6));
        addMunicipalRainToggle(rain, rainAnim);
        rain.addView(body(detail(forecast, Forecast.Factor.CHUVA_OBSERVADA)));
        rain.addView(body(detail(forecast, Forecast.Factor.CHUVA_PREVISTA)));

        LinearLayout factors = section("4", "Fatores explicativos retornados pelo servidor");
        factors.addView(anim(MemorialAnimView.BARS, forecast, samples, taio, itup),
                params(34 + 30 * Math.max(1, forecast.factors.size()), 6));
        if (forecast.factors.isEmpty()) {
            factors.addView(body("O servidor não retornou parcelas explicativas para esta resposta."));
        } else {
            for (Forecast.Factor factor : forecast.factors) {
                factors.addView(body(factor.title + ": " + factor.detail + " Parcela informada: " + signed(factor.cm) + " cm."));
            }
        }
    }

    private void addMunicipalRainToggle(LinearLayout rain, MemorialAnimView rainAnim) {
        TextView hint = label("Toque duas vezes no desenho para ver a chuva de 24 h por município", 11,
                Color.rgb(2, 132, 199), true);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        hint.setPadding(0, dp(4), 0, 0);
        rain.addView(hint);
        final MunicipalRainView cities = new MunicipalRainView(this);
        cities.setData(snapshot == null ? null : snapshot.municipalRain);
        cities.setVisibility(View.GONE);
        LinearLayout.LayoutParams cityParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cityParams.topMargin = dp(8);
        rain.addView(cities, cityParams);
        final TextView hintRef = hint;
        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                boolean open = cities.getVisibility() != View.VISIBLE;
                cities.setVisibility(open ? View.VISIBLE : View.GONE);
                if (open) cities.replay();
                hintRef.setText(open ? "Toque duas vezes no desenho para fechar a grade"
                        : "Toque duas vezes no desenho para ver a chuva de 24 h por município");
                return true;
            }
        });
        rainAnim.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent event) { return detector.onTouchEvent(event); }
        });
    }

    private void addScorecard() {
        LinearLayout section = section("5", "Histórico e acurácia do servidor");
        section.addView(body("A grade abaixo é somente a resposta de histórico e estatísticas do serviço centralizado. O aparelho não cria previsões históricas, não confere níveis e não calcula percentuais."));
        TextView period = label("Histórico: últimas 24 h · acurácia: últimos 7 dias", 11, GREY, false);
        period.setPadding(0, dp(8), 0, dp(2));
        section.addView(period);

        final CheckBox checked = new CheckBox(this);
        checked.setText("Já conferidas");
        checked.setTextColor(INK);
        checked.setTextSize(14);
        checked.setMinHeight(dp(48));
        checked.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.rgb(123, 31, 162)));
        checked.setChecked(onlyCheckedHistory);
        checked.setContentDescription("Já conferidas: mostrar apenas linhas com os três horizontes conferidos pelo servidor");
        section.addView(checked);
        TextView filterHelp = label("Quando marcado, exibe somente linhas com +1 h, +2 h e +3 h já conferidas. Os percentuais acima não são alterados pelo filtro.", 11, GREY, false);
        filterHelp.setPadding(0, 0, 0, dp(8));
        section.addView(filterHelp);

        final TextView count = label("", 12, GREY, false);
        count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        section.addView(count);
        final ForecastScorecardView score = new ForecastScorecardView(this);
        LinearLayout.LayoutParams scoreParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scoreParams.topMargin = dp(10);
        section.addView(score, scoreParams);

        final Button more = new Button(this);
        more.setText("Mostrar mais");
        more.setAllCaps(false);
        more.setTextSize(14);
        more.setTextColor(Color.WHITE);
        more.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(123, 31, 162)));
        more.setMinHeight(dp(48));
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        moreParams.topMargin = dp(10);
        section.addView(more, moreParams);

        HydrologyForecastClient.Response response = snapshot == null ? null : snapshot.forecastResponse;
        if (response == null) response = HydrologyForecastClient.loadCached(this);
        final HydrologyForecastClient.Response historyResponse = response;
        updateHistory(score, count, more, historyResponse);
        checked.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean isChecked) {
                onlyCheckedHistory = isChecked;
                historyLimit = ForecastHistorySelection.PAGE_SIZE;
                updateHistory(score, count, more, historyResponse);
            }
        });
        more.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                historyLimit += ForecastHistorySelection.PAGE_SIZE;
                updateHistory(score, count, more, historyResponse);
            }
        });
        // Uma consulta nova a cada abertura; cache não mantém histórico nem estatísticas.
        requestForecastIfNeeded();
    }

    private void updateHistory(ForecastScorecardView score, TextView count, Button more,
                               HydrologyForecastClient.Response response) {
        boolean hasResponse = response != null && response.contractV2;
        boolean hasHistory = hasResponse && (!response.history.isEmpty() || !response.accuracy.isEmpty());
        if (!hasHistory && !forecastRequestFinished) {
            score.setLoading();
            count.setText("Consultando o histórico do servidor…");
            more.setVisibility(View.GONE);
            return;
        }
        List<HydrologyForecastClient.HistoryGroup> all = hasResponse
                ? response.groupsFor(bridge, Integer.MAX_VALUE) : java.util.Collections.emptyList();
        // Filtrar antes de limitar evita ocultar as linhas antigas de +2 h e +3 h.
        List<HydrologyForecastClient.HistoryGroup> filtered = ForecastHistorySelection.filter(all, onlyCheckedHistory);
        List<HydrologyForecastClient.HistoryGroup> visible = ForecastHistorySelection.visible(filtered, historyLimit);
        String summary = visible.size() + " de " + filtered.size() + " linhas"
                + (onlyCheckedHistory ? " com os três horizontes conferidos" : " recebidas do servidor");
        if (!forecastRequestError.isEmpty()) summary += " · atualização indisponível; último histórico recebido";
        count.setText(summary);
        score.setEmptyMessage(onlyCheckedHistory
                ? "Ainda não há linhas com os três horizontes conferidos."
                : "O servidor não retornou histórico para esta ponte.");
        score.setData(visible, hasResponse ? accuracy(response) : null, bridge.displayName,
                hasResponse ? response.disclaimer : "Estimativa experimental; não substitui os avisos oficiais da Defesa Civil.");
        int remaining = filtered.size() - visible.size();
        more.setVisibility(remaining > 0 ? View.VISIBLE : View.GONE);
        more.setContentDescription("Mostrar mais " + Math.min(ForecastHistorySelection.PAGE_SIZE, remaining) + " linhas do histórico");
    }

    private HydrologyForecastClient.AccuracyRow[] accuracy(HydrologyForecastClient.Response response) {
        HydrologyForecastClient.AccuracyRow[] values = new HydrologyForecastClient.AccuracyRow[3];
        for (int h = 1; h <= 3; h++) values[h - 1] = response.accuracyFor(bridge, h);
        return values;
    }

    private void requestForecastIfNeeded() {
        if (forecastRequestStarted) return;
        forecastRequestStarted = true;
        HydrologyForecastClient.fetchAsync(this, new HydrologyForecastClient.Callback() {
            @Override public void onComplete(final HydrologyForecastClient.Response response, Exception error) {
                if (isFinishing()) return;
                final boolean requestFailed = error != null;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        forecastRequestFinished = true;
                        forecastRequestError = requestFailed || response == null || !response.contractV2
                                ? "Falha ao atualizar" : "";
                        if (response != null && response.contractV2) {
                            snapshot = AppCache.load(ForecastActivity.this);
                            if (snapshot == null) snapshot = new Snapshot();
                            HydrologyForecastClient.applyToSnapshot(snapshot, response, false);
                            AppCache.save(ForecastActivity.this, snapshot);
                        }
                        build(snapshot == null ? null : snapshot.forecasts.get(bridge));
                    }
                });
            }
        });
    }

    private void addSources(Forecast forecast) {
        LinearLayout sourceCard = card();
        sourceCard.addView(label("Fonte e limites", 15, INK, true));
        sourceCard.addView(body("Níveis atuais, cotas, chuva, barragens e histórico visual continuam vindo das fontes públicas já usadas pelo app. A previsão e as estatísticas desta tela vêm do serviço centralizado."));
        String model = forecast.modelVersion == null || forecast.modelVersion.isEmpty()
                ? "versão do modelo não informada" : forecast.modelVersion;
        sourceCard.addView(body("Modelo informado pelo servidor: " + model + "."));
        TextView warn = body("Estimativa experimental; siga avisos oficiais. Para decisões de segurança, siga sempre os avisos da Defesa Civil (telefone 199).");
        warn.setTextColor(Color.rgb(153, 27, 27));
        warn.setTypeface(Typeface.DEFAULT_BOLD);
        sourceCard.addView(warn);
        if (forecast.generatedAt != null && !forecast.generatedAt.isEmpty()) {
            sourceCard.addView(label("Gerada em " + forecast.generatedAt, 11, GREY, false));
        }
    }

    private String reason(Forecast forecast) {
        if (forecast != null && forecast.unavailableReason != null && !forecast.unavailableReason.isEmpty()) {
            return forecast.unavailableReason;
        }
        return "O servidor ainda não retornou um resultado atual. Tente novamente quando houver conexão.";
    }

    private String detail(Forecast forecast, String key) {
        for (Forecast.Factor factor : forecast.factors) {
            if (key.equals(factor.key)) return factor.detail + " Parcela informada: " + signed(factor.cm) + " cm.";
        }
        return "Não informado nesta resposta.";
    }

    private static String signed(double value) {
        if (Math.abs(value) < .05) return "0,0";
        return (value > 0 ? "+" : "−") + String.format(BR, "%.1f", Math.abs(value));
    }

    private MemorialAnimView anim(int type, Forecast forecast, List<Sample> samples,
                                 DamReading taio, DamReading itup) {
        MemorialAnimView view = new MemorialAnimView(this, type);
        view.setData(forecast, samples, taio, itup);
        return view;
    }

    private LinearLayout.LayoutParams params(int heightDp, int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp));
        params.topMargin = dp(topDp);
        return params;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(16));
        card.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        content.addView(card, params);
        return card;
    }

    private LinearLayout section(String number, String title) {
        LinearLayout card = card();
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = label(number, 13, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.rgb(123, 31, 162));
        badge.setBackground(circle);
        header.addView(badge, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView titleView = label(title, 15, INK, true);
        titleView.setPadding(dp(10), 0, 0, 0);
        header.addView(titleView);
        card.addView(header);
        return card;
    }

    private TextView label(String text, float sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView body(String text) {
        TextView view = label(text, 13.5f, BODY, false);
        view.setLineSpacing(dp(3), 1f);
        view.setPadding(0, dp(8), 0, 0);
        return view;
    }
}
