package br.com.riodosul.niveldorio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Typeface;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Gráfico do nível nas últimas 24 h, com as cotas da Defesa Civil e a previsão de +1 h.
 * A escala vertical se ajusta aos dados (nunca fica presa em 8 m) e o eixo horizontal mostra a hora.
 */
public final class ChartRenderer {
    private static final Locale BR = new Locale("pt", "BR");

    private ChartRenderer() {}

    /** Versão em bitmap, usada pelo widget. */
    public static Bitmap render(List<Sample> input, Cotas cotas, Forecast forecast, int width, int height) {
        width = Math.max(width, 320);
        height = Math.max(height, 120);
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        draw(new Canvas(bmp), 0, 0, width, height, input, cotas, forecast, Math.max(1f, width / 360f));
        return bmp;
    }

    public static void draw(Canvas c, float x0, float y0, float width, float height,
                            List<Sample> input, Cotas cotasIn, Forecast forecast, float density) {
        draw(c, x0, y0, width, height, input, cotasIn, forecast, density, -1f);
    }

    /** "pulse" de 0 a 1 anima um halo nos pontos atual e previsto; negativo desenha sem animação. */
    public static void draw(Canvas c, float x0, float y0, float width, float height,
                            List<Sample> input, Cotas cotasIn, Forecast forecast, float density, float pulse) {
        draw(c, x0, y0, width, height, input, cotasIn, forecast, density, pulse, -1f);
    }

