package br.com.riodosul.niveldorio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Grade com a chuva acumulada nas últimas 24 h em cada município da bacia.
 * As linhas entram uma a uma e as barras crescem até o valor.
 */
public class MunicipalRainView extends View {
    private static final Locale BR = new Locale("pt", "BR");
    private static final int INK = Color.rgb(28, 65, 94);
    private static final int GREY = Color.rgb(102, 112, 133);
    private static final int SOFT = Color.rgb(150, 158, 168);
    private static final int LINE = Color.rgb(224, 232, 241);
    private static final int RAIN = Color.rgb(2, 132, 199);
    private static final int RAIN_LIGHT = Color.rgb(125, 196, 235);

    private static final float PAD = 12f;
    private static final float HEAD_H = 22f;
    private static final float ROW_H = 27f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final List<MunicipalRain> rows = new ArrayList<>();
    private long appearAt = System.currentTimeMillis();

    public MunicipalRainView(Context context) {
        this(context, null);
    }

    public MunicipalRainView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
    }

    public void setData(List<MunicipalRain> list) {
        rows.clear();
        if (list != null) rows.addAll(list);
        replay();
        requestLayout();
    }

    /** Recomeça a animação de entrada (usado a cada vez que a grade é aberta). */
    public void replay() {
        appearAt = System.currentTimeMillis();
        invalidate();
    }

    private float dp(float v) { return v * density; }

    public float preferredHeightDp() {
        float h = PAD + 16 + 2 * 13 + 6;
        if (rows.isEmpty()) return h + 30 + PAD;
        return h + HEAD_H + rows.size() * ROW_H + 10 + 3 * 13 + PAD;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int desired = Math.round(dp(preferredHeightDp()));
        int h = MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(heightSpec) : desired;
        setMeasuredDimension(w, h);
    }

    private float text(Canvas c, String value, float x, float y, float size, int color, boolean bold, int alpha) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setAlpha(alpha);
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

        RectF card = new RectF(dp(1), dp(1), w - dp(1), h - dp(1));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(246, 251, 255));
        paint.setAlpha(255);
        c.drawRoundRect(card, dp(14), dp(14), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Color.rgb(207, 228, 245));
        c.drawRoundRect(card, dp(14), dp(14), paint);

        float left = dp(PAD), right = w - dp(PAD);
        float y = dp(PAD + 12);
        text(c, "Chuva acumulada nas últimas 24 h, por município", left, y, 12, INK, true, 255);
        y += dp(15);
        wrap(c, summary(), left, y, right - left, 9.5f, GREY, 2);
        y += dp(13) + dp(6);

        if (rows.isEmpty()) {
            text(c, "Sem dados de chuva por município nesta consulta. Atualize a tela inicial.",
                    left, y + dp(18), 10.5f, GREY, false, 255);
            return;
        }

        double max = 1.0;
        for (MunicipalRain m : rows) if (m.hasValue()) max = Math.max(max, m.mm24);
        double scale = Math.max(5.0, Math.ceil(max / 5.0) * 5.0);

        float nameW = (right - left) * 0.36f;
        float valueW = dp(56);
        float barLeft = left + nameW;
        float barRight = right - valueW;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(226, 240, 252));
        paint.setAlpha(255);
        c.drawRoundRect(new RectF(left, y, right, y + dp(HEAD_H)), dp(7), dp(7), paint);
        float hb = y + dp(HEAD_H / 2f + 3.5f);
        text(c, "MUNICÍPIO", left + dp(6), hb, 9, RAIN, true, 255);
        text(c, String.format(BR, "0 a %.0f mm", scale), barLeft, hb, 9, RAIN, true, 255);
        String mmHead = "24 H";
        text(c, mmHead, right - measure(mmHead, 9, true) - dp(4), hb, 9, RAIN, true, 255);
        y += dp(HEAD_H);

        long now = System.currentTimeMillis();
        for (int i = 0; i < rows.size(); i++) {
            MunicipalRain m = rows.get(i);
            float rowTop = y + i * dp(ROW_H);
            float t = clamp((now - appearAt - i * 60L) / 260f);
            int alpha = (int) (255 * t);
            float dy = (1f - t) * dp(6);
            float base = rowTop + dp(ROW_H / 2f + 4f) + dy;

            if (i % 2 == 0) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.WHITE);
                paint.setAlpha(255);
                c.drawRect(left, rowTop, right, rowTop + dp(ROW_H), paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(LINE);
            paint.setAlpha(255);
            c.drawRect(left, rowTop + dp(ROW_H) - dp(0.7f), right, rowTop + dp(ROW_H), paint);

            String name = fit(m.city, 10.5f, nameW - dp(10));
            text(c, name, left + dp(6), base, 10.5f, m.hasValue() ? INK : SOFT, true, alpha);

            // Barra: cresce depois que a linha aparece.
            float grow = clamp((now - appearAt - i * 60L - 180L) / 600f);
            grow = grow * grow * (3f - 2f * grow);
            float barTop = rowTop + dp(9) + dy, barBottom = rowTop + dp(ROW_H) - dp(9) + dy;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(236, 243, 250));
            paint.setAlpha(alpha);
            c.drawRoundRect(new RectF(barLeft, barTop, barRight, barBottom), dp(4), dp(4), paint);
            if (m.hasValue() && m.mm24 > 0) {
                float len = (float) Math.min(1.0, m.mm24 / scale) * (barRight - barLeft) * grow;
                paint.setColor(m.measured() ? RAIN : RAIN_LIGHT);
                paint.setAlpha(alpha);
                c.drawRoundRect(new RectF(barLeft, barTop, barLeft + Math.max(dp(3), len), barBottom), dp(4), dp(4), paint);
            }

            String value;
            int color;
            if (!m.hasValue()) {
                value = "sem leitura";
                color = SOFT;
            } else {
                value = String.format(BR, "%.1f mm", m.mm24 * grow);
                color = m.measured() ? RAIN : Color.rgb(84, 140, 184);
            }
            float vw = measure(value, m.hasValue() ? 10.5f : 9, m.hasValue());
            text(c, value, right - vw - dp(4), base - (m.measured() || !m.hasValue() ? 0 : dp(4)),
                    m.hasValue() ? 10.5f : 9, color, m.hasValue(), alpha);
            if (m.hasValue() && !m.measured()) {
                String tag = "estimativa";
                text(c, tag, right - measure(tag, 7.5f, false) - dp(4), base + dp(6), 7.5f, GREY, false, alpha);
            }
        }

        float noteY = y + rows.size() * dp(ROW_H) + dp(14);
        wrap(c, "Azul forte: média dos pluviômetros da Defesa Civil instalados no município. Azul claro "
                + "(estimativa): o pluviômetro não enviou leitura nas últimas 3 h e o valor é do modelo Open-Meteo "
                + "no local — costuma ficar acima do medido.", left, noteY, right - left, 9, GREY, 3);

        if (isShown()) postInvalidateDelayed(40);
    }

    private String summary() {
        double sum = 0;
        int measured = 0, estimated = 0, silent = 0;
        MunicipalRain top = null;
        for (MunicipalRain m : rows) {
            if (m.measured()) {
                sum += m.mm24;
                measured++;
                if (top == null || m.mm24 > top.mm24) top = m;
            } else if (m.hasValue()) {
                estimated++;
            } else {
                silent++;
            }
        }
        if (measured == 0 && estimated == 0) return "Nenhum município com leitura ou estimativa agora.";
        StringBuilder s = new StringBuilder();
        if (measured > 0) {
            s.append(String.format(BR, "Medido em %d município%s: média de %.1f mm", measured, measured == 1 ? "" : "s", sum / measured));
            if (top != null && top.mm24 >= 0.1) s.append(String.format(BR, ", mais chuva em %s (%.1f mm)", top.city, top.mm24));
            s.append('.');
        }
        if (estimated > 0) s.append(String.format(BR, " %d com estimativa", estimated));
        if (silent > 0) s.append(String.format(BR, "%s %d sem dado", estimated > 0 ? "," : "", silent));
        if (estimated > 0 || silent > 0) s.append('.');
        return s.toString().trim();
    }

    /** Escreve o texto quebrando por palavras, em até {@code maxLines} linhas de 13 dp. */
    private void wrap(Canvas c, String value, float x, float y, float maxWidth, float size, int color, int maxLines) {
        String[] words = value.split(" ");
        StringBuilder line = new StringBuilder();
        int lines = 0;
        for (int i = 0; i < words.length; i++) {
            String next = line.length() == 0 ? words[i] : line + " " + words[i];
            if (measure(next, size, false) <= maxWidth) {
                line.setLength(0);
                line.append(next);
                continue;
            }
            if (lines == maxLines - 1) {
                String cut = line.toString();
                while (cut.length() > 3 && measure(cut + "…", size, false) > maxWidth) cut = cut.substring(0, cut.length() - 1);
                text(c, cut + "…", x, y + lines * dp(13), size, color, false, 255);
                return;
            }
            text(c, line.toString(), x, y + lines * dp(13), size, color, false, 255);
            lines++;
            line.setLength(0);
            line.append(words[i]);
        }
        if (line.length() > 0) text(c, line.toString(), x, y + lines * dp(13), size, color, false, 255);
    }

    private String fit(String value, float size, float maxWidth) {
        if (value == null) return "";
        if (measure(value, size, true) <= maxWidth) return value;
        String s = value;
        while (s.length() > 3 && measure(s + "…", size, true) > maxWidth) s = s.substring(0, s.length() - 1);
        return s.trim() + "…";
    }

    private static float clamp(float t) {
        return t < 0f ? 0f : Math.min(1f, t);
    }
}
