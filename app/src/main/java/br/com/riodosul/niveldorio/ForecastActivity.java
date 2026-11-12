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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Memorial descritivo: explica, passo a passo e com ilustrações animadas, como a previsão foi feita. */
public class ForecastActivity extends Activity {
    public static final String EXTRA_BRIDGE = "bridge";
    private static final Locale BR = new Locale("pt", "BR");
    private static final int INK = Color.rgb(28, 65, 94);
    private static final int BODY = Color.rgb(55, 65, 81);
    private static final int GREY = Color.rgb(102, 112, 133);

    private float density;
    private LinearLayout content;
    private ExecutorService scorePool;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;

        Bridge bridge = AppPrefs.getSelectedBridge(this);
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
        scroll.addView(content, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        Snapshot s = AppCache.load(this);
        Forecast f = s == null ? null : s.forecasts.get(bridge);
        build(bridge, s, f);
    }

    private int dp(float v) { return Math.round(v * density); }

    private void build(Bridge bridge, Snapshot s, Forecast f) {
        TextView back = label("‹  Voltar", 15, Color.rgb(21, 101, 192), true);
        back.setPadding(0, dp(10), dp(24), dp(10));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        content.addView(back);

        TextView title = label("Como chegamos a esta previsão", 21, INK, true);
        content.addView(title);
        TextView subtitle = label("Memorial descritivo — " + bridge.label(), 13, GREY, false);
        subtitle.setPadding(0, dp(2), 0, dp(12));
        content.addView(subtitle);

        List<Sample> samples = s == null ? null : s.riverHistory.get(bridge);
        DamReading taio = s == null ? null : s.taio, itup = s == null ? null : s.ituporanga;

        MemorialAnimView hero = anim(MemorialAnimView.HERO, f, samples, taio, itup);
        if (f != null && f.bridge == null) f.bridge = bridge;
        content.addView(hero, params(230, 0));

        if (f == null || !f.available) {
            LinearLayout card = card();
            card.addView(label("Previsão indisponível agora", 15, INK, true));
            card.addView(body(f != null && !f.unavailableReason.isEmpty() ? f.unavailableReason
                    : "Ainda não há uma consulta completa guardada. Volte à tela principal e puxe para baixo para atualizar."));
            addScorecard(bridge);
            return;
        }

        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", BR);
        String now = hm.format(new Date(f.baseTime)), then = hm.format(new Date(f.targetTime));

        // Resumo.
        LinearLayout resumo = card();
        resumo.addView(label("Em resumo", 15, INK, true));
        resumo.addView(body(String.format(BR,
                "Às %s o rio estava em %.2f m (%s). Para as %s a estimativa é de %.2f m: %s %s cm, com margem de erro de "
                        + "± %.0f cm — ou seja, entre %.2f m e %.2f m. Confiança %s.",
                now, f.currentLevel, f.cotas.situacao(f.currentLevel).toLowerCase(BR), then, f.predictedLevel,
                f.deltaCm >= 1 ? "sobe" : f.deltaCm <= -1 ? "desce" : "varia",
                String.format(BR, "%.0f", Math.abs(f.deltaCm)), f.bandCm,
                f.predictedLevel - f.bandCm / 100.0, f.predictedLevel + f.bandCm / 100.0, f.confidenceLabel())));
        Forecast.Horizon h2 = f.horizon(2), h3 = f.horizon(3);
        if (h2 != null && h3 != null) {
            resumo.addView(body(String.format(BR,
                    "Mais adiante: %.2f m às %s (± %.0f cm, confiança %s) e %.2f m às %s (± %.0f cm, confiança %s). "
                            + "Quanto mais longe, maior a margem: o rio tem mais tempo para mudar de ritmo.",
                    h2.predictedLevel, hm.format(new Date(h2.targetTime)), h2.bandCm, h2.confidenceLabel(),
                    h3.predictedLevel, hm.format(new Date(h3.targetTime)), h3.bandCm, h3.confidenceLabel())));
        }
        for (String note : f.notes) {
            TextView n = body("⚠ " + note);
            n.setTextColor(Color.rgb(180, 83, 9));
            resumo.addView(n);
        }

        // 1. Tendência.
        LinearLayout c1 = section("1", "O rio agora e o ritmo dele");
        c1.addView(anim(MemorialAnimView.TREND, f, samples, taio, itup), params(150, 6));
        c1.addView(body(detail(f, Forecast.Factor.TENDENCIA)
                + (Double.isNaN(f.rate180) ? "" : String.format(BR, " Olhando as últimas 3 horas, o ritmo médio foi de %s cm/h.",
                signed(f.rate180)))));
        c1.addView(body("Este é o fator de maior peso: o que o rio fez na última hora é o melhor indício do que fará na próxima."));

        // 2. Montante.
        LinearLayout c2 = section("2", "O que vem rio acima");
        c2.addView(anim(MemorialAnimView.UPSTREAM, f, samples, taio, itup), params(140, 6));
        c2.addView(body("Os rios Itajaí do Oeste e Itajaí do Sul se juntam em Rio do Sul e formam o Itajaí-Açu. "
                + detail(f, Forecast.Factor.MONTANTE)));

        // 3. Barragens.
        LinearLayout c3 = section("3", "Barragens de Taió e Ituporanga");
        c3.addView(anim(MemorialAnimView.DAMS, f, samples, taio, itup), params(150, 6));
        c3.addView(body(detail(f, Forecast.Factor.BARRAGENS)));

        // 4. Chuva.
        LinearLayout c4 = section("4", "Chuva na bacia");
        final MemorialAnimView rainAnim = anim(MemorialAnimView.RAIN, f, samples, taio, itup);
        c4.addView(rainAnim, params(130, 6));
        TextView rainHint = label("Toque duas vezes no desenho para ver a chuva de 24 h por município", 11,
                Color.rgb(2, 132, 199), true);
        rainHint.setGravity(Gravity.CENTER_HORIZONTAL);
        rainHint.setPadding(0, dp(4), 0, 0);
        c4.addView(rainHint);
        final MunicipalRainView cities = new MunicipalRainView(this);
        cities.setData(s == null ? null : s.municipalRain);
        cities.setVisibility(View.GONE);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(8);
        c4.addView(cities, cp);
        final TextView hintRef = rainHint;
        final GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                return true;
            }

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
            @Override public boolean onTouch(View v, MotionEvent event) {
                return detector.onTouchEvent(event);
            }
        });
        c4.addView(body(detail(f, Forecast.Factor.CHUVA_OBSERVADA)));
        c4.addView(body(detail(f, Forecast.Factor.CHUVA_PREVISTA)));
        if (!f.bulletinSummary.isEmpty()) {
            c4.addView(body("Boletim de hoje da Defesa Civil de Rio do Sul: " + f.bulletinSummary));
        }

        // 5. A conta.
        LinearLayout c5 = section("5", "A conta, parcela por parcela");
        c5.addView(anim(MemorialAnimView.BARS, f, samples, taio, itup), params(34 + 30 * Math.max(1, f.factors.size()), 6));
        c5.addView(body(String.format(BR, "Nível atual %.2f m %s %s cm = %.2f m previstos para as %s.",
                f.currentLevel, f.deltaCm >= 0 ? "+" : "−", String.format(BR, "%.1f", Math.abs(f.deltaCm)), f.predictedLevel, then)));
        c5.addView(body("Barras vermelhas empurram o nível para cima; verdes, para baixo; cinza não alteram."));

        // 6. Margem de erro.
        LinearLayout c6 = section("6", "Margem de erro e confiança");
        c6.addView(body(String.format(BR,
                "A margem de ± %.0f cm soma três coisas: a oscilação natural do sensor (%.1f cm nesta janela), uma parte da "
                        + "variação prevista — quanto mais rápido o rio muda, maior a dúvida — e a incerteza da chuva.",
                f.bandCm, Double.isNaN(f.noiseCm) ? 0 : f.noiseCm)));
        c6.addView(body("A fórmula da tendência foi conferida com 21 dias de leituras reais das estações de Rio do Sul "
                + "(outubro e novembro de 2026, incluindo uma cheia perto de 9 m). Nas pontes Dom Tito Buss e BR-470, o erro "
                + "médio da previsão de 1 hora ficou entre 1 e 2 cm, e 9 em cada 10 previsões erraram menos de 4 cm. "
                + "Na estação do Itajaí do Sul o sensor oscila mais e o erro típico é maior (perto de 8 cm)."));
        c6.addView(body("Para 2 e 3 horas a conta usa 85% do ritmo medido a cada hora. No mesmo histórico, o erro médio "
                + "foi de cerca de 4 cm (2 h) e 6 cm (3 h) na Dom Tito Buss, e de 2 cm e 4 cm na BR-470 — sempre menos da "
                + "metade do erro de simplesmente repetir o nível atual. A margem cresce com o horizonte e cobriu de 8 a 9 em "
                + "cada 10 casos nessas duas pontes."));
        c6.addView(body("Os ajustes de rios a montante, barragens e chuva têm pesos pequenos e limites fixos; eles são "
                + "estimativas de bom senso, ainda não calibradas com histórico."));

        addScorecard(bridge);

        // Fontes e limites.
        LinearLayout c7 = card();
        c7.addView(label("Fontes e limites", 15, INK, true));
        c7.addView(body("• Níveis, cotas, pluviômetros e barragens: Defesa Civil de Rio do Sul (portal público).\n"
                + "• Chuva prevista hora a hora: modelo meteorológico Open-Meteo, em cinco pontos da bacia.\n"
                + "• Boletim do dia: Defesa Civil de Rio do Sul."));
        TextView warn = body("Esta é uma estimativa automática e aproximada para as próximas 3 horas. Chuva forte e "
                + "localizada, manobras nas barragens e falhas de sensor podem mudar o rio mais depressa do que a conta "
                + "enxerga. Para decisões de segurança, siga sempre os avisos oficiais da Defesa Civil (telefone 199).");
        warn.setTextColor(Color.rgb(153, 27, 27));
        warn.setTypeface(Typeface.DEFAULT_BOLD);
        c7.addView(warn);
        if (s != null && s.fetchedAt > 0) {
            c7.addView(label("Consulta de " + new SimpleDateFormat("dd/MM/yyyy HH:mm", BR).format(new Date(s.fetchedAt)), 11, GREY, false));
        }
    }

    /** Seção 7: previsões anteriores (1, 2 e 3 h) comparadas com o nível que o rio marcou. */
    private void addScorecard(final Bridge bridge) {
        LinearLayout c = section("7", "A previsão tem acertado?");
        c.addView(body("Cada previsão calculada — para daqui a 1, 2 e 3 horas — fica registrada. Quando o horário previsto "
                + "passa, o app procura a leitura da estação mais próxima daquele horário e compara. Os quadros mostram "
                + "quantas ficaram dentro da margem nos últimos 30 dias; a grade, as previsões mais recentes."));

        final ForecastScorecardView score = new ForecastScorecardView(this);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(10);
        c.addView(score, p);

        scorePool = Executors.newSingleThreadExecutor();
        scorePool.execute(new Runnable() {
            @Override public void run() {
                SupabaseForecast.pull(ForecastActivity.this, bridge, 180);
                final List<ForecastStore.Issue> issues = ForecastStore.recentIssues(ForecastActivity.this, bridge, 12);
                final ForecastStore.Stats[] stats = new ForecastStore.Stats[ForecastStore.HORIZONS];
                for (int h = 1; h <= ForecastStore.HORIZONS; h++) {
                    stats[h - 1] = ForecastStore.stats(ForecastActivity.this, bridge, 30, h);
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        score.setData(issues, stats, bridge.displayName);
                    }
                });
            }
        });
    }

    @Override protected void onDestroy() {
        if (scorePool != null) {
            scorePool.shutdownNow();
            scorePool = null;
        }
        super.onDestroy();
    }

    private String detail(Forecast f, String key) {
        if (f == null) return "";
        for (Forecast.Factor x : f.factors) {
            if (key.equals(x.key)) {
                return x.detail + " Parcela na conta: " + signed(x.cm) + " cm.";
            }
        }
        return "";
    }

    private static String signed(double v) {
        if (Math.abs(v) < 0.05) return "0,0";
        return (v > 0 ? "+" : "−") + String.format(BR, "%.1f", Math.abs(v));
    }

    // ---------------------------------------------------------------- componentes

    private MemorialAnimView anim(int type, Forecast f, List<Sample> samples, DamReading taio, DamReading itup) {
        MemorialAnimView v = new MemorialAnimView(this, type);
        v.setData(f, samples, taio, itup);
        return v;
    }

    private LinearLayout.LayoutParams params(int heightDp, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp));
        p.topMargin = dp(topDp);
        return p;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(16));
        card.setBackground(bg);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(12);
        content.addView(card, p);
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

        TextView t = label(title, 15, INK, true);
        t.setPadding(dp(10), 0, 0, 0);
        header.addView(t);
        card.addView(header);
        return card;
    }

    private TextView label(String text, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView body(String text) {
        TextView t = label(text, 13.5f, BODY, false);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(0, dp(8), 0, 0);
        return t;
    }
}
