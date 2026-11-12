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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Card "Nível nas últimas 24 horas".
 *
 * A borda fina, de cantos arredondados, toma a cor da situação atual do rio (verde = normal,
 * amarelo = atenção, laranja = alerta, vermelho = emergência, na escala oficial da Defesa Civil).
 * A base do card tem a água do rio se movendo: duas ondas em sentidos opostos, um brilho de
 * correnteza atravessando o card e um reflexo que percorre a própria linha do gráfico.
 */
public class RiverChartView extends View {
    private static final Locale BR = new Locale("pt", "BR");
    private static final float RADIUS_DP = 14f;

    private List<Sample> samples = new ArrayList<>();
    private Cotas cotas = new Cotas();
    private Forecast forecast;
    /** Nível atual da ponte escolhida — é ele que dá a cor da borda. */
    private double currentLevel = Double.NaN;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wave = new Path();
    private final float density;
    /** Início da animação de entrada: a linha aparece da esquerda para a direita. */
    private long revealStart = System.currentTimeMillis();
    private Bridge shownBridge;
    private boolean hadData;

    public RiverChartView(Context c, AttributeSet attrs) {
        super(c, attrs);
        density = getResources().getDisplayMetrics().density;
    }

    public void setData(List<Sample> samples, Cotas cotas, Forecast forecast) {
        setData(samples, cotas, forecast, Double.NaN);
    }

    public void setData(List<Sample> samples, Cotas cotas, Forecast forecast, double currentLevel) {
        this.samples = samples == null ? new ArrayList<Sample>() : new ArrayList<>(samples);
        this.cotas = cotas == null ? new Cotas() : cotas;
        this.forecast = forecast;
        this.currentLevel = currentLevel;
        // Reinicia a entrada animada só quando muda a ponte ou chegam os primeiros dados.
        Bridge b = forecast == null ? null : forecast.bridge;
        boolean hasData = this.samples.size() >= 2;
        if (b != shownBridge || hasData != hadData) {
            shownBridge = b;
            hadData = hasData;
            revealStart = System.currentTimeMillis();
        }
        invalidate();
    }

    private float dp(float v) { return v * density; }

    /** Fração 0..1 que se repete a cada periodMs. */
    private float loop(long periodMs) {
        return (System.currentTimeMillis() % periodMs) / (float) periodMs;
    }

    private static float smooth(float t) {
        return t * t * (3f - 2f * t);
    }

    /** Cor da borda: a situação atual do rio. */
    private int borderColor() {
        double level = level();
        if (Double.isNaN(level) || level <= 0) return Color.rgb(150, 158, 168);
        return cotas.cor(level);
    }

    private double level() {
        double level = currentLevel;
        if (Double.isNaN(level) && forecast != null && forecast.available) level = forecast.currentLevel;
        return level;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float inset = 1.4f * density;
        float radius = RADIUS_DP * density;
        RectF card = new RectF(inset, inset, w - inset, h - inset);
        int edge = borderColor();

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setAlpha(255);
        paint.setColor(Color.WHITE);
        canvas.drawRoundRect(card, radius, radius, paint);

        // Água do rio se movendo, por baixo de tudo e recortada nos cantos do card.
        canvas.save();
        Path clip = new Path();
        clip.addRoundRect(card, radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
        drawCurrent(canvas, card, w);
        drawWater(canvas, w, h);
        canvas.restore();

        paint.setShader(null);
        paint.setAlpha(255);
        paint.setTypeface(Typeface.DEFAULT_BOLD);

        // Legenda da previsão, medida primeiro para saber onde termina o espaço do título.
        boolean hasForecast = forecast != null && forecast.available;
        String legend = hasForecast ? "- - previsão +1h" : null;
        float legendStart = w - 14 * density;
        if (legend != null) {
            paint.setTextSize(10 * density);
            legendStart = w - 14 * density - paint.measureText(legend);
        }

        paint.setTextSize(13 * density);
        paint.setColor(Color.rgb(28, 65, 94));
        String title = "Nível nas últimas 24 horas";
        canvas.drawText(title, 14 * density, 24 * density, paint);
        float titleEnd = 14 * density + paint.measureText(title);

        // Etiqueta da situação, na mesma cor da borda, se sobrar espaço.
        String situation = situationLabel();
        if (situation != null) {
            paint.setTextSize(9 * density);
            float chipW = paint.measureText(situation) + 16 * density;
            float chipX = titleEnd + 8 * density;
            if (chipX + chipW < legendStart - 6 * density) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(edge);
                paint.setAlpha(235);
                canvas.drawRoundRect(new RectF(chipX, 12 * density, chipX + chipW, 27 * density),
                        7 * density, 7 * density, paint);
                paint.setAlpha(255);
                paint.setColor(Color.WHITE);
                canvas.drawText(situation, chipX + 8 * density, 23 * density, paint);
            }
        }

        if (legend != null) {
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextSize(10 * density);
            paint.setColor(Color.rgb(123, 31, 162));
            canvas.drawText(legend, legendStart, 23 * density, paint);
        }

        long now = System.currentTimeMillis();
        float t = Math.min(1f, (now - revealStart) / 1100f);
        float reveal = smooth(t);
        boolean revealed = reveal >= 1f;
        float pulse = (now % 1800L) / 1800f;
        float flow = (now % 3600L) / 3600f;
        canvas.save();
        try {
            if (!revealed) canvas.clipRect(0, 0, w * (0.12f + 0.88f * reveal), h);
            ChartRenderer.draw(canvas, 6 * density, 32 * density, w - 12 * density, h - 38 * density,
                    samples, cotas, forecast, density, revealed ? pulse : -1f, revealed ? flow : -1f);
            canvas.restore();
        } catch (RuntimeException e) {
            canvas.restore();
            // Um dado inesperado nunca pode fechar a tela: mostra o aviso no lugar do gráfico.
            paint.setShader(null);
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextSize(12 * density);
            paint.setColor(Color.rgb(102, 112, 133));
            canvas.drawText("Não foi possível desenhar o gráfico agora.", 14 * density, h / 2f, paint);
        }

        // Borda fina de cantos arredondados, na cor do nível de criticidade atual.
        paint.setStyle(Paint.Style.STROKE);
        paint.setShader(null);
        paint.setAlpha(255);
        paint.setStrokeWidth(1.8f * density);
        paint.setColor(edge);
        canvas.drawRoundRect(card, radius, radius, paint);

        if (isShown()) postInvalidateDelayed(40);
    }

