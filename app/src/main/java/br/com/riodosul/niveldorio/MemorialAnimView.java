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
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ilustrações animadas do memorial descritivo (o papel dos "gifs"): são desenhadas pelo próprio
 * app, em laço, a partir dos dados reais da consulta — sem arquivos de imagem.
 */
public class MemorialAnimView extends View {
    public static final int HERO = 0;
    public static final int TREND = 1;
    public static final int UPSTREAM = 2;
    public static final int DAMS = 3;
    public static final int RAIN = 4;
    public static final int BARS = 5;

    private static final Locale BR = new Locale("pt", "BR");
    private static final int BLUE = Color.rgb(21, 101, 192);
    private static final int PURPLE = Color.rgb(123, 31, 162);
    private static final int INK = Color.rgb(28, 65, 94);
    private static final int GREY = Color.rgb(102, 112, 133);

    private final int type;
    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final long startedAt = System.currentTimeMillis();

    private Forecast forecast;
    private List<Sample> samples = new ArrayList<>();
    private DamReading taio, ituporanga;

    public MemorialAnimView(Context context, int type) {
        super(context);
        this.type = type;
        density = getResources().getDisplayMetrics().density;
    }

    public void setData(Forecast forecast, List<Sample> samples, DamReading taio, DamReading ituporanga) {
        this.forecast = forecast;
        this.samples = samples == null ? new ArrayList<Sample>() : samples;
        this.taio = taio;
        this.ituporanga = ituporanga;
        invalidate();
    }

    private float dp(float v) { return v * density; }

    /** Fração 0..1 que se repete a cada "periodMs". */
    private float loop(long periodMs) {
        return ((System.currentTimeMillis() - startedAt) % periodMs) / (float) periodMs;
    }

    private float text(Canvas c, String value, float x, float y, float size, int color, boolean bold) {
        paint.setShader(null);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(dp(size));
        paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        c.drawText(value, x, y, paint);
        return paint.measureText(value);
    }

    private float textCentered(Canvas c, String value, float cx, float y, float size, int color, boolean bold) {
        paint.setTextSize(dp(size));
        paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        float w = paint.measureText(value);
        text(c, value, cx - w / 2f, y, size, color, bold);
        return w;
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        try {
            switch (type) {
                case HERO: drawHero(c, w, h); break;
                case TREND: drawTrend(c, w, h); break;
                case UPSTREAM: drawUpstream(c, w, h); break;
                case DAMS: drawDams(c, w, h); break;
                case RAIN: drawRain(c, w, h); break;
                default: drawBars(c, w, h); break;
            }
        } catch (RuntimeException ignored) {
            // Ilustração é enfeite: um dado inesperado não pode fechar a tela.
        }
        if (isShown()) postInvalidateDelayed(33);
    }

    // ------------------------------------------------------------------ destaque

