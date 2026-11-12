package br.com.riodosul.niveldorio;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Tela que abre com dois toques no card de uma barragem: tendência das últimas 24 horas
 * (nível do reservatório e comportas) e a chuva que caiu sobre a barragem nesse período.
 */
public class DamActivity extends Activity {
    public static final String EXTRA_DAM = "dam";
    private static final Locale BR = new Locale("pt", "BR");
    private static final int INK = Color.rgb(28, 65, 94);
    private static final int BODY = Color.rgb(55, 65, 81);
    private static final int GREY = Color.rgb(102, 112, 133);

    private float density;
    private LinearLayout content;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;

        String key = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_DAM);
        if (key == null) key = DamSummaryView.KEY_TAIO;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(243, 247, 252));
        scroll.setFitsSystemWindows(true);
        scroll.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(10), dp(16), dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        Snapshot s = AppCache.load(this);
        DamReading dam = DamSummaryView.KEY_ITUPORANGA.equals(key)
                ? (s == null ? null : s.ituporanga) : (s == null ? null : s.taio);
        String fallback = DamSummaryView.KEY_ITUPORANGA.equals(key) ? "Barragem Sul Ituporanga" : "Barragem Oeste Taió";
        if (dam == null) dam = new DamReading(fallback, 0, 0, 0, 0, "");

        build(dam, s);
    }

    private int dp(float v) { return Math.round(v * density); }

    private void build(DamReading dam, Snapshot s) {
        s_fetchedAt = s == null ? 0L : s.fetchedAt;
        TextView back = label("‹  Voltar", 15, Color.rgb(21, 101, 192), true);
        back.setPadding(0, dp(10), dp(24), dp(10));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        content.addView(back);

        TextView title = label(dam.name, 21, INK, true);
        content.addView(title);
        TextView subtitle = label("Tendência das últimas 24 horas e chuva na barragem", 13, GREY, false);
        subtitle.setPadding(0, dp(2), 0, dp(12));
        content.addView(subtitle);

        // Indicadores do momento.
        LinearLayout kpis = card();
        addKpiRow(kpis,
                tile("Nível do reservatório", String.format(BR, "%.2f m", dam.levelMeters), stateColor(dam)),
                tile("Ocupação", String.format(BR, "%.1f%%", dam.capacityPercent), stateColor(dam)));
        addKpiRow(kpis,
                tile("Comportas", dam.gatesOpen + "/" + Math.max(0, dam.gatesTotal) + " abertas",
                        dam.gatesOpen > 0 ? Color.rgb(0, 121, 107) : GREY),
                tile("Variação em 3 h", Double.isNaN(dam.levelChange3hCm) ? "—"
                                : String.format(BR, "%s%.0f cm", dam.levelChange3hCm >= 0 ? "+" : "−",
                                Math.abs(dam.levelChange3hCm)),
                        Color.rgb(21, 101, 192)));
        kpis.addView(label("Leitura: " + (dam.age == null || dam.age.isEmpty() ? "horário não informado" : dam.age),
                10.5f, GREY, false));

        // Gráfico animado de 24 h.
        LinearLayout chart = card();
        chart.addView(sectionTitle("Últimas 24 horas"));
        DamChartView view = new DamChartView(this);
        view.setDam(dam);
        chart.addView(view, matchWrap(dp(370)));
        chart.addView(label(dam.rainFromGauge
                ? "Nível e chuva medidos pela Defesa Civil na barragem (nível a cada 15 min, chuva somada por hora)."
                : "Nível medido pela Defesa Civil a cada 15 min; chuva por hora estimada pelo modelo no ponto da barragem.",
                10.5f, GREY, false));

        // Chuva nas últimas 24 h.
        LinearLayout rainCard = card();
        rainCard.addView(iconTitle("Chuva sobre a barragem", dam.rain24hMm));
        rainCard.addView(body(rainSummary(dam)));
        rainCard.addView(body(rainDetail(dam)));
        rainCard.addView(label(dam.rainFromGauge
                ? "Chuva medida pelo pluviômetro da Defesa Civil instalado na barragem, somada hora a hora nas últimas 24 h."
                : "O pluviômetro da barragem não respondeu: chuva estimada pelo modelo Open-Meteo no ponto da barragem "
                        + "(" + coordinate(dam) + "), hora a hora, nas últimas 24 h.", 10.5f, GREY, false));

        // Comportas nas últimas 24 h.
        LinearLayout gatesCard = card();
        gatesCard.addView(sectionTitle("Comportas nas últimas 24 horas"));
        gatesCard.addView(body(gateSummary(dam)));

        // Legenda das cores da borda dos cards.
        LinearLayout legend = card();
        legend.addView(sectionTitle("O que significa a cor da borda"));
        legend.addView(body(stateLegend(dam)));

        if (s_fetchedAt > 0) {
            legend.addView(label("Consulta de " + new SimpleDateFormat("dd/MM/yyyy HH:mm", BR)
                    .format(new Date(s_fetchedAt)), 10.5f, GREY, false));
        }
    }

    /** Carimbo da última consulta, para o rodapé. */
    private long s_fetchedAt;

    // ---------------------------------------------------------------- textos

    private int stateColor(DamReading d) {
        return DamSummaryView.corBorda(DamSummaryView.estado(d));
    }

    private String stateLegend(DamReading d) {
        int e = DamSummaryView.estado(d);
        return String.format(BR,
                "Agora: %s.\n"
                        + "• Verde — normal: reservatório abaixo de 80%% e sem comporta aberta.\n"
                        + "• Âmbar — atenção: ocupação de 80%% a 94,9%% ou comportas abertas (descarga em curso).\n"
                        + "• Vermelho — no limite: reservatório em 95%% ou mais.",
                DamSummaryView.nomeEstado(e).toLowerCase(BR));
    }

    private String rainSummary(DamReading d) {
        if (Double.isNaN(d.rain24hMm)) {
            return "A consulta de chuva sobre a barragem não respondeu desta vez. "
                    + "Assim que ela voltar, o total das últimas 24 h aparece aqui.";
        }
        if (d.rain24hMm < 0.2) {
            return String.format(BR, "Não choveu de forma relevante sobre a barragem nas últimas 24 h (%.1f mm no total).",
                    d.rain24hMm);
        }
        double max = 0;
        long maxTime = 0;
        int hoursWithRain = 0;
        for (Sample s : d.rainHourly) {
            if (Double.isNaN(s.value) || s.value < 0) continue;
            if (s.value >= 0.2) hoursWithRain++;
            if (s.value > max) {
                max = s.value;
                maxTime = s.timeMillis;
            }
        }
        return String.format(BR, "Choveu sim: %.1f mm nas últimas 24 h, em %d hora%s, com pico de %.1f mm%s.",
                d.rain24hMm, hoursWithRain, hoursWithRain == 1 ? "" : "s", max,
                maxTime > 0 ? " às " + new SimpleDateFormat("HH:mm", BR).format(new Date(maxTime)) : "");
    }

    private String rainDetail(DamReading d) {
        if (d.rainHourly.isEmpty() || Double.isNaN(d.rain24hMm) || d.rain24hMm < 0.2) return "";
        StringBuilder sb = new StringBuilder("Hora a hora: ");
        SimpleDateFormat hm = new SimpleDateFormat("HH'h'", BR);
        int shown = 0;
        for (int i = d.rainHourly.size() - 1; i >= 0 && shown < 8; i--) {
            Sample s = d.rainHourly.get(i);
            if (Double.isNaN(s.value) || s.value < 0.2) continue;
            if (shown > 0) sb.append("  •  ");
            sb.append(hm.format(new Date(s.timeMillis))).append(" ").append(String.format(BR, "%.1f", s.value));
            shown++;
        }
        if (shown == 0) return "";
        sb.append(" mm");
        return sb.toString();
    }

    private String gateSummary(DamReading d) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(BR, "%d abertura%s e %d fechamento%s de comporta nas últimas 24 h.",
                d.gatesOpened24h, d.gatesOpened24h == 1 ? "" : "s",
                d.gatesClosed24h, d.gatesClosed24h == 1 ? "" : "s"));
        if (!Double.isNaN(d.hoursSinceLastGateEvent)) {
            sb.append(String.format(BR, " A última manobra foi há %.1f h.", d.hoursSinceLastGateEvent));
        } else {
            sb.append(" Nenhuma manobra registrada no período.");
        }
        if (d.gatesOpenedArriving + d.gatesClosedArriving > 0) {
            sb.append(String.format(BR, " Entre 2 e 12 h atrás, %d abertura(s) e %d fechamento(s) "
                    + "— é a água que pode estar chegando a Rio do Sul agora.",
                    d.gatesOpenedArriving, d.gatesClosedArriving));
        }
        return sb.toString();
    }

    private String coordinate(DamReading d) {
        if (Double.isNaN(d.latitude) || Double.isNaN(d.longitude)) return "posição não informada";
        return String.format(BR, "%.4f, %.4f", d.latitude, d.longitude);
    }

    // ---------------------------------------------------------------- componentes

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.rgb(224, 232, 241));
        card.setBackground(bg);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(12);
        content.addView(card, p);
        return card;
    }

    private LinearLayout.LayoutParams matchWrap(int heightPx) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, heightPx);
        p.topMargin = dp(6);
        return p;
    }

    private void addKpiRow(LinearLayout parent, View first, View second) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(4);
        parent.addView(row, rowParams);
        row.addView(first, kpiParams());
        row.addView(second, kpiParams());
    }

    private LinearLayout.LayoutParams kpiParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.leftMargin = dp(4);
        p.rightMargin = dp(4);
        return p;
    }

    private View tile(String caption, String value, int accent) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(245, 249, 254));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.rgb(224, 232, 241));
        tile.setBackground(bg);

        TextView v = label(value, 19, accent, true);
        tile.addView(v);
        TextView k = label(caption, 10, GREY, false);
        tile.addView(k);
        return tile;
    }

    /** Título com o ícone animado de sol ou chuva à esquerda. */
    private View iconTitle(String text, final double mm) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View icon = new View(this) {
            private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

            @Override protected void onDraw(android.graphics.Canvas c) {
                super.onDraw(c);
                WeatherIcon.draw(c, p, getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight()), mm,
                        System.currentTimeMillis());
                if (isShown()) postInvalidateDelayed(33);
            }
        };
        row.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView t = sectionTitle(text);
        t.setPadding(dp(8), 0, 0, 0);
        row.addView(t);
        return row;
    }

    private TextView sectionTitle(String text) {
        return label(text, 15, INK, true);
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
