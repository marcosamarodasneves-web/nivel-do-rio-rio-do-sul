package br.com.riodosul.niveldorio;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * Ícone animado de tempo, desenhado em Canvas: sol girando quando não choveu e nuvem com gotas
 * caindo quando choveu (mais gotas quanto mais chuva). Usado no gráfico da barragem, na tela da
 * barragem e nos cards da tela inicial.
 */
public final class WeatherIcon {
    private WeatherIcon() {}

    /**
     * @param cx,cy centro do ícone
     * @param size  lado do quadrado ocupado pelo ícone (px)
     * @param mm    chuva de referência; abaixo de 0,2 mm desenha o sol
     */
    public static void draw(Canvas c, Paint paint, float cx, float cy, float size, double mm, long now) {
        boolean rainy = !Double.isNaN(mm) && mm >= 0.2;
        float u = size / 48f;
        paint.setShader(null);
        paint.setPathEffect(null);
        if (!rainy) {
            drawSun(c, paint, cx, cy, u, now);
            return;
        }
        int drops = (int) Math.max(3, Math.min(9, 3 + mm / 3.0));
        float fall = (now % 900L) / 900f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(2.2f * u);
        float top = cy + 4 * u, bottom = cy + 23 * u;
        for (int i = 0; i < drops; i++) {
            float dx = cx - 14 * u + (28 * u) * (((i * 37) % 100) / 100f);
            float f = (fall + ((i * 53) % 100) / 100f) % 1f;
            float y = top + (bottom - top) * f;
            paint.setColor(Color.argb((int) (235 * (1f - f * 0.7f)), 33, 150, 243));
            c.drawLine(dx, y, dx - 2 * u, y + 6 * u, paint);
        }
        drawCloud(c, paint, cx + 2.5f * u * (float) Math.sin((now % 5000L) / 5000f * 2 * Math.PI), cy - 4 * u, u,
                Color.rgb(120, 134, 150));
    }

    private static void drawSun(Canvas c, Paint paint, float cx, float cy, float u, long now) {
        float rot = (now % 12000L) / 12000f * 360f;
        float pulse = 1f + 0.06f * (float) Math.sin((now % 2400L) / 2400f * 2 * Math.PI);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(46, 255, 179, 0));
        c.drawCircle(cx, cy, 17 * u * pulse, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(2.6f * u);
        paint.setColor(Color.rgb(255, 179, 0));
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(rot + i * 45);
            float r1 = 12 * u, r2 = 18 * u * pulse;
            c.drawLine(cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1,
                    cx + (float) Math.cos(a) * r2, cy + (float) Math.sin(a) * r2, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(255, 193, 7));
        c.drawCircle(cx, cy, 9 * u, paint);
        paint.setColor(Color.argb(140, 255, 236, 179));
        c.drawCircle(cx - 2.5f * u, cy - 2.5f * u, 3.5f * u, paint);
    }

    private static void drawCloud(Canvas c, Paint paint, float cx, float cy, float u, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawCircle(cx - 9 * u, cy + 2 * u, 7 * u, paint);
        c.drawCircle(cx + 1 * u, cy - 3 * u, 9.5f * u, paint);
        c.drawCircle(cx + 11 * u, cy + 2.5f * u, 6.5f * u, paint);
        c.drawRoundRect(new RectF(cx - 16 * u, cy + 1 * u, cx + 17 * u, cy + 9 * u), 4 * u, 4 * u, paint);
        paint.setColor(Color.argb(60, 255, 255, 255));
        c.drawCircle(cx - 1 * u, cy - 6 * u, 3.5f * u, paint);
    }
}
