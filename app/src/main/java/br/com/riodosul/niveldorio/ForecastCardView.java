package br.com.riodosul.niveldorio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Card da tela principal com as previsões para daqui a 1, 2 e 3 horas, lado a lado.
 * Toque abre o memorial descritivo.
 */
public class ForecastCardView extends View {
    private static final Locale BR = new Locale("pt", "BR");
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wave = new Path();
    private final float density;
    private Forecast forecast;
    private boolean loading = true;
    /** Início da entrada animada das colunas (reinicia quando a previsão muda). */
    private long appearAt = System.currentTimeMillis();
    private long shownBase = -1;

    public ForecastCardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setClickable(true);
    }

    public void setForecast(Forecast forecast, boolean loading) {
        long base = forecast == null ? -1 : forecast.baseTime;
        if (base != shownBase || (forecast != null && forecast.bridge != (this.forecast == null ? null : this.forecast.bridge))) {
            appearAt = System.currentTimeMillis();
            shownBase = base;
        }
        this.forecast = forecast;
        this.loading = loading;
        invalidate();
    }

    private float dp(float v) { return v * density; }

    private float text(Canvas c, String value, float x, float y, float size, int color, boolean bold) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(dp(size));
        paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        c.drawText(value, x, y, paint);
        return paint.measureText(value);
    }

    private float measure(String value, float size, boolean bold) {
        paint.setTextSize(dp(size));
        paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return paint.measureText(value);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        RectF card = new RectF(dp(2), dp(2), w - dp(2), h - dp(2));

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, w, h, Color.rgb(13, 71, 161), Color.rgb(74, 20, 140), Shader.TileMode.CLAMP));
        c.drawRoundRect(card, dp(14), dp(14), paint);
        paint.setShader(null);

        // Ondas animadas no rodapé do card.
        c.save();
        Path clip = new Path();
        clip.addRoundRect(card, dp(14), dp(14), Path.Direction.CW);
        c.clipPath(clip);
        float phase = (System.currentTimeMillis() % 6000L) / 6000f;
        drawWave(c, w, h, h - dp(20), dp(5), phase, Color.argb(46, 255, 255, 255));
        drawWave(c, w, h, h - dp(13), dp(4), 1f - phase + 0.3f, Color.argb(36, 255, 255, 255));
        c.restore();

        float left = dp(16);
        text(c, "PREVISÃO CENTRALIZADA · EXPERIMENTAL", left, dp(24), 9.5f, Color.argb(220, 255, 255, 255), true);

        if (forecast == null || !forecast.available) {
            String msg = loading ? "Buscando previsão centralizada…" : "Previsão indisponível agora";
            text(c, msg, left, dp(66), 17, Color.WHITE, true);
            String reason = forecast != null && forecast.unavailableReason != null
                    && !forecast.unavailableReason.isEmpty() ? forecast.unavailableReason
                    : "Atualize a tela para tentar de novo.";
            while (reason.length() > 3 && measure(reason, 10.5f, false) > w - dp(32)) {
                reason = reason.substring(0, reason.length() - 2).trim() + "…";
            }
            text(c, reason, left, dp(88), 10.5f, Color.argb(210, 255, 255, 255), false);
        } else {
            drawColumns(c, w);
        }

        String cta = "Toque para ver detalhes do servidor  ›";
        float cw = measure(cta, 11, true);
        text(c, cta, w - cw - dp(16), h - dp(12), 11, Color.WHITE, true);

        if (isShown()) postInvalidateDelayed(40);
    }

    /** As três colunas: +1 h (em destaque), +2 h e +3 h. */
    private void drawColumns(Canvas c, float w) {
        float left = dp(10), right = w - dp(10);
        float gap = dp(6);
        float colW = (right - left - 2 * gap) / 3f;
        float top = dp(34), bottom = dp(128);
        long now = System.currentTimeMillis();
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", BR);

        for (int i = 0; i < 3; i++) {
            Forecast.Horizon hz = forecast.horizon(i + 1);
            float x0 = left + i * (colW + gap);
            // Entrada: cada coluna sobe e aparece, uma depois da outra.
            float t = Math.max(0f, Math.min(1f, (now - appearAt - i * 140L) / 380f));
            float ease = t * t * (3f - 2f * t);
            int alpha = (int) (255 * ease);
            float dy = (1f - ease) * dp(10);

            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            paint.setAlpha((int) ((i == 0 ? 34 : 18) * ease));
            c.drawRoundRect(new RectF(x0, top + dy, x0 + colW, bottom + dy), dp(10), dp(10), paint);

            float cx = x0 + colW / 2f;
            String head = "+" + (i + 1) + " h" + (hz != null && hz.targetTime > 0 ? " · " + hm.format(new Date(hz.targetTime)) : "");
            center(c, head, cx, top + dp(17) + dy, 10.5f, Color.argb((int) (220 * ease), 255, 255, 255), true);

            if (hz == null || Double.isNaN(hz.predictedLevel)) {
                center(c, "—", cx, top + dp(48) + dy, 20, Color.argb(alpha, 255, 255, 255), true);
                continue;
            }
            // Nível previsto.
            String value = String.format(BR, "%.2f", hz.predictedLevel);
            float vSize = i == 0 ? 24 : 21;
            float vw = measure(value, vSize, true);
            float mw = measure("m", 12, true);
            float vx = cx - (vw + dp(2) + mw) / 2f;
            text(c, value, vx, top + dp(47) + dy, vSize, Color.argb(alpha, 255, 255, 255), true);
            text(c, "m", vx + vw + dp(2), top + dp(47) + dy, 12, Color.argb(alpha, 255, 255, 255), true);

            // Variação em relação a agora.
            String delta = hz.arrow() + " " + (hz.deltaCm >= 0 ? "+" : "−") + String.format(BR, "%.0f cm", Math.abs(hz.deltaCm));
            float dw = measure(delta, 11, true);
            int chip = hz.deltaCm >= 1 ? Color.rgb(239, 83, 80) : hz.deltaCm <= -1 ? Color.rgb(38, 166, 107) : Color.rgb(120, 144, 156);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(chip);
            paint.setAlpha(alpha);
            float chipTop = top + dp(55) + dy;
            c.drawRoundRect(new RectF(cx - dw / 2f - dp(7), chipTop, cx + dw / 2f + dp(7), chipTop + dp(19)), dp(9.5f), dp(9.5f), paint);
            center(c, delta, cx, chipTop + dp(13.5f), 11, Color.argb(alpha, 255, 255, 255), true);

            // Margem de erro.
            String band = Double.isNaN(hz.bandCm) ? "margem não informada" : String.format(BR, "± %.0f cm", hz.bandCm);
            center(c, band, cx, top + dp(89) + dy, 9.5f,
                    Color.argb((int) (205 * ease), 255, 255, 255), false);
        }
    }

    private void center(Canvas c, String value, float cx, float y, float size, int color, boolean bold) {
        float tw = measure(value, size, bold);
        text(c, value, cx - tw / 2f, y, size, color, bold);
    }

    private void drawWave(Canvas c, float w, float h, float baseY, float amp, float phase, int color) {
        wave.reset();
        wave.moveTo(0, h);
        float len = w / 1.6f;
        for (float x = 0; x <= w + dp(8); x += dp(8)) {
            wave.lineTo(x, baseY + (float) Math.sin((x / len + phase) * 2 * Math.PI) * amp);
        }
        wave.lineTo(w, h);
        wave.close();
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawPath(wave, paint);
    }
}
