package br.com.riodosul.niveldorio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Tendência de 24 h de uma barragem: nível do reservatório (área com degradê, mínimo, máximo e
 * valor atual) e, embaixo, a chuva hora a hora com ícone animado de sol ou chuva.
 * A linha entra da esquerda para a direita, um reflexo corre sobre ela e as barras de chuva crescem
 * em sequência. As comportas ficam nos cards da tela e não se repetem aqui.
 */
public class DamChartView extends View {
    private static final Locale BR = new Locale("pt", "BR");
    private static final int INK = Color.rgb(28, 65, 94);
    private static final int GREY = Color.rgb(102, 112, 133);
    private static final int SOFT = Color.rgb(150, 158, 168);
    private static final int GRID = Color.rgb(232, 237, 244);
    private static final int BLUE = Color.rgb(21, 101, 192);
    private static final int SKY = Color.rgb(129, 212, 250);
    private static final int RAIN = Color.rgb(2, 132, 199);
    private static final long HOUR = 3600000L;
    private static final long DAY = 24L * HOUR;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private DamReading dam;
    private long revealStart = System.currentTimeMillis();

    public DamChartView(Context context) {
        this(context, null);
    }

    public DamChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
    }

    public void setDam(DamReading dam) {
        this.dam = dam;
        this.revealStart = System.currentTimeMillis();
        invalidate();
    }

    private float dp(float v) { return v * density; }

    private static float smooth(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    private float text(Canvas c, String s, float x, float y, float size, int color, boolean bold) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setPathEffect(null);
        paint.setColor(color);
        paint.setTextSize(dp(size));
        paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        c.drawText(s, x, y, paint);
        return paint.measureText(s);
    }

    private float measure(String s, float size, boolean bold) {
        paint.setTextSize(dp(size));
        paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return paint.measureText(s);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        long now = System.currentTimeMillis();
        try {
            draw(c, w, h, now);
        } catch (RuntimeException e) {
            text(c, "Não foi possível desenhar o histórico agora.", dp(4), h / 2f, 12, GREY, false);
        }
        if (isShown()) postInvalidateDelayed(33);
    }

    private void draw(Canvas c, float w, float h, long now) {
        List<DamPoint> history = new ArrayList<>();
        if (dam != null) {
            for (DamPoint p : dam.history) {
                if (p != null && p.timeMillis > 0 && !Double.isNaN(p.levelMeters) && p.levelMeters > 0) history.add(p);
            }
        }
        if (history.size() < 2) {
            text(c, "Ainda não há histórico de 24 h desta barragem.", dp(4), h * 0.42f, 12, GREY, false);
            text(c, "Volte à tela inicial, puxe para atualizar e abra de novo.", dp(4), h * 0.42f + dp(18), 10.5f, SOFT, false);
            return;
        }

        DamPoint first = history.get(0), last = history.get(history.size() - 1);
        long t1 = Math.max(last.timeMillis, Math.min(now, last.timeMillis + HOUR));
        long t0 = t1 - DAY;

        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        DamPoint pMin = first, pMax = first;
        for (DamPoint p : history) {
            if (p.timeMillis < t0) continue;
            if (p.levelMeters < min) { min = p.levelMeters; pMin = p; }
            if (p.levelMeters > max) { max = p.levelMeters; pMax = p; }
        }
        double range = Math.max(0.10, max - min);
        double step = niceStep(range / 3.0);
        double yMin = Math.floor((min - range * 0.15) / step) * step;
        double yMax = Math.ceil((max + range * 0.25) / step) * step;

        float reveal = smooth((now - revealStart) / 1200f);

        // ---- Cabeçalho: variação em 24 h.
        double change = last.levelMeters - first.levelMeters;
        String trend = Math.abs(change) < 0.01 ? "estável em 24 h"
                : (change > 0 ? "subiu " : "baixou ") + String.format(BR, "%.2f m em 24 h", Math.abs(change));
        int trendColor = Math.abs(change) < 0.01 ? GREY : change > 0 ? Color.rgb(221, 107, 18) : Color.rgb(31, 145, 91);
        String arrow = Math.abs(change) < 0.01 ? "→" : change > 0 ? "↗" : "↘";
        text(c, "NÍVEL DO RESERVATÓRIO", 0, dp(12), 9.5f, BLUE, true);
        String chip = arrow + " " + trend;
        float cw = measure(chip, 10, true);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(trendColor);
        paint.setAlpha(28);
        c.drawRoundRect(new RectF(w - cw - dp(16), 0, w, dp(18)), dp(9), dp(9), paint);
        text(c, chip, w - cw - dp(8), dp(12.5f), 10, trendColor, true);

        // ---- Área do gráfico de nível.
        String sample = String.format(BR, "%.1f", yMax);
        float gutter = measure(sample, 9, false) + dp(8);
        float left = gutter, right = w - dp(16);
        float top = dp(30), bottom = h * 0.60f;

        // Grade horizontal com rótulos.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, density * 0.8f));
        for (double v = yMin; v <= yMax + step * 0.01; v += step) {
            float y = y(v, yMin, yMax, top, bottom);
            paint.setColor(GRID);
            paint.setPathEffect(null);
            c.drawLine(left, y, right, y, paint);
            String label = String.format(BR, step < 0.1 ? "%.2f" : "%.1f", v);
            text(c, label, left - measure(label, 9, false) - dp(5), y + dp(3), 9, SOFT, false);
            paint.setStyle(Paint.Style.STROKE);
        }

        // Grade vertical e eixo do tempo (compartilhado com a chuva).
        float rainTop = bottom + dp(52), rainBottom = h - dp(22);
        drawTimeAxis(c, t0, t1, left, right, top, rainBottom);

        // Série (suavizada) e área com degradê.
        Path line = new Path(), fill = new Path();
        float lastX = 0, lastY = 0, firstX = 0;
        boolean started = false;
        float prevX = 0, prevY = 0;
        for (DamPoint p : history) {
            if (p.timeMillis < t0) continue;
            float px = x(p.timeMillis, t0, t1, left, right);
            float py = y(p.levelMeters, yMin, yMax, top, bottom);
            if (!started) {
                line.moveTo(px, py);
                fill.moveTo(px, bottom);
                fill.lineTo(px, py);
                firstX = px;
                started = true;
            } else {
                float mx = (prevX + px) / 2f;
                line.cubicTo(mx, prevY, mx, py, px, py);
                fill.cubicTo(mx, prevY, mx, py, px, py);
            }
            prevX = px;
            prevY = py;
            lastX = px;
            lastY = py;
        }
        fill.lineTo(lastX, bottom);
        fill.lineTo(firstX, bottom);
        fill.close();

        c.save();
        c.clipRect(left, top - dp(8), left + (right - left) * reveal + dp(2), bottom + dp(2));
        paint.setStyle(Paint.Style.FILL);
        paint.setPathEffect(null);
        paint.setShader(new LinearGradient(0, top, 0, bottom, Color.argb(110, 33, 150, 243), Color.argb(8, 33, 150, 243),
                Shader.TileMode.CLAMP));
        c.drawPath(fill, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(BLUE);
        paint.setStrokeWidth(dp(2.6f));
        c.drawPath(line, paint);
        if (reveal >= 1f) drawFlow(c, line, (now % 4200L) / 4200f);
        c.restore();

        if (reveal >= 1f) {
            // Mínimo e máximo do período.
            if (pMax != last && max - min >= 0.02) marker(c, pMax, "máx", t0, t1, left, right, top, bottom, yMin, yMax);
            if (pMin != last && max - min >= 0.02) marker(c, pMin, "mín", t0, t1, left, right, top, bottom, yMin, yMax);
            drawCurrent(c, last, lastX, lastY, left, right, top, bottom, change < 0, now);
        }

        // ---- Chuva por hora.
        drawRain(c, t0, t1, left, right, rainTop, rainBottom, reveal, now, w);
    }

    /** Linhas verticais a cada 6 h e rótulos de hora sem sobrepor; marca "agora" no fim. */
    private void drawTimeAxis(Canvas c, long t0, long t1, float left, float right, float top, float bottom) {
        long six = 6L * HOUR;
        long offset = TimeZone.getDefault().getOffset(t0);
        long firstTick = ((t0 + offset) / six + 1) * six - offset;
        SimpleDateFormat hour = new SimpleDateFormat("HH'h'", BR);
        float lastEnd = -1e9f;
        float labelY = bottom + dp(15);
        for (long t = firstTick; t < t1; t += six) {
            float x = x(t, t0, t1, left, right);
            paint.setStyle(Paint.Style.STROKE);
            paint.setPathEffect(new DashPathEffect(new float[] { dp(2), dp(3) }, 0));
            paint.setColor(GRID);
            paint.setStrokeWidth(Math.max(1f, density * 0.8f));
            c.drawLine(x, top, x, bottom, paint);
            paint.setPathEffect(null);
            String label = hour.format(new Date(t));
            float lw = measure(label, 9, false);
            float lx = Math.max(left, Math.min(right - lw, x - lw / 2f));
            if (lx > lastEnd + dp(6) && lx + lw < right - dp(34)) {
                text(c, label, lx, labelY, 9, SOFT, false);
                lastEnd = lx + lw;
            }
        }
        String nowLabel = "agora";
        text(c, nowLabel, right - measure(nowLabel, 9, true), labelY, 9, INK, true);
    }

    private void marker(Canvas c, DamPoint p, String tag, long t0, long t1, float left, float right,
                        float top, float bottom, double yMin, double yMax) {
        float px = x(p.timeMillis, t0, t1, left, right);
        float py = y(p.levelMeters, yMin, yMax, top, bottom);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(Color.WHITE);
        c.drawCircle(px, py, dp(3.6f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.6f));
        paint.setColor(BLUE);
        c.drawCircle(px, py, dp(3.6f), paint);
        String label = tag + " " + String.format(BR, "%.2f", p.levelMeters);
        float lw = measure(label, 8.5f, false);
        float lx = Math.max(left + dp(2), Math.min(right - lw - dp(60), px - lw / 2f));
        boolean above = "máx".equals(tag);
        float ly = above ? Math.max(top + dp(9), py - dp(7)) : Math.min(bottom - dp(4), py + dp(14));
        text(c, label, lx, ly, 8.5f, GREY, false);
    }

    /** Ponto atual com halo pulsando e etiqueta com nível e ocupação. */
    private void drawCurrent(Canvas c, DamPoint last, float x, float y, float left, float right, float top,
                             float bottom, boolean falling, long now) {
        float pulse = (now % 1800L) / 1800f;
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(BLUE);
        paint.setAlpha((int) (110 * (1f - pulse)));
        c.drawCircle(x, y, dp(6) + dp(10) * pulse, paint);
        paint.setAlpha(255);
        paint.setColor(Color.WHITE);
        c.drawCircle(x, y, dp(5.2f), paint);
        paint.setColor(BLUE);
        c.drawCircle(x, y, dp(3.4f), paint);

        String value = String.format(BR, "%.2f m", last.levelMeters);
        String pct = Double.isNaN(last.capacityPercent) ? "" : String.format(BR, "  %.0f%%", last.capacityPercent);
        float vw = measure(value, 10.5f, true), pw = pct.isEmpty() ? 0 : measure(pct, 9.5f, false);
        float bw = vw + pw + dp(14), bh = dp(20);
        float bx = Math.min(right - bw, Math.max(left, x - bw - dp(12)));
        // A linha chega ao ponto atual vindo de cima quando o nível cai (e de baixo quando sobe):
        // a etiqueta vai para o lado vazio.
        boolean below = falling ? y + dp(14) + bh <= bottom : y - bh - dp(14) < top - dp(6);
        float by = below ? y + dp(14) : y - bh - dp(14);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(INK);
        paint.setAlpha(235);
        c.drawRoundRect(new RectF(bx, by, bx + bw, by + bh), dp(6), dp(6), paint);
        paint.setAlpha(255);
        text(c, value, bx + dp(7), by + dp(14), 10.5f, Color.WHITE, true);
        if (!pct.isEmpty()) text(c, pct, bx + dp(7) + vw, by + dp(14), 9.5f, Color.rgb(187, 222, 251), false);
    }

    /** Reflexo que corre sobre a linha, como a correnteza nos outros gráficos. */
    private void drawFlow(Canvas c, Path line, float flow) {
        PathMeasure measure = new PathMeasure(line, false);
        float total = measure.getLength();
        if (total < dp(20)) return;
        float seg = Math.min(total, dp(40));
        float head = flow * (total + seg);
        float start = Math.max(0f, head - seg), end = Math.min(total, head);
        if (end - start < dp(2)) return;
        int alpha = (int) (190f * (float) Math.sin(Math.PI * flow));
        if (alpha <= 8) return;
        Path piece = new Path();
        if (!measure.getSegment(start, end, piece, true)) return;
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(SKY);
        paint.setAlpha(alpha / 2);
        paint.setStrokeWidth(dp(8f));
        c.drawPath(piece, paint);
        paint.setColor(Color.WHITE);
        paint.setAlpha(alpha);
        paint.setStrokeWidth(dp(2.4f));
        c.drawPath(piece, paint);
        paint.setAlpha(255);
    }

    // ------------------------------------------------------------------ chuva

    private void drawRain(Canvas c, long t0, long t1, float left, float right, float top, float bottom,
                          float reveal, long now, float w) {
        List<Sample> rain = new ArrayList<>();
        double total = 0, peak = 0;
        long peakTime = 0;
        if (dam != null) {
            for (Sample s : dam.rainHourly) {
                if (s == null || Double.isNaN(s.value) || s.value < 0 || s.timeMillis <= t0 || s.timeMillis > t1 + HOUR) continue;
                rain.add(s);
                total += s.value;
                if (s.value > peak) {
                    peak = s.value;
                    peakTime = s.timeMillis;
                }
            }
        }
        boolean known = dam != null && (!rain.isEmpty() || !Double.isNaN(dam.rain24hMm));
        if (dam != null && !Double.isNaN(dam.rain24hMm)) total = dam.rain24hMm;

        // Título com ícone animado (sol ou chuva).
        float iconSize = dp(30);
        float titleY = top - dp(22);
        WeatherIcon.draw(c, paint, iconSize / 2f, titleY - dp(3), iconSize, known ? total : Double.NaN, now);
        float tx = iconSize + dp(6);
        text(c, "CHUVA POR HORA", tx, titleY - dp(4), 9.5f, RAIN, true);
        String sub;
        if (!known) sub = "sem dados de chuva nesta consulta";
        else if (total < 0.2) sub = String.format(BR, "não choveu nas últimas 24 h (%.1f mm)", total);
        else sub = String.format(BR, "%.1f mm em 24 h • pico de %.1f mm às %s", total, peak,
                    new SimpleDateFormat("HH'h'", BR).format(new Date(peakTime - HOUR)));
        text(c, sub, tx, titleY + dp(9), 9.5f, known && total >= 0.2 ? INK : GREY, known && total >= 0.2);

        // Base e escala.
        double scaleMax = Math.max(2.0, Math.ceil(peak));
        paint.setShader(null);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, density));
        paint.setColor(Color.rgb(214, 223, 233));
        c.drawLine(left, bottom, right, bottom, paint);
        paint.setColor(GRID);
        float mid = bottom - (bottom - top) / 2f;
        c.drawLine(left, top, right, top, paint);
        c.drawLine(left, mid, right, mid, paint);
        String topLabel = String.format(BR, "%.0f", scaleMax);
        text(c, topLabel, left - measure(topLabel, 9, false) - dp(5), top + dp(3), 9, SOFT, false);
        text(c, "mm", left - measure("mm", 8, false) - dp(5), bottom - dp(2), 8, SOFT, false);

        if (!known || total < 0.2) {
            // Sem chuva: uma linha d'água calma ondulando na base.
            Path calm = new Path();
            float phase = (now % 5000L) / 5000f;
            for (float x = left; x <= right; x += dp(4)) {
                float yy = bottom - dp(3) + (float) Math.sin(((x - left) / dp(40) + phase) * 2 * Math.PI) * dp(1.5f);
                if (x == left) calm.moveTo(x, yy); else calm.lineTo(x, yy);
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.6f));
            paint.setColor(SKY);
            c.drawPath(calm, paint);
            return;
        }

        float slot = (right - left) * HOUR / (float) (t1 - t0);
        float barW = Math.max(dp(3), slot * 0.62f);
        for (int i = 0; i < rain.size(); i++) {
            Sample s = rain.get(i);
            if (s.value < 0.05) continue;
            float local = smooth((reveal * rain.size() - i) / 3f);
            if (local <= 0f) continue;
            float xEnd = x(s.timeMillis, t0, t1, left, right);
            float xc = xEnd - slot / 2f;
            float full = (bottom - top) * (float) Math.min(1.0, s.value / scaleMax);
            float barTop = bottom - full * local;
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0, barTop, 0, bottom, Color.rgb(3, 169, 244), Color.rgb(2, 119, 189),
                    Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(xc - barW / 2f, barTop, xc + barW / 2f, bottom), dp(2.5f), dp(2.5f), paint);
            paint.setShader(null);
            if (s.timeMillis == peakTime && local >= 1f) {
                String v = String.format(BR, "%.1f", s.value);
                float vw = measure(v, 8.5f, true);
                text(c, v, Math.max(left, Math.min(right - vw, xc - vw / 2f)), barTop - dp(3), 8.5f, RAIN, true);
            }
        }
    }

    // ------------------------------------------------------------------ util

    private static double niceStep(double raw) {
        if (raw <= 0) return 0.1;
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double n = raw / mag;
        double nice = n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10;
        return nice * mag;
    }

    private static float x(long t, long t0, long t1, float left, float right) {
        return left + (float) ((t - t0) / (double) Math.max(1, t1 - t0)) * (right - left);
    }

    private static float y(double v, double min, double max, float top, float bottom) {
        double clamped = Math.max(min, Math.min(max, v));
        return bottom - (float) ((clamped - min) / Math.max(1e-9, max - min)) * (bottom - top);
    }
}