    private void drawHero(Canvas c, float w, float h) {
        RectF card = new RectF(0, 0, w, h);
        path.reset();
        path.addRoundRect(card, dp(18), dp(18), Path.Direction.CW);
        c.save();
        c.clipPath(path);

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, 0, h, Color.rgb(10, 37, 84), Color.rgb(25, 86, 160), Shader.TileMode.CLAMP));
        c.drawRect(card, paint);
        paint.setShader(null);

        boolean ok = forecast != null && forecast.available
                && finite(forecast.currentLevel) && finite(forecast.predictedLevel);
        Cotas cotas = forecast == null ? new Cotas() : forecast.cotas;
        double top = cotas.topoDaEscala(ok ? forecast.currentLevel : Double.NaN, ok ? forecast.predictedLevel : Double.NaN);
        float tankTop = dp(96), tankBottom = h;

        // A água sobe (ou desce) do nível atual para o previsto e volta, em laço.
        float t = loop(5200);
        float ease = t < 0.15f ? 0f : t < 0.55f ? smooth((t - 0.15f) / 0.40f) : t < 0.9f ? 1f : 1f - smooth((t - 0.9f) / 0.1f);
        double shown = ok ? forecast.currentLevel + (forecast.predictedLevel - forecast.currentLevel) * ease : 0;
        // Amplia a diferença para ela ser visível mesmo quando são poucos centímetros.
        float baseFrac = ok ? (float) (forecast.currentLevel / top) : 0.4f;
        float exaggerated = ok ? (float) ((shown - forecast.currentLevel) * 100.0) * dp(1.2f) : 0;
        exaggerated = Math.max(-dp(26), Math.min(dp(26), exaggerated));
        float surface = tankBottom - (tankBottom - tankTop) * Math.max(0.12f, Math.min(0.9f, baseFrac)) - exaggerated;

        float p = loop(4200);
        drawWater(c, w, h, surface + dp(7), dp(6), p, Color.argb(120, 66, 165, 245));
        drawWater(c, w, h, surface + dp(3), dp(5), 1f - p + 0.37f, Color.argb(150, 41, 121, 255));
        drawWater(c, w, h, surface, dp(4), p * 2f + 0.61f, Color.argb(220, 33, 150, 243));

        // Régua com as cotas, à direita.
        if (ok) {
            float rx = w - dp(14);
            double[] marks = { cotas.atencao, cotas.alerta, cotas.emergencia };
            int[] colors = { Cotas.COR_ATENCAO, Cotas.COR_ALERTA, Cotas.COR_EMERGENCIA };
            String[] names = { "atenção", "alerta", "emergência" };
            for (int i = 0; i < 3; i++) {
                float y = tankBottom - (tankBottom - tankTop) * (float) (marks[i] / top);
                if (y < tankTop - dp(4)) continue;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setColor(colors[i]);
                c.drawLine(rx - dp(18), y, rx, y, paint);
                String label = String.format(BR, "%s %.2f", names[i], marks[i]);
                paint.setTextSize(dp(9));
                paint.setTypeface(Typeface.DEFAULT_BOLD);
                text(c, label, rx - dp(22) - paint.measureText(label), y + dp(3), 9, Color.WHITE, true);
            }
        }
        c.restore();

        float left = dp(18);
        text(c, "PREVISÃO CENTRALIZADA · 1, 2 E 3 HORAS", left, dp(26), 9.5f, Color.argb(215, 255, 255, 255), true);
        if (forecast != null && forecast.bridge != null) {
            text(c, forecast.bridge.displayName + " — " + forecast.bridge.river, left, dp(42), 11, Color.argb(215, 255, 255, 255), false);
        }
        if (!ok) {
            text(c, forecast == null || forecast.unavailableReason.isEmpty() ? "Indisponível agora" : forecast.unavailableReason, left, dp(80), 16, Color.WHITE, true);
            return;
        }
        String value = String.format(BR, "%.2f", forecast.predictedLevel);
        float vw = text(c, value, left, dp(88), 40, Color.WHITE, true);
        float mw = text(c, "m", left + vw + dp(5), dp(88), 18, Color.WHITE, true);

        String delta = forecast.arrow() + " " + (forecast.deltaCm >= 0 ? "+" : "−") + String.format(BR, "%.0f cm", Math.abs(forecast.deltaCm));
        paint.setTextSize(dp(14));
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        float dw = paint.measureText(delta);
        float chipX = left + vw + mw + dp(14);
        int chip = forecast.deltaCm >= 1 ? Color.rgb(239, 83, 80) : forecast.deltaCm <= -1 ? Color.rgb(38, 166, 107) : Color.rgb(120, 144, 156);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(chip);
        float pulse = 1f + 0.04f * (float) Math.sin(loop(1400) * 2 * Math.PI);
        c.save();
        c.scale(pulse, pulse, chipX + (dw + dp(18)) / 2f, dp(74));
        c.drawRoundRect(new RectF(chipX, dp(60), chipX + dw + dp(18), dp(89)), dp(15), dp(15), paint);
        text(c, delta, chipX + dp(9), dp(80), 14, Color.WHITE, true);
        c.restore();
    }

    private void drawWater(Canvas c, float w, float h, float baseY, float amp, float phase, int color) {
        path.reset();
        path.moveTo(0, h);
        float len = w / 1.4f;
        for (float x = 0; x <= w + dp(6); x += dp(6)) {
            path.lineTo(x, baseY + (float) Math.sin((x / len + phase) * 2 * Math.PI) * amp);
        }
        path.lineTo(w, h);
        path.close();
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawPath(path, paint);
    }

    private static float smooth(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    // ------------------------------------------------------------------ tendência

    private void drawTrend(Canvas c, float w, float h) {
        if (forecast == null || !forecast.available) return;
        long end = forecast.baseTime;
        long from = end - 3L * 3600000L;
        List<Sample> pts = new ArrayList<>();
        for (Sample s : samples) if (s.timeMillis >= from && s.timeMillis <= end + 60000L) pts.add(s);
        if (pts.size() < 2) {
            textCentered(c, "Sem leituras das últimas 3 horas para ilustrar", w / 2f, h / 2f, 11, GREY, false);
            return;
        }
        double min = forecast.predictedLevel - forecast.bandCm / 100.0, max = forecast.predictedLevel + forecast.bandCm / 100.0;
        for (Sample s : pts) { min = Math.min(min, s.value); max = Math.max(max, s.value); }
        double pad = Math.max(0.03, (max - min) * 0.18);
        min -= pad; max += pad;

        float left = dp(46), right = w - dp(64), top = dp(14), bottom = h - dp(26);
        long t1 = forecast.targetTime;

        paint.setStyle(Paint.Style.STROKE);
        paint.setPathEffect(null);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Color.rgb(226, 232, 240));
        for (int i = 0; i <= 2; i++) {
            float gy = top + (bottom - top) * i / 2f;
            c.drawLine(left, gy, right, gy, paint);
            double v = max - (max - min) * i / 2.0;
            text(c, String.format(BR, "%.2f", v), dp(8), gy + dp(3.5f), 9, GREY, false);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(Color.rgb(226, 232, 240));
        }
        text(c, "3 h atrás", left, h - dp(8), 9, GREY, false);
        float nowX = tx(end, from, t1, left, right);
        textCentered(c, "agora", nowX, h - dp(8), 9, GREY, true);
        textCentered(c, "+1 h", right, h - dp(8), 9, PURPLE, true);

        // A linha medida se desenha, depois a previsão se estende; então recomeça.
        float t = loop(4800);
        float drawn = Math.min(1f, t / 0.45f);
        float projected = t < 0.45f ? 0f : Math.min(1f, (t - 0.45f) / 0.25f);

        path.reset();
        for (int i = 0; i < pts.size(); i++) {
            float px = tx(pts.get(i).timeMillis, from, t1, left, right);
            float py = ty(pts.get(i).value, min, max, top, bottom);
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        PathMeasure measure = new PathMeasure(path, false);
        Path partial = new Path();
        measure.getSegment(0, measure.getLength() * drawn, partial, true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.6f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(BLUE);
        c.drawPath(partial, paint);

        float lastX = tx(pts.get(pts.size() - 1).timeMillis, from, t1, left, right);
        float lastY = ty(forecast.currentLevel, min, max, top, bottom);
        float fx = right, fy = ty(forecast.predictedLevel, min, max, top, bottom);
        if (drawn >= 1f) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(BLUE);
            c.drawCircle(lastX, lastY, dp(4), paint);
        }
        if (projected > 0f) {
            float ex = lastX + (fx - lastX) * projected, ey = lastY + (fy - lastY) * projected;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2.4f));
            paint.setColor(PURPLE);
            paint.setPathEffect(new DashPathEffect(new float[] { dp(6), dp(4) }, 0));
            Path dash = new Path();
            dash.moveTo(lastX, lastY);
            dash.lineTo(ex, ey);
            c.drawPath(dash, paint);
            paint.setPathEffect(null);
            if (projected >= 1f) {
                float by1 = ty(forecast.predictedLevel + forecast.bandCm / 100.0, min, max, top, bottom);
                float by2 = ty(forecast.predictedLevel - forecast.bandCm / 100.0, min, max, top, bottom);
                paint.setStrokeWidth(dp(1.5f));
                c.drawLine(fx, by1, fx, by2, paint);
                c.drawLine(fx - dp(4), by1, fx + dp(4), by1, paint);
                c.drawLine(fx - dp(4), by2, fx + dp(4), by2, paint);
                float pulse = dp(5) + dp(2) * (float) Math.sin(loop(900) * 2 * Math.PI);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb(60, 123, 31, 162));
                c.drawCircle(fx, fy, pulse + dp(4), paint);
                paint.setColor(PURPLE);
                c.drawCircle(fx, fy, dp(4.5f), paint);
                text(c, String.format(BR, "%.2f m", forecast.predictedLevel), fx + dp(8), fy + dp(4), 11, PURPLE, true);
            }
        }
    }

    private static float tx(long t, long t0, long t1, float left, float right) {
        return left + (float) ((t - t0) / (double) Math.max(1, t1 - t0)) * (right - left);
    }

    private static float ty(double v, double min, double max, float top, float bottom) {
        return bottom - (float) ((v - min) / Math.max(1e-6, max - min)) * (bottom - top);
    }

    // ------------------------------------------------------------------ montante

    private void drawUpstream(Canvas c, float w, float h) {
        float cx = w * 0.56f, cy = h * 0.52f;
        Path oeste = new Path();
        oeste.moveTo(dp(20), dp(26));
        oeste.cubicTo(w * 0.22f, dp(20), w * 0.34f, cy - dp(6), cx, cy);
        Path sul = new Path();
        sul.moveTo(dp(20), h - dp(26));
        sul.cubicTo(w * 0.22f, h - dp(18), w * 0.36f, cy + dp(8), cx, cy);
        Path acu = new Path();
        acu.moveTo(cx, cy);
        acu.cubicTo(w * 0.70f, cy + dp(4), w * 0.82f, cy - dp(12), w - dp(18), cy - dp(4));

        Bridge b = forecast == null ? null : forecast.bridge;
        drawRiver(c, oeste, dp(9), b == Bridge.RICARDO_KANITZ ? 0.35f : 1f);
        drawRiver(c, sul, dp(9), b == Bridge.BR470 ? 0.35f : 1f);
        drawRiver(c, acu, dp(13), b == Bridge.DOM_TITO ? 1f : 0.35f);

        float t = loop(3600);
        drawFlow(c, oeste, t, 4, b == Bridge.RICARDO_KANITZ ? 90 : 255);
        drawFlow(c, sul, t + 0.13f, 4, b == Bridge.BR470 ? 90 : 255);
        drawFlow(c, acu, t + 0.4f, 4, b == Bridge.DOM_TITO ? 255 : 90);

        text(c, "Itajaí do Oeste", dp(20), dp(14), 10, INK, true);
        text(c, "Itajaí do Sul", dp(20), h - dp(6), 10, INK, true);
        paint.setTextSize(dp(10));
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        String acuLabel = "Itajaí-Açu";
        text(c, acuLabel, w - dp(18) - paint.measureText(acuLabel), cy - dp(22), 10, INK, true);

        // Marca a ponte desta previsão.
        float[] pos = new float[2];
        Path where = b == Bridge.BR470 ? oeste : b == Bridge.RICARDO_KANITZ ? sul : acu;
        PathMeasure pm = new PathMeasure(where, false);
        pm.getPosTan(pm.getLength() * (b == Bridge.DOM_TITO ? 0.45f : 0.78f), pos, null);
        float pulse = dp(7) + dp(3) * (float) Math.sin(loop(1100) * 2 * Math.PI);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(70, 123, 31, 162));
        c.drawCircle(pos[0], pos[1], pulse + dp(4), paint);
        paint.setColor(PURPLE);
        c.drawCircle(pos[0], pos[1], dp(6), paint);
        paint.setColor(Color.WHITE);
        c.drawCircle(pos[0], pos[1], dp(2.5f), paint);
        if (b != null) textCentered(c, b.displayName, Math.max(dp(70), Math.min(w - dp(70), pos[0])), pos[1] + dp(24), 10, PURPLE, true);
    }

    private void drawRiver(Canvas c, Path river, float width, float strength) {
        paint.setShader(null);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(width);
        paint.setColor(Color.argb((int) (255 * (0.25f + 0.5f * strength)), 100, 181, 246));
        c.drawPath(river, paint);
    }

    private void drawFlow(Canvas c, Path river, float phase, int dots, int alpha) {
        PathMeasure pm = new PathMeasure(river, false);
        float[] pos = new float[2];
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < dots; i++) {
            float f = (phase + i / (float) dots) % 1f;
            pm.getPosTan(pm.getLength() * f, pos, null);
            paint.setColor(Color.argb(alpha, 13, 71, 161));
            c.drawCircle(pos[0], pos[1], dp(2.6f), paint);
        }
    }

    // ------------------------------------------------------------------ barragens

    private void drawDams(Canvas c, float w, float h) {
        float gap = dp(14);
        float each = (w - gap) / 2f;
        drawDam(c, 0, each, h, taio, "Taió");
        drawDam(c, each + gap, each, h, ituporanga, "Ituporanga");
    }

    private void drawDam(Canvas c, float x, float width, float h, DamReading d, String name) {
        textCentered(c, name, x + width / 2f, dp(13), 11, INK, true);
        if (d == null) {
            textCentered(c, "sem dados", x + width / 2f, h / 2f, 10, GREY, false);
            return;
        }
        float top = dp(22), bottom = h - dp(18);
        float wallX = x + width * 0.58f;
        float wallW = dp(12);

        // Reservatório com o nível proporcional à ocupação.
        float pct = (float) Math.max(0.05, Math.min(1.0, d.capacityPercent / 100.0));
        float surface = bottom - (bottom - top) * pct * 0.92f;
        float ripple = dp(1.5f) * (float) Math.sin(loop(2400) * 2 * Math.PI);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(100, 181, 246));
        c.drawRect(x + dp(4), surface + ripple, wallX, bottom, paint);
        textCentered(c, String.format(BR, "%.0f%%", d.capacityPercent), (x + dp(4) + wallX) / 2f,
                Math.min(bottom - dp(6), surface + dp(18)), 12, Color.WHITE, true);

        // Barragem.
        paint.setColor(Color.rgb(96, 108, 120));
        c.drawRect(wallX, top, wallX + wallW, bottom, paint);

        // Comportas: abertas soltam água (animada), fechadas ficam vermelhas.
        int total = Math.max(1, d.gatesTotal);
        float slot = (bottom - top - dp(6)) / total;
        float t = loop(900);
        for (int i = 0; i < total; i++) {
            boolean open = d.gateStates != null && i < d.gateStates.length ? d.gateStates[i] : i < d.gatesOpen;
            float gy = top + dp(3) + slot * i + slot / 2f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(open ? Color.rgb(31, 160, 106) : Color.rgb(218, 91, 82));
            c.drawRoundRect(new RectF(wallX + dp(2), gy - Math.min(dp(5), slot * 0.35f), wallX + wallW - dp(2),
                    gy + Math.min(dp(5), slot * 0.35f)), dp(2), dp(2), paint);
            if (open) {
                float reach = x + width - dp(6) - (wallX + wallW);
                for (int k = 0; k < 3; k++) {
                    float f = (t + k / 3f) % 1f;
                    paint.setColor(Color.argb((int) (220 * (1f - f)), 33, 150, 243));
                    c.drawCircle(wallX + wallW + reach * f, gy + dp(10) * f * f, dp(2.2f), paint);
                }
            }
        }
        textCentered(c, d.gatesOpen + " de " + d.gatesTotal + " comportas abertas", x + width / 2f, h - dp(4), 9, GREY, true);
    }

    // ------------------------------------------------------------------ chuva

    private void drawRain(Canvas c, float w, float h) {
        double obs = forecast == null ? Double.NaN : forecast.rainBasin1hMm;
        double next = forecast == null ? Double.NaN : forecast.rainNextHourMm;
        float half = w / 2f;
        drawSky(c, 0, half, h, obs, "Última hora", finite(obs) ? String.format(BR, "%.1f mm", obs) : "sem dado");
        drawSky(c, half, half, h, next, "Próxima hora", finite(next) ? String.format(BR, "%.1f mm", next) : "sem dado");
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Color.rgb(226, 232, 240));
        c.drawLine(half, dp(10), half, h - dp(10), paint);
    }

    private void drawSky(Canvas c, float x, float width, float h, double mm, String title, String value) {
        float cx = x + width / 2f;
        textCentered(c, title, cx, dp(14), 10, GREY, true);
        float cloudY = dp(44);
        boolean rainy = finite(mm) && mm >= 0.1;
        if (!rainy) {
            // Sem chuva: sol com raios girando atrás da nuvem.
            float sx = cx + dp(16), sy = cloudY - dp(8);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(Color.rgb(255, 179, 0));
            float rot = loop(9000) * 360f;
            for (int i = 0; i < 8; i++) {
                double a = Math.toRadians(rot + i * 45);
                c.drawLine(sx + (float) Math.cos(a) * dp(13), sy + (float) Math.sin(a) * dp(13),
                        sx + (float) Math.cos(a) * dp(18), sy + (float) Math.sin(a) * dp(18), paint);
            }
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(sx, sy, dp(10), paint);
        }
        float drift = dp(3) * (float) Math.sin(loop(5000) * 2 * Math.PI);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(rainy ? Color.rgb(120, 134, 150) : Color.rgb(207, 216, 226));
        c.drawCircle(cx - dp(14) + drift, cloudY + dp(3), dp(10), paint);
        c.drawCircle(cx + drift, cloudY - dp(4), dp(13), paint);
        c.drawCircle(cx + dp(15) + drift, cloudY + dp(3), dp(10), paint);
        c.drawRoundRect(new RectF(cx - dp(24) + drift, cloudY + dp(1), cx + dp(25) + drift, cloudY + dp(13)), dp(6), dp(6), paint);

        if (rainy) {
            int drops = (int) Math.max(4, Math.min(16, 4 + mm * 2.5));
            float fall = loop(700);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.8f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            float topY = cloudY + dp(16), bottomY = h - dp(26);
            for (int i = 0; i < drops; i++) {
                float dx = cx - dp(24) + (dp(48) * ((i * 37) % 100) / 100f);
                float f = (fall + ((i * 53) % 100) / 100f) % 1f;
                float y = topY + (bottomY - topY) * f;
                paint.setColor(Color.argb((int) (230 * (1f - f * 0.6f)), 33, 150, 243));
                c.drawLine(dx, y, dx - dp(2), y + dp(6), paint);
            }
        }
        textCentered(c, value, cx, h - dp(6), 13, rainy ? BLUE : INK, true);
    }

    // ------------------------------------------------------------------ a conta

    private void drawBars(Canvas c, float w, float h) {
        if (forecast == null || forecast.factors.isEmpty()) return;
        int n = forecast.factors.size();
        float row = (h - dp(34)) / n;
        float labelW = dp(132), valueW = dp(58);
        float barLeft = labelW, barRight = w - valueW;
        float zero = (barLeft + barRight) / 2f;
        double maxAbs = 1.0;
        for (Forecast.Factor f : forecast.factors) maxAbs = Math.max(maxAbs, Math.abs(f.cm));
        maxAbs = Math.max(maxAbs, Math.abs(forecast.deltaCm));

        // As barras crescem até o valor e ficam; o ciclo recomeça a cada 6 s.
        float t = loop(6000);
        float grow = smooth(Math.min(1f, t / 0.3f));

        paint.setStyle(Paint.Style.STROKE);
        paint.setPathEffect(null);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Color.rgb(203, 213, 225));
        c.drawLine(zero, dp(2), zero, h - dp(30), paint);

        for (int i = 0; i < n; i++) {
            Forecast.Factor f = forecast.factors.get(i);
            float cy = row * i + row / 2f;
            text(c, f.title, 0, cy + dp(4), 10.5f, INK, false);
            float len = (float) (f.cm / maxAbs) * (barRight - barLeft) / 2f * grow;
            int color = f.cm > 0.05 ? Color.rgb(229, 83, 80) : f.cm < -0.05 ? Color.rgb(38, 166, 107) : Color.rgb(176, 190, 197);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            float bx1 = Math.min(zero, zero + len), bx2 = Math.max(zero, zero + len);
            if (bx2 - bx1 < dp(2)) { bx1 = zero - dp(1); bx2 = zero + dp(1); }
            c.drawRoundRect(new RectF(bx1, cy - dp(6), bx2, cy + dp(6)), dp(3), dp(3), paint);
            String v = signed(f.cm * grow);
            paint.setTextSize(dp(11));
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            text(c, v, w - paint.measureText(v), cy + dp(4), 11, color == Color.rgb(176, 190, 197) ? GREY : color, true);
        }

        float ty = h - dp(10);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Color.rgb(203, 213, 225));
        c.drawLine(0, h - dp(28), w, h - dp(28), paint);
        text(c, "Variação prevista em 1 h", 0, ty, 11.5f, INK, true);
        String total = signed(forecast.deltaCm * grow);
        paint.setTextSize(dp(13));
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        text(c, total, w - paint.measureText(total), ty, 13, PURPLE, true);
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
    private static String signed(double cm) {
        if (Math.abs(cm) < 0.05) return "0,0 cm";
        return (cm > 0 ? "+" : "−") + String.format(BR, "%.1f cm", Math.abs(cm));
    }
}