    /**
     * "pulse" anima o halo dos pontos e "flow" faz um brilho correr sobre a linha, como a correnteza
     * do rio. Valores negativos desenha sem essa animação (usado pelo widget, que é estático).
     */
    public static void draw(Canvas c, float x0, float y0, float width, float height,
                            List<Sample> input, Cotas cotasIn, Forecast forecast, float density,
                            float pulse, float flow) {
        if (width < 40 || height < 40) return;
        Cotas cotas = cotasIn == null ? new Cotas() : cotasIn;

        List<Sample> data = new ArrayList<>();
        if (input != null) {
            for (Sample s : input) {
                if (s != null && s.timeMillis > 0 && !Double.isNaN(s.value) && !Double.isInfinite(s.value) && s.value > 0) {
                    data.add(s);
                }
            }
        }

        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTextSize(10f * density);
        text.setColor(Color.rgb(95, 105, 118));

        if (data.size() < 2) {
            text.setTextSize(13f * density);
            String msg = "Histórico indisponível no momento";
            c.drawText(msg, x0 + (width - text.measureText(msg)) / 2f, y0 + height / 2f, text);
            return;
        }

        boolean hasForecast = forecast != null && forecast.available
                && !Double.isNaN(forecast.predictedLevel) && forecast.targetTime > data.get(data.size() - 1).timeMillis;

        // Escala vertical ajustada aos dados.
        double dataMin = Double.MAX_VALUE, dataMax = 0;
        for (Sample s : data) {
            dataMin = Math.min(dataMin, s.value);
            dataMax = Math.max(dataMax, s.value);
        }
        if (hasForecast) {
            dataMin = Math.min(dataMin, forecast.predictedLevel);
            dataMax = Math.max(dataMax, forecast.predictedLevel);
        }
        double yMin = Math.max(0, Math.floor(dataMin - 0.5));
        double yMax = cotas.topoDaEscala(dataMax);
        if (yMax - yMin < 2) yMax = yMin + 2;

        long t0 = data.get(0).timeMillis;
        long tLast = data.get(data.size() - 1).timeMillis;
        long t1 = hasForecast ? forecast.targetTime : tLast;
        if (t1 <= t0) t1 = t0 + 1;

        float left = x0 + text.measureText("00 m") + 8f * density;
        float right = x0 + width - 8f * density;
        float top = y0 + 8f * density;
        float bottom = y0 + height - 18f * density;
        if (right - left < 20 || bottom - top < 20) return;

        // Faixas de cor suave por situação.
        double[] limits = { yMin, cotas.atencao, cotas.alerta, cotas.emergencia, yMax };
        int[] tints = { Cotas.COR_NORMAL, Cotas.COR_ATENCAO, Cotas.COR_ALERTA, Cotas.COR_EMERGENCIA };
        Paint band = new Paint();
        for (int i = 0; i < 4; i++) {
            double lo = Math.max(yMin, limits[i]), hi = Math.min(yMax, limits[i + 1]);
            if (i == 0) lo = yMin;
            if (hi <= lo) continue;
            band.setColor(tints[i]);
            band.setAlpha(22);
            c.drawRect(left, y(hi, yMin, yMax, top, bottom), right, y(lo, yMin, yMax, top, bottom), band);
        }

        // Linhas de grade a cada metro (ou 2 m quando a escala é grande).
        Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        grid.setColor(Color.rgb(222, 229, 238));
        grid.setStrokeWidth(Math.max(1f, density));
        int step = (yMax - yMin) > 8 ? 2 : 1;
        for (double v = Math.ceil(yMin); v <= yMax + 0.001; v += step) {
            float gy = y(v, yMin, yMax, top, bottom);
            c.drawLine(left, gy, right, gy, grid);
            String label = String.format(BR, "%.0f m", v);
            c.drawText(label, left - text.measureText(label) - 5f * density, gy + 3.5f * density, text);
        }

        // Cotas da Defesa Civil.
        drawCota(c, cotas.atencao, "Atenção", Cotas.COR_ATENCAO, yMin, yMax, left, right, top, bottom, density);
        drawCota(c, cotas.alerta, "Alerta", Cotas.COR_ALERTA, yMin, yMax, left, right, top, bottom, density);
        drawCota(c, cotas.emergencia, "Emergência", Cotas.COR_EMERGENCIA, yMin, yMax, left, right, top, bottom, density);

        // Eixo do tempo: marca a cada 6 h cheias.
        SimpleDateFormat hour = new SimpleDateFormat("HH'h'", BR);
        long sixHours = 6L * 3600000L;
        long span = t1 - t0;
        long tickStep = span > 30L * 3600000L ? 2 * sixHours : span > 8L * 3600000L ? sixHours : 3600000L * 2;
        long offset = java.util.TimeZone.getDefault().getOffset(t0);
        long firstTick = ((t0 + offset) / tickStep + 1) * tickStep - offset;
        float lastLabelEnd = -1e9f;
        for (long t = firstTick; t < t1; t += tickStep) {
            float tx = x(t, t0, t1, left, right);
            c.drawLine(tx, top, tx, bottom, grid);
            String label = hour.format(new Date(t));
            float lw = text.measureText(label);
            float lx = Math.max(left, Math.min(right - lw, tx - lw / 2f));
            if (lx > lastLabelEnd + 4f * density) {
                c.drawText(label, lx, bottom + 13f * density, text);
                lastLabelEnd = lx + lw;
            }
        }

        // Série.
        Path line = new Path();
        Path fill = new Path();
        float firstX = 0, lastX = 0, lastY = 0;
        for (int i = 0; i < data.size(); i++) {
            Sample s = data.get(i);
            float px = x(s.timeMillis, t0, t1, left, right);
            float py = y(s.value, yMin, yMax, top, bottom);
            if (i == 0) {
                line.moveTo(px, py);
                fill.moveTo(px, bottom);
                fill.lineTo(px, py);
                firstX = px;
            } else {
                line.lineTo(px, py);
                fill.lineTo(px, py);
            }
            lastX = px;
            lastY = py;
        }
        fill.lineTo(lastX, bottom);
        fill.lineTo(firstX, bottom);
        fill.close();

        Paint area = new Paint(Paint.ANTI_ALIAS_FLAG);
        area.setColor(Color.argb(46, 21, 101, 192));
        c.drawPath(fill, area);

        Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
        lp.setStyle(Paint.Style.STROKE);
        lp.setStrokeWidth(2.4f * density);
        lp.setStrokeCap(Paint.Cap.ROUND);
        lp.setStrokeJoin(Paint.Join.ROUND);
        lp.setColor(Color.rgb(21, 101, 192));
        c.drawPath(line, lp);

        drawFlow(c, line, flow, density);

        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Previsão de +1 h: trecho tracejado, margem de erro e ponto final.
        if (hasForecast) {
            float fx = x(forecast.targetTime, t0, t1, left, right);
            float fy = y(forecast.predictedLevel, yMin, yMax, top, bottom);
            int fColor = Color.rgb(123, 31, 162);

            Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);
            dash.setStyle(Paint.Style.STROKE);
            dash.setStrokeWidth(2.2f * density);
            dash.setColor(fColor);
            dash.setPathEffect(new DashPathEffect(new float[] { 5f * density, 4f * density }, 0));
            Path fp = new Path();
            fp.moveTo(lastX, lastY);
            fp.lineTo(fx, fy);
            c.drawPath(fp, dash);

            Paint whisker = new Paint(Paint.ANTI_ALIAS_FLAG);
            whisker.setColor(fColor);
            whisker.setStrokeWidth(1.5f * density);
            float wy1 = y(forecast.predictedLevel + forecast.bandCm / 100.0, yMin, yMax, top, bottom);
            float wy2 = y(forecast.predictedLevel - forecast.bandCm / 100.0, yMin, yMax, top, bottom);
            c.drawLine(fx, wy1, fx, wy2, whisker);

            if (pulse >= 0f) {
                float p2 = (pulse + 0.5f) % 1f;
                dot.setColor(Color.argb((int) (110 * (1f - p2)), 123, 31, 162));
                c.drawCircle(fx, fy, (4.5f + 8f * p2) * density, dot);
            }
            dot.setColor(Color.WHITE);
            c.drawCircle(fx, fy, 4.5f * density, dot);
            dot.setColor(fColor);
            c.drawCircle(fx, fy, 3.2f * density, dot);

            Paint ft = new Paint(Paint.ANTI_ALIAS_FLAG);
            ft.setColor(fColor);
            ft.setTextSize(9.5f * density);
            ft.setTypeface(Typeface.DEFAULT_BOLD);
            String label = "+1h";
            float ly = fy - 8f * density < top + 10f * density ? fy + 15f * density : fy - 8f * density;
            c.drawText(label, fx - ft.measureText(label) - 2f * density, ly, ft);
        }

