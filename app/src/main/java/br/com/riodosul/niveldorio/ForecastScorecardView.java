package br.com.riodosul.niveldorio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Grade "o que foi previsto × o que o rio marcou" para os três horizontes.
 * Cada linha é um momento em que o app calculou a previsão; as colunas +1 h, +2 h e +3 h mostram o
 * nível previsto, se acertou e o nível real medido naquele horário. As linhas entram animadas.
 */
public class ForecastScorecardView extends View {
    private static final Locale BR = new Locale("pt", "BR");
    private static final int INK = Color.rgb(28, 65, 94);
    private static final int BODY = Color.rgb(55, 65, 81);
    private static final int GREY = Color.rgb(102, 112, 133);
    private static final int SOFT = Color.rgb(150, 158, 168);
    private static final int LINE = Color.rgb(224, 232, 241);
    private static final int PURPLE = Color.rgb(123, 31, 162);
    private static final int BLUE = Color.rgb(21, 101, 192);

    private static final float PAD = 12f;
    private static final float TILE_H = 64f;
    private static final float HEAD_H = 24f;
    private static final float ROW_H = 40f;
    private static final int MAX_ROWS = 12;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final List<ForecastStore.Issue> issues = new ArrayList<>();
    private final ForecastStore.Stats[] stats = new ForecastStore.Stats[ForecastStore.HORIZONS];
    private String bridgeLabel = "";
    private boolean loading = true;
    private long appearAt = System.currentTimeMillis();

    public ForecastScorecardView(Context context) {
        this(context, null);
    }

