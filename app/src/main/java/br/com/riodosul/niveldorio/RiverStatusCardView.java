package br.com.riodosul.niveldorio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/**
 * Card principal com o nível atual. A régua colorida se redimensiona sozinha: vai até 8 m em dias
 * normais e cresce (9, 10, 11 m...) quando o rio passa disso, sempre com folga acima do nível.
 */
public class RiverStatusCardView extends View {
    private static final Locale BR = new Locale("pt", "BR");
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private Bridge bridge;
    private RiverReading reading;

    public RiverStatusCardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
    }

    public void setData(Bridge bridge, RiverReading reading) {
        this.bridge = bridge;
        this.reading = reading;
        invalidate();
    }

    private float dp(float v) { return v * density; }

    private float text(Canvas c, String value, float x, float y, float size, int color, boolean bold) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(dp(size));
        paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        c.drawText(value, x, y, paint);
        return paint.measureText(value);
    }

    /** Encurta o texto com reticências para caber na largura disponível. */
    private String fit(String value, float size, boolean bold, float maxWidth) {
        paint.setTextSize(dp(size));
        paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        if (paint.measureText(value) <= maxWidth) return value;
        String s = value;
        while (s.length() > 4 && paint.measureText(s + "…") > maxWidth) s = s.substring(0, s.length() - 1);
        return s.trim() + "…";
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        double level = reading == null ? Double.NaN : reading.levelMeters;
        boolean has = !Double.isNaN(level) && level > 0;
        Cotas cotas = reading == null || reading.cotas == null ? new Cotas() : reading.cotas;
        String status = has ? cotas.situacao(level) : "Sem dados";
        int color = has ? cotas.cor(level) : Color.rgb(140, 150, 160);

        // Fundo e faixa lateral com a situação.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        c.drawRoundRect(new RectF(dp(2), dp(2), w - dp(2), h - dp(2)), dp(14), dp(14), paint);
        paint.setColor(color);
        c.drawRoundRect(new RectF(dp(2), dp(2), dp(68), h - dp(2)), dp(14), dp(14), paint);
        c.drawRect(dp(35), dp(2), dp(68), h - dp(2), paint);

        // Água ondulando na faixa lateral: sobe junto com o nível do rio.
        if (has) {
            c.save();
            android.graphics.Path strip = new android.graphics.Path();
            strip.addRoundRect(new RectF(dp(2), dp(2), w - dp(2), h - dp(2)), dp(14), dp(14), android.graphics.Path.Direction.CW);
            c.clipPath(strip);
            c.clipRect(dp(2), dp(2), dp(68), h - dp(2));
            float frac = (float) Math.max(0.12, Math.min(0.92, level / cotas.topoDaEscala(level)));
            float surface = h - h * frac;
            float phase = (System.currentTimeMillis() % 4200L) / 4200f;
            stripWave(c, h, surface + dp(5), dp(3.5f), phase, Color.argb(46, 255, 255, 255));
            stripWave(c, h, surface, dp(3), 1.4f - phase, Color.argb(40, 255, 255, 255));
            c.restore();
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(color);
        c.drawRoundRect(new RectF(dp(2), dp(2), w - dp(2), h - dp(2)), dp(14), dp(14), paint);

        c.save();
        c.rotate(-90, dp(35), h / 2f);
        String verticalStatus = status.toUpperCase(BR);
        paint.setTextSize(dp(12));
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        float sw = paint.measureText(verticalStatus);
        text(c, verticalStatus, dp(35) - sw / 2f, h / 2f + dp(4), 12, Color.WHITE, true);
        c.restore();

        float left = dp(82);
        float right = w - dp(14);
        String title = bridge == null ? "Ponte" : bridge.displayName + " - " + bridge.river;
        text(c, fit(title, 13, true, right - left), left, dp(29), 13, Color.rgb(28, 65, 94), true);
        text(c, fit("Referência: " + (bridge == null ? "não informada" : bridge.reference), 9, false, right - left),
                left, dp(46), 9, Color.rgb(102, 112, 133), false);

        // Nível: a largura é medida, então 9,87 e 10,25 ocupam o espaço que precisarem.
        String value = has ? String.format(BR, "%.2f", level) : "--,--";
        int levelColor = has && cotas.faixa(level) >= 1 ? color : Color.rgb(24, 96, 150);
        float vw = text(c, value, left, dp(90), 32, levelColor, true);
        float mw = text(c, "m", left + vw + dp(4), dp(90), 15, levelColor, true);
        float infoX = left + vw + mw + dp(14);
        if (right - infoX > dp(70)) {
            text(c, "NÍVEL DO RIO", infoX, dp(76), 9, Color.rgb(65, 80, 91), true);
            text(c, fit("Leitura: " + (reading == null ? "--" : reading.readingTime), 9, false, right - infoX),
                    infoX, dp(90), 9, Color.rgb(75, 82, 88), false);
        }

        // Régua: topo dinâmico.
        double top = cotas.topoDaEscala(has ? level : Double.NaN);
        float barTop = dp(106), barBottom = dp(118);
        drawScale(c, left, right, barTop, barBottom, cotas, top, color, has ? level : Double.NaN);

        // Rótulos das cotas sob a régua, sem sobrepor.
        float labelY = dp(137);
        int grey = Color.rgb(102, 112, 133);
        paint.setTextSize(dp(9));
        paint.setTypeface(Typeface.DEFAULT);
        String topLabel = String.format(BR, "%.0f m", top);
        float topW = paint.measureText(topLabel);
        float lastEnd = left + text(c, "0", left, labelY, 9, grey, false);
        double[] marks = { cotas.atencao, cotas.alerta, cotas.emergencia };
        int[] markColors = { Cotas.COR_ATENCAO, Cotas.COR_ALERTA, Cotas.COR_EMERGENCIA };
        for (int i = 0; i < marks.length; i++) {
            String label = String.format(BR, "%.2f", marks[i]);
            paint.setTextSize(dp(9));
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            float lw = paint.measureText(label);
            float lx = left + (right - left) * (float) (marks[i] / top) - lw / 2f;
            if (lx < lastEnd + dp(3) || lx + lw > right - topW - dp(3)) continue;
            text(c, label, lx, labelY, 9, markColors[i], true);
            lastEnd = lx + lw;
        }
        text(c, topLabel, right - topW, labelY, 9, grey, false);

        if (cotas.oficial) {
            text(c, "Cotas: Defesa Civil de Rio do Sul", left, dp(152), 8, Color.rgb(140, 148, 160), false);
        }
        if (isShown()) postInvalidateDelayed(40);
    }

    private void stripWave(Canvas c, float h, float baseY, float amp, float phase, int color) {
        android.graphics.Path wave = new android.graphics.Path();
        wave.moveTo(0, h);
        for (float x = 0; x <= dp(72); x += dp(4)) {
            wave.lineTo(x, baseY + (float) Math.sin((x / dp(60) + phase) * 2 * Math.PI) * amp);
        }
        wave.lineTo(dp(72), h);
        wave.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawPath(wave, paint);
    }

    private void drawScale(Canvas c, float left, float right, float top, float bottom,
                           Cotas cotas, double max, int color, double level) {
        double[] limits = { 0, cotas.atencao, cotas.alerta, cotas.emergencia, max };
        int[] colors = { Color.rgb(38, 170, 108), Color.rgb(250, 205, 34), Color.rgb(239, 139, 45), Color.rgb(221, 83, 82) };
        float radius = (bottom - top) / 2f;

        c.save();
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(new RectF(left, top, right, bottom), radius, radius, android.graphics.Path.Direction.CW);
        c.clipPath(clip);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 4; i++) {
            paint.setColor(colors[i]);
            float x1 = left + (right - left) * (float) (Math.min(max, limits[i]) / max);
            float x2 = left + (right - left) * (float) (Math.min(max, limits[i + 1]) / max);
            if (x2 > x1) c.drawRect(x1, top, x2, bottom, paint);
        }
        c.restore();

        if (!Double.isNaN(level)) {
            float x = left + (right - left) * (float) (Math.max(0, Math.min(max, level)) / max);
            x = Math.max(left + dp(7), Math.min(right - dp(7), x));
            // Halo pulsando em volta do marcador.
            float pulse = (System.currentTimeMillis() % 1800L) / 1800f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            paint.setAlpha((int) (110 * (1f - pulse)));
            c.drawCircle(x, (top + bottom) / 2, dp(7.5f) + dp(9) * pulse, paint);
            paint.setColor(Color.WHITE);
            c.drawCircle(x, (top + bottom) / 2, dp(7.5f), paint);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            c.drawCircle(x, (top + bottom) / 2, dp(6.5f), paint);
        }
    }
}