    private String situationLabel() {
        double level = level();
        if (Double.isNaN(level) || level <= 0) return null;
        return cotas.situacao(level).toUpperCase(BR);
    }

    /** Faixa clara de correnteza atravessando o card, bem discreta, atrás do gráfico. */
    private void drawCurrent(Canvas c, RectF card, float w) {
        float t = loop(9000);
        float cx = card.left - w + 2f * w * t;
        float band = w * 0.34f;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(cx - band, 0, cx + band, 0,
                new int[] { Color.argb(0, 129, 212, 250), Color.argb(30, 129, 212, 250), Color.argb(0, 129, 212, 250) },
                new float[] { 0f, 0.5f, 1f }, Shader.TileMode.CLAMP));
        c.drawRect(card, paint);
        paint.setShader(null);
    }

    /** Água na base do card: duas ondas em sentidos opostos e brilhinhos correndo na superfície. */
    private void drawWater(Canvas c, float w, float h) {
        float bandTop = h - 30 * density;

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, bandTop, 0, h,
                new int[] { Color.argb(10, 21, 101, 192), Color.argb(58, 21, 101, 192) },
                null, Shader.TileMode.CLAMP));
        c.drawRect(0, bandTop, w, h, paint);
        paint.setShader(null);

        wave(c, w, h, bandTop + 4 * density, 3 * density, loop(5200), Color.argb(52, 129, 212, 250));
        wave(c, w, h, bandTop + 10 * density, 2.6f * density, 1f - loop(6400), Color.argb(46, 21, 101, 192));

        // Brilhos que correm com a água.
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 4; i++) {
            float f = (loop(7000) + i * 0.25f) % 1f;
            float x = 6 * density + (w - 13 * density) * f;
            float y = bandTop + 12 * density + (float) Math.sin(f * Math.PI * 2f) * 2 * density;
            paint.setColor(Color.WHITE);
            paint.setAlpha((int) (70 * (1f - Math.abs(f - 0.5f) * 2f)));
            c.drawRoundRect(new RectF(x, y, x + 7 * density, y + 1.4f * density),
                    1.4f * density, 1.4f * density, paint);
        }
        paint.setAlpha(255);
    }

    private void wave(Canvas c, float w, float h, float baseY, float amp, float phase, int color) {
        wave.reset();
        wave.moveTo(0, h);
        float len = w / 1.4f;
        for (float x = 0; x <= w + dp(8); x += dp(6)) {
            wave.lineTo(x, baseY + (float) Math.sin((x / len + phase) * 2 * Math.PI) * amp);
        }
        wave.lineTo(w, h);
        wave.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setAlpha(255);
        paint.setColor(color);
        c.drawPath(wave, paint);
    }
}
