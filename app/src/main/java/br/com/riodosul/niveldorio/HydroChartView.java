package br.com.riodosul.niveldorio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Painel visual sem dependências externas: histórico do rio e cartões de
 * barragens com ocupação, nível e estado individual das comportas.
 */
public class HydroChartView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private List<Sample> riverSamples = new ArrayList<>();
    private Double currentRiverLevel;
    private DamReading taio;
    private DamReading ituporanga;

    public HydroChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void setData(List<Sample> samples, Double currentRiverLevel, DamReading taio, DamReading ituporanga) {
        riverSamples = samples == null ? new ArrayList<>() : new ArrayList<>(samples);
        this.currentRiverLevel = currentRiverLevel;
        this.taio = taio;
        this.ituporanga = ituporanga;
        invalidate();
    }

    private float dp(float value) { return value * density; }

    private void text(Canvas c, String value, float x, float y, float size, int color, boolean bold) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(dp(size));
        paint.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        c.drawText(value, x, y, paint);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(panelColor(currentRiverLevel));
        c.drawRoundRect(new RectF(0, 0, w, h), dp(16), dp(16), paint);

        float pad = dp(16);
        text(c, "Monitoramento", pad, pad + dp(16), 16, Color.rgb(15, 47, 82), true);
        text(c, "Rio, barragens e comportas", pad, pad + dp(36), 12, Color.rgb(102, 112, 133), false);
        text(c, alertForLevel(currentRiverLevel), w - pad - dp(76), pad + dp(20), 11, alertColor(currentRiverLevel), true);
        drawLegend(c, w - pad - dp(150), pad + dp(8));

        float chartTop = pad + dp(52);
        float chartHeight = dp(145);
        drawRiverChart(c, pad, chartTop, w - pad, chartHeight);

        float cardsTop = chartTop + chartHeight + dp(18);
        float gap = dp(10);
        float cardWidth = (w - 2 * pad - gap) / 2f;
        drawDamCard(c, pad, cardsTop, cardWidth, h - cardsTop - pad, taio, "Taió");
        drawDamCard(c, pad + cardWidth + gap, cardsTop, cardWidth, h - cardsTop - pad, ituporanga, "Ituporanga");
    }

    private void drawLegend(Canvas c, float x, float y) {
        paint.setColor(Color.rgb(21, 101, 192));
        paint.setStrokeWidth(dp(3));
        c.drawLine(x, y + dp(5), x + dp(18), y + dp(5), paint);
        text(c, "nível do rio", x + dp(24), y + dp(9), 10, Color.rgb(102, 112, 133), false);
    }

    private void drawRiverChart(Canvas c, float left, float top, float right, float height) {
        float graphLeft = left + dp(30);
        float graphRight = right - dp(4);
        float graphTop = top + dp(12);
        float graphBottom = top + height - dp(24);
        int grid = Color.rgb(225, 232, 241);
        stroke.setColor(grid);
        stroke.setStrokeWidth(dp(1));
        for (int i = 0; i < 4; i++) {
            float y = graphTop + (graphBottom - graphTop) * i / 3f;
            c.drawLine(graphLeft, y, graphRight, y, stroke);
        }
        drawAlertBands(c, graphLeft, graphRight, graphTop, graphBottom);

        if (riverSamples.size() < 2) {
            Double current = currentRiverLevel != null ? currentRiverLevel
                    : (riverSamples.isEmpty() ? null : riverSamples.get(0).value);
            String message = current == null
                    ? "Aguardando a primeira leitura…"
                    : String.format(new Locale("pt", "BR"), "Leitura atual: %.2f m", current);
            text(c, message, graphLeft, (graphTop + graphBottom) / 2f + dp(5), 12, Color.rgb(102, 112, 133), false);
            if (current != null) {
                paint.setColor(Color.rgb(21, 101, 192));
                c.drawCircle(graphRight, (graphTop + graphBottom) / 2f - dp(8), dp(6), paint);
            }
            return;
        }
        double min = 0.0, max = 8.0, range = max - min;
        text(c, String.format(Locale.US, "%.2f m", max), left, graphTop + dp(4), 9, Color.rgb(102, 112, 133), false);
        text(c, String.format(Locale.US, "%.2f m", min), left, graphBottom, 9, Color.rgb(102, 112, 133), false);

        long start = riverSamples.get(0).timeMillis;
        long end = riverSamples.get(riverSamples.size() - 1).timeMillis;
        if (end <= start) end = start + 1;
        Path line = new Path();
        Path area = new Path();
        for (int i = 0; i < riverSamples.size(); i++) {
            Sample s = riverSamples.get(i);
            float x = graphLeft + (float) ((s.timeMillis - start) / (double) (end - start)) * (graphRight - graphLeft);
            float y = graphBottom - (float) ((s.value - min) / range) * (graphBottom - graphTop);
            if (i == 0) { line.moveTo(x, y); area.moveTo(x, graphBottom); area.lineTo(x, y); }
            else { line.lineTo(x, y); area.lineTo(x, y); }
        }
        area.lineTo(graphRight, graphBottom); area.close();
        paint.setColor(Color.argb(45, 21, 101, 192));
        c.drawPath(area, paint);
        stroke.setColor(Color.rgb(21, 101, 192));
        stroke.setStrokeWidth(dp(3));
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        c.drawPath(line, stroke);
        Sample last = riverSamples.get(riverSamples.size() - 1);
        float lastY = graphBottom - (float) ((last.value - min) / range) * (graphBottom - graphTop);
        paint.setColor(Color.rgb(21, 101, 192));
        c.drawCircle(graphRight, lastY, dp(5), paint);
        String stamp = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(last.timeMillis));
        text(c, stamp, graphRight - dp(30), graphBottom + dp(17), 9, Color.rgb(102, 112, 133), false);
    }

    private void drawAlertBands(Canvas c, float left, float right, float top, float bottom) {
        float y45 = alertY(4.5, top, bottom);
        float y55 = alertY(5.5, top, bottom);
        float y65 = alertY(6.5, top, bottom);
        paint.setColor(Color.argb(34, 31, 164, 103)); c.drawRect(left, y45, right, bottom, paint);
        paint.setColor(Color.argb(34, 248, 199, 33)); c.drawRect(left, y55, right, y45, paint);
        paint.setColor(Color.argb(34, 239, 133, 45)); c.drawRect(left, y65, right, y55, paint);
        paint.setColor(Color.argb(34, 220, 76, 70)); c.drawRect(left, top, right, y65, paint);
        stroke.setColor(Color.argb(125, 150, 110, 70));
        stroke.setStrokeWidth(dp(1));
        c.drawLine(left, y45, right, y45, stroke);
        c.drawLine(left, y55, right, y55, stroke);
        c.drawLine(left, y65, right, y65, stroke);
        text(c, "4,50", right - dp(82), y45 - dp(3), 8, Color.rgb(102, 112, 133), false);
        text(c, "5,50", right - dp(56), y55 - dp(3), 8, Color.rgb(102, 112, 133), false);
        text(c, "6,50", right - dp(30), y65 - dp(3), 8, Color.rgb(102, 112, 133), false);
    }

    private float alertY(double level, float top, float bottom) {
        return bottom - (float) (level / 8.0) * (bottom - top);
    }

    private String alertForLevel(Double level) {
        if (level == null) return "Aguardando";
        if (level < 4.5) return "Normal";
        if (level < 5.5) return "Atenção";
        if (level < 6.5) return "Alerta";
        return "Emergência";
    }

    private int alertColor(Double level) {
        if (level == null || level < 4.5) return Color.rgb(20, 124, 77);
        if (level < 5.5) return Color.rgb(133, 100, 0);
        if (level < 6.5) return Color.rgb(184, 87, 13);
        return Color.rgb(177, 32, 32);
    }

    private int panelColor(Double level) {
        if (level == null || level < 4.5) return Color.rgb(246, 253, 249);
        if (level < 5.5) return Color.rgb(255, 253, 237);
        if (level < 6.5) return Color.rgb(255, 247, 238);
        return Color.rgb(255, 241, 241);
    }

    private void drawDamCard(Canvas c, float left, float top, float width, float height, DamReading dam, String fallbackName) {
        paint.setColor(Color.rgb(248, 250, 253));
        c.drawRoundRect(new RectF(left, top, left + width, top + height), dp(12), dp(12), paint);
        if (dam == null) {
            text(c, fallbackName, left + dp(12), top + dp(24), 13, Color.rgb(15, 47, 82), true);
            text(c, "Sem dados", left + dp(12), top + dp(52), 12, Color.rgb(102, 112, 133), false);
            return;
        }
        text(c, dam.name == null ? fallbackName : dam.name.replace("Barragem Oeste ", "").replace("Barragem Sul ", ""), left + dp(12), top + dp(24), 13, Color.rgb(15, 47, 82), true);
        text(c, String.format(new Locale("pt", "BR"), "%.2f m", dam.levelMeters), left + dp(12), top + dp(50), 18, Color.rgb(21, 101, 192), true);
        text(c, String.format(new Locale("pt", "BR"), "%.1f%% ocupado", dam.capacityPercent), left + dp(12), top + dp(69), 10, Color.rgb(102, 112, 133), false);

        float barLeft = left + dp(12), barRight = left + width - dp(12), barTop = top + dp(80), barHeight = dp(8);
        paint.setColor(Color.rgb(224, 232, 241));
        c.drawRoundRect(new RectF(barLeft, barTop, barRight, barTop + barHeight), dp(4), dp(4), paint);
        float pct = (float) Math.max(0.0, Math.min(100.0, dam.capacityPercent));
        paint.setColor(pct >= 80 ? Color.rgb(220, 76, 70) : Color.rgb(21, 145, 104));
        c.drawRoundRect(new RectF(barLeft, barTop, barLeft + (barRight - barLeft) * pct / 100f, barTop + barHeight), dp(4), dp(4), paint);

        text(c, "Comportas", left + dp(12), top + dp(108), 10, Color.rgb(102, 112, 133), true);
        String gateLabel = dam.gatesOpen + "/" + dam.gatesTotal + " abertas";
        text(c, gateLabel, left + dp(12), top + dp(126), 11, Color.rgb(15, 47, 82), false);
        drawGates(c, left + dp(12), top + dp(140), width - dp(24), dam);
    }

    private void drawGates(Canvas c, float left, float top, float width, DamReading dam) {
        int total = Math.max(1, dam.gatesTotal);
        float gap = dp(3);
        float segment = (width - gap * (total - 1)) / total;
        for (int i = 0; i < total; i++) {
            boolean open = dam.gateStates != null && i < dam.gateStates.length
                    ? dam.gateStates[i] : i < dam.gatesOpen;
            paint.setColor(open ? Color.rgb(22, 155, 105) : Color.rgb(218, 91, 82));
            float x = left + i * (segment + gap);
            c.drawRoundRect(new RectF(x, top, x + segment, top + dp(18)), dp(4), dp(4), paint);
            text(c, open ? "A" : "F", x + segment / 2f - dp(4), top + dp(13), 9, Color.WHITE, true);
        }
    }
}