        if (pulse >= 0f) {
            dot.setColor(Color.argb((int) (120 * (1f - pulse)), 21, 101, 192));
            c.drawCircle(lastX, lastY, (5f + 9f * pulse) * density, dot);
        }
        dot.setColor(Color.WHITE);
        c.drawCircle(lastX, lastY, 5f * density, dot);
        dot.setColor(Color.rgb(21, 101, 192));
        c.drawCircle(lastX, lastY, 3.6f * density, dot);
    }

    private static void drawCota(Canvas c, double value, String label, int color, double yMin, double yMax,
                                 float left, float right, float top, float bottom, float density) {
        if (value <= yMin || value >= yMax) return;
        float py = y(value, yMin, yMax, top, bottom);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setColor(color);
        line.setStrokeWidth(1.4f * density);
        line.setPathEffect(new DashPathEffect(new float[] { 6f * density, 4f * density }, 0));
        Path p = new Path();
        p.moveTo(left, py);
        p.lineTo(right, py);
        c.drawPath(p, line);

        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(color);
        text.setTextSize(9f * density);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        String full = String.format(BR, "%s %.2f m", label, value);
        c.drawText(full, left + 4f * density, py - 3f * density, text);
    }

    /** Brilho que percorre a série desenhada, na cor do rio: dá a impressão de correnteza. */
    private static void drawFlow(Canvas c, Path line, float flow, float density) {
        if (flow < 0f || line == null || line.isEmpty()) return;
        PathMeasure measure = new PathMeasure(line, false);
        float total = measure.getLength();
        if (total < 12f * density) return;

        float t = Math.max(0f, Math.min(1f, flow));
        float seg = Math.min(total, 34f * density);
        float head = t * (total + seg);
        float start = Math.max(0f, head - seg);
        float end = Math.min(total, head);
        if (end - start < 2f * density) return;

        // Entra e sai suave: o brilho fica mais forte no meio do caminho.
        int alpha = (int) (180f * (float) Math.sin(Math.PI * t));
        if (alpha <= 8) return;

        Path piece = new Path();
        if (!measure.getSegment(start, end, piece, true)) return;

        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
        glow.setStrokeJoin(Paint.Join.ROUND);
        glow.setColor(Color.rgb(129, 212, 250));
        glow.setAlpha(alpha / 2);
        glow.setStrokeWidth(7f * density);
        c.drawPath(piece, glow);

        glow.setColor(Color.WHITE);
        glow.setAlpha(alpha);
        glow.setStrokeWidth(2.4f * density);
        c.drawPath(piece, glow);
    }

    private static float x(long t, long t0, long t1, float left, float right) {
        return left + (float) ((t - t0) / (double) (t1 - t0)) * (right - left);
    }

    private static float y(double v, double min, double max, float top, float bottom) {
        double clamped = Math.max(min, Math.min(max, v));
        return bottom - (float) ((clamped - min) / (max - min)) * (bottom - top);
    }
}