    public ForecastScorecardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
    }

    public void setData(List<ForecastStore.Issue> issues, ForecastStore.Stats[] stats, String bridgeLabel) {
        this.issues.clear();
        if (issues != null) {
            for (int i = 0; i < issues.size() && i < MAX_ROWS; i++) this.issues.add(issues.get(i));
        }
        for (int i = 0; i < this.stats.length; i++) {
            this.stats[i] = stats != null && i < stats.length ? stats[i] : null;
        }
        this.bridgeLabel = bridgeLabel == null ? "" : bridgeLabel;
        this.loading = false;
        this.appearAt = System.currentTimeMillis();
        requestLayout();
        invalidate();
    }

    private float dp(float v) { return v * density; }

    /** Altura que o conteúdo precisa, em dp. */
    public float preferredHeightDp() {
        if (loading) return PAD * 2 + 20;
        float h = PAD + 16;                 // título
        h += TILE_H + 12;                    // quadros de acerto
        if (issues.isEmpty()) return h + 34 + PAD;
        h += HEAD_H + issues.size() * ROW_H;
        h += 12 + 26;                        // legenda em duas linhas
        return h + PAD;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int desired = Math.round(dp(preferredHeightDp()));
        int h = MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(heightSpec) : Math.max(desired, Math.round(dp(48)));
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

    private void center(Canvas c, String value, float cx, float y, float size, int color, boolean bold, int alpha) {
        text(c, value, cx - measure(value, size, bold) / 2f, y, size, color, bold, alpha);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        RectF card = new RectF(dp(1), dp(1), w - dp(1), h - dp(1));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(247, 251, 255));
        c.drawRoundRect(card, dp(14), dp(14), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(LINE);
        c.drawRoundRect(card, dp(14), dp(14), paint);

        float left = dp(PAD);
        float right = w - dp(PAD);
        float y = dp(PAD + 12);
        int full = 255;

        if (loading) {
            text(c, "Consultando as previsões anteriores…", left, y, 11, GREY, false, full);
            if (isShown()) postInvalidateDelayed(200);
            return;
        }

        text(c, "Previsão × nível real — " + bridgeLabel, left, y, 12, INK, true, full);
        y += dp(10);

        long now = System.currentTimeMillis();
        drawTiles(c, left, right, y, now);
        y += dp(TILE_H + 12);

        if (issues.isEmpty()) {
            text(c, "Ainda não há previsão anterior para conferir. Ela é registrada a cada atualização",
                    left, y + dp(8), 10.5f, GREY, false, full);
            text(c, "com o app aberto e conferida quando o horário previsto passa.",
                    left, y + dp(22), 10.5f, GREY, false, full);
            return;
        }

        // Cabeçalho da grade.
        float[] col = columns(left, right);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(232, 242, 253));
        paint.setAlpha(full);
        c.drawRoundRect(new RectF(left, y, right, y + dp(HEAD_H)), dp(7), dp(7), paint);
        float hb = y + dp(HEAD_H / 2f + 3.5f);
        text(c, "FEITA ÀS", col[0] + dp(6), hb, 9, BLUE, true, full);
        for (int k = 0; k < 3; k++) center(c, "+" + (k + 1) + " h", col[k + 1], hb, 9.5f, PURPLE, true, full);

        y += dp(HEAD_H);
        SimpleDateFormat dayMin = new SimpleDateFormat("dd/MM HH:mm", BR);
        SimpleDateFormat min = new SimpleDateFormat("HH:mm", BR);

        for (int i = 0; i < issues.size(); i++) {
            ForecastStore.Issue is = issues.get(i);
            float rowTop = y + i * dp(ROW_H);
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(full);
            if (i % 2 == 0) {
                paint.setColor(Color.WHITE);
                c.drawRect(left, rowTop, right, rowTop + dp(ROW_H), paint);
            }
            paint.setColor(LINE);
            c.drawRect(left, rowTop + dp(ROW_H) - dp(0.7f), right, rowTop + dp(ROW_H), paint);

            // Entrada animada: cada linha sobe e ganha opacidade, uma de cada vez.
            float t = clamp((now - appearAt - i * 90L) / 240f);
            int alpha = (int) (full * t);
            float dy = (1f - t) * dp(7);
            float line1 = rowTop + dp(17) + dy;
            float line2 = rowTop + dp(31) + dy;

            String when = isToday(is.baseTime) ? min.format(new Date(is.baseTime)) : dayMin.format(new Date(is.baseTime));
            text(c, when, col[0] + dp(6), line1, 10.5f, BODY, true, alpha);
            if (!Double.isNaN(is.currentLevel) && is.currentLevel > 0) {
                text(c, String.format(BR, "rio %.2f m", is.currentLevel), col[0] + dp(6), line2, 9, GREY, false, alpha);
            }
            for (int k = 0; k < 3; k++) drawCell(c, is.rows[k], col[k + 1], line1, line2, alpha);
        }

        // Destaque discreto na linha mais recente, depois de todas aparecerem.
        if (now - appearAt > issues.size() * 90L + 300L) {
            float pulse = (now % 2400L) / 2400f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(PURPLE);
            paint.setAlpha((int) (16 * (1f - pulse)));
            c.drawRect(left, y, right, y + dp(ROW_H), paint);
            paint.setAlpha(full);
        }

        float noteY = y + issues.size() * dp(ROW_H) + dp(14);
        text(c, "✔ dentro da margem   ≈ até o dobro dela   ✘ fora   … aguardando o horário",
                left, noteY, 9, GREY, false, full);
        text(c, "Embaixo de cada previsão, o nível que o rio marcou no horário previsto (± 20 min).",
                left, noteY + dp(13), 9, GREY, false, full);

        if (isShown()) postInvalidateDelayed(40);
    }

    /** Três quadros com o aproveitamento de cada horizonte. */
    private void drawTiles(Canvas c, float left, float right, float top, long now) {
        float gap = dp(8);
        float tw = (right - left - 2 * gap) / 3f;
        float grow = clamp((now - appearAt) / 700f);
        grow = grow * grow * (3f - 2f * grow);
        for (int k = 0; k < 3; k++) {
            ForecastStore.Stats st = stats[k];
            float x0 = left + k * (tw + gap);
            RectF r = new RectF(x0, top, x0 + tw, top + dp(TILE_H));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            paint.setAlpha(255);
            c.drawRoundRect(r, dp(10), dp(10), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(LINE);
            c.drawRoundRect(r, dp(10), dp(10), paint);

            float cx = x0 + tw / 2f;
            center(c, "+" + (k + 1) + " h", cx, top + dp(14), 9.5f, PURPLE, true, 255);
            boolean has = st != null && st.resolved() > 0;
            int pct = has ? st.hitRatePercent() : 0;
            int color = !has ? SOFT : pct >= 80 ? Cotas.COR_NORMAL : pct >= 60 ? Cotas.COR_ATENCAO : Cotas.COR_EMERGENCIA;
            String big = has ? Math.round(pct * grow) + "%" : "—";
            center(c, big, cx, top + dp(33), 17, color, true, 255);
            String sub = !has ? (st != null && st.pending > 0 ? st.pending + " aguardando" : "sem conferência")
                    : String.format(BR, "erro médio %.1f cm", st.meanAbsErrorCm);
            center(c, sub, cx, top + dp(50), 8.5f, GREY, false, 255);
            if (has) center(c, String.format(BR, "%d conferidas", st.resolved()), cx, top + dp(61), 7.5f, SOFT, false, 255);

            // Barrinha de acerto no rodapé do quadro.
            if (has) {
                float bx0 = x0 + dp(10), bx1 = x0 + tw - dp(10), by = top + dp(43);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.rgb(232, 238, 245));
                c.drawRoundRect(new RectF(bx0, by - dp(2), bx1, by), dp(1), dp(1), paint);
                paint.setColor(color);
                c.drawRoundRect(new RectF(bx0, by - dp(2), bx0 + (bx1 - bx0) * pct / 100f * grow, by), dp(1), dp(1), paint);
            }
        }
    }

    private void drawCell(Canvas c, ForecastStore.Row r, float cx, float line1, float line2, int alpha) {
        if (r == null) {
            center(c, "—", cx, line1, 11, SOFT, false, alpha);
            return;
        }
        String symbol;
        int color;
        switch (r.outcome) {
            case ForecastStore.OUTCOME_HIT: symbol = "✔"; color = Cotas.COR_NORMAL; break;
            case ForecastStore.OUTCOME_NEAR: symbol = "≈"; color = Cotas.COR_ATENCAO; break;
            case ForecastStore.OUTCOME_MISS: symbol = "✘"; color = Cotas.COR_EMERGENCIA; break;
            case ForecastStore.OUTCOME_NO_DATA: symbol = "—"; color = SOFT; break;
            default: symbol = "…"; color = SOFT; break;
        }
        String value = String.format(BR, "%.2f", r.predictedLevel);
        float vw = measure(value, 11, true);
        float sw = measure(symbol, 11, true);
        float x = cx - (vw + dp(4) + sw) / 2f;
        text(c, value, x, line1, 11, PURPLE, true, alpha);
        text(c, symbol, x + vw + dp(4), line1, 11, color, true, alpha);

        String second;
        int secondColor = GREY;
        if (hasActual(r) && (r.outcome == ForecastStore.OUTCOME_HIT || r.outcome == ForecastStore.OUTCOME_NEAR
                || r.outcome == ForecastStore.OUTCOME_MISS)) {
            String err = (r.errorCm >= 0 ? "+" : "−") + String.format(BR, "%.0f", Math.abs(r.errorCm));
            second = String.format(BR, "real %.2f (%s)", r.actualLevel, err);
            if (measure(second, 9, false) > dp(78)) second = String.format(BR, "%.2f (%s)", r.actualLevel, err);
            secondColor = color == SOFT ? GREY : color;
        } else if (r.outcome == ForecastStore.OUTCOME_NO_DATA) {
            second = "sem medição";
        } else {
            second = "aguarda";
            secondColor = SOFT;
        }
        center(c, second, cx, line2, 9, secondColor, false, alpha);
    }

    /** Centro de cada coluna: [0] = borda esquerda da coluna "feita às", [1..3] = centros de +1/+2/+3 h. */
    private float[] columns(float left, float right) {
        float usable = right - left;
        float first = usable * 0.25f;
        float each = (usable - first) / 3f;
        return new float[] { left, left + first + each * 0.5f, left + first + each * 1.5f, left + first + each * 2.5f };
    }

    private static boolean hasActual(ForecastStore.Row r) {
        return !Double.isNaN(r.actualLevel) && r.actualLevel > 0;
    }

    private static boolean isToday(long time) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
        return f.format(new Date(time)).equals(f.format(new Date()));
    }

    private static float clamp(float t) {
        return t < 0f ? 0f : Math.min(1f, t);
    }
}
