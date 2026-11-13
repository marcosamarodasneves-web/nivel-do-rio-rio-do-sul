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
 * Histórico e acurácia enviados pelo servidor central. Esta view não compara níveis, não classifica
 * resultados e não grava previsões: apenas apresenta os DTOs recebidos no GET.
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
    private static final float TILE_H = 72f;
    private static final float HEAD_H = 24f;
    private static final float ROW_H = 40f;
    private static final int MAX_ROWS = 12;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final List<HydrologyForecastClient.HistoryGroup> groups = new ArrayList<>();
    private final HydrologyForecastClient.AccuracyRow[] stats = new HydrologyForecastClient.AccuracyRow[3];
    private String bridgeLabel = "";
    private String disclaimer = "";
    private boolean loading = true;
    private long appearAt = System.currentTimeMillis();

    public ForecastScorecardView(Context context) { this(context, null); }
    public ForecastScorecardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
    }

    public void setLoading() {
        loading = true;
        requestLayout();
        invalidate();
    }

    public void setData(List<HydrologyForecastClient.HistoryGroup> groups,
                        HydrologyForecastClient.AccuracyRow[] stats,
                        String bridgeLabel, String disclaimer) {
        this.groups.clear();
        if (groups != null) {
            for (int i = 0; i < groups.size() && i < MAX_ROWS; i++) this.groups.add(groups.get(i));
        }
        for (int i = 0; i < this.stats.length; i++) {
            this.stats[i] = stats != null && i < stats.length ? stats[i] : null;
        }
        this.bridgeLabel = bridgeLabel == null ? "" : bridgeLabel;
        this.disclaimer = disclaimer == null ? "" : disclaimer;
        this.loading = false;
        this.appearAt = System.currentTimeMillis();
        requestLayout();
        invalidate();
    }

    private float dp(float value) { return value * density; }
    public float preferredHeightDp() {
        if (loading) return PAD * 2 + 20;
        float height = PAD + 18 + TILE_H + 12;
        if (groups.isEmpty()) return height + 42 + PAD;
        return height + HEAD_H + groups.size() * ROW_H + 40 + PAD;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int desired = Math.round(dp(preferredHeightDp()));
        int height = MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(heightSpec) : Math.max(desired, Math.round(dp(48)));
        setMeasuredDimension(width, height);
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
        float left = dp(PAD), right = w - dp(PAD), y = dp(PAD + 12);
        if (loading) {
            text(c, "Consultando histórico e acurácia do servidor…", left, y, 11, GREY, false, 255);
            if (isShown()) postInvalidateDelayed(200);
            return;
        }
        text(c, "Servidor · últimos 7 dias — " + bridgeLabel, left, y, 12, INK, true, 255);
        y += dp(10);
        drawTiles(c, left, right, y);
        y += dp(TILE_H + 12);
        if (groups.isEmpty()) {
            text(c, "O servidor não retornou histórico para esta ponte.", left, y + dp(8), 10.5f, GREY, false, 255);
            text(c, "Nenhuma previsão antiga é criada ou calculada neste aparelho.", left, y + dp(23), 10.5f, GREY, false, 255);
            if (!disclaimer.isEmpty()) text(c, disclaimer, left, y + dp(38), 9, SOFT, false, 255);
            return;
        }
        drawHeader(c, left, right, y);
        y += dp(HEAD_H);
        SimpleDateFormat dayMin = new SimpleDateFormat("dd/MM HH:mm", BR);
        SimpleDateFormat min = new SimpleDateFormat("HH:mm", BR);
        long now = System.currentTimeMillis();
        float[] columns = columns(left, right);
        for (int i = 0; i < groups.size(); i++) {
            HydrologyForecastClient.HistoryGroup group = groups.get(i);
            float rowTop = y + i * dp(ROW_H);
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(255);
            if (i % 2 == 0) {
                paint.setColor(Color.WHITE);
                c.drawRect(left, rowTop, right, rowTop + dp(ROW_H), paint);
            }
            paint.setColor(LINE);
            c.drawRect(left, rowTop + dp(ROW_H) - dp(.7f), right, rowTop + dp(ROW_H), paint);
            float t = clamp((now - appearAt - i * 90L) / 240f);
            int alpha = (int) (255 * t);
            float dy = (1f - t) * dp(7);
            float line1 = rowTop + dp(17) + dy, line2 = rowTop + dp(31) + dy;
            String when = isToday(group.baseTime) ? min.format(new Date(group.baseTime)) : dayMin.format(new Date(group.baseTime));
            text(c, when, columns[0] + dp(6), line1, 10.5f, BODY, true, alpha);
            if (!Double.isNaN(group.currentLevel) && group.currentLevel > 0) {
                text(c, String.format(BR, "rio %.2f m", group.currentLevel), columns[0] + dp(6), line2, 9, GREY, false, alpha);
            }
            for (int k = 0; k < 3; k++) drawCell(c, group.rows[k], columns[k + 1], line1, line2, alpha);
        }
        float noteY = y + groups.size() * dp(ROW_H) + dp(14);
        text(c, "✔ dentro   ≈ quase   ✘ fora   — sem medição   … pendente", left, noteY, 9, GREY, false, 255);
        text(c, "Níveis, margem, resultado e estatísticas são os valores do servidor.", left, noteY + dp(13), 9, GREY, false, 255);
        if (!disclaimer.isEmpty()) text(c, disclaimer, left, noteY + dp(26), 8.5f, SOFT, false, 255);
        if (isShown()) postInvalidateDelayed(40);
    }

    private void drawHeader(Canvas c, float left, float right, float y) {
        float[] col = columns(left, right);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(232, 242, 253));
        paint.setAlpha(255);
        c.drawRoundRect(new RectF(left, y, right, y + dp(HEAD_H)), dp(7), dp(7), paint);
        float baseline = y + dp(HEAD_H / 2f + 3.5f);
        text(c, "FEITA ÀS", col[0] + dp(6), baseline, 9, BLUE, true, 255);
        for (int k = 0; k < 3; k++) center(c, "+" + (k + 1) + " h", col[k + 1], baseline, 9.5f, PURPLE, true, 255);
    }

    private void drawTiles(Canvas c, float left, float right, float top) {
        float gap = dp(8), tileWidth = (right - left - 2 * gap) / 3f;
        for (int k = 0; k < 3; k++) {
            HydrologyForecastClient.AccuracyRow stat = stats[k];
            float x0 = left + k * (tileWidth + gap);
            RectF tile = new RectF(x0, top, x0 + tileWidth, top + dp(TILE_H));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            paint.setAlpha(255);
            c.drawRoundRect(tile, dp(10), dp(10), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(LINE);
            c.drawRoundRect(tile, dp(10), dp(10), paint);
            float cx = x0 + tileWidth / 2f;
            center(c, "+" + (k + 1) + " h", cx, top + dp(14), 9.5f, PURPLE, true, 255);
            boolean has = stat != null && stat.previsoes > 0;
            String big = has && stat.pctDentro != null ? String.format(BR, "%.0f%%", stat.pctDentro) : "—";
            int color = has ? Cotas.COR_NORMAL : SOFT;
            center(c, big, cx, top + dp(35), 17, color, true, 255);
            String sub = !has ? "sem dados" : stat.conferidas + "/" + stat.previsoes + " conferidas";
            center(c, sub, cx, top + dp(53), 8.5f, GREY, false, 255);
            if (has && stat.erroMedioCm != null) {
                center(c, String.format(BR, "erro médio %.1f cm", stat.erroMedioCm), cx, top + dp(66), 7.5f, SOFT, false, 255);
            }
        }
    }

    private void drawCell(Canvas c, HydrologyForecastClient.HistoryRow row, float cx,
                          float line1, float line2, int alpha) {
        if (row == null || Double.isNaN(row.predictedLevel)) {
            center(c, "—", cx, line1, 11, SOFT, false, alpha);
            return;
        }
        String outcome = row.outcome == null ? "pendente" : row.outcome.toLowerCase(Locale.ROOT);
        String symbol;
        int color;
        if ("dentro".equals(outcome)) { symbol = "✔"; color = Cotas.COR_NORMAL; }
        else if ("quase".equals(outcome)) { symbol = "≈"; color = Cotas.COR_ATENCAO; }
        else if ("fora".equals(outcome)) { symbol = "✘"; color = Cotas.COR_EMERGENCIA; }
        else if ("sem_medicao".equals(outcome)) { symbol = "—"; color = SOFT; }
        else { symbol = "…"; color = SOFT; }
        String value = String.format(BR, "%.2f", row.predictedLevel);
        float vw = measure(value, 11, true), sw = measure(symbol, 11, true);
        float x = cx - (vw + dp(4) + sw) / 2f;
        text(c, value, x, line1, 11, PURPLE, true, alpha);
        text(c, symbol, x + vw + dp(4), line1, 11, color, true, alpha);
        String second = "pendente";
        int secondColor = SOFT;
        if (row.actualTime > 0 && !Double.isNaN(row.actualLevel) && row.actualLevel > 0) {
            second = String.format(BR, "real %.2f", row.actualLevel);
            if (!Double.isNaN(row.errorCm)) second += " (" + signed(row.errorCm) + ")";
            secondColor = color;
        } else if ("sem_medicao".equals(outcome)) {
            second = "sem medição";
        }
        if (measure(second, 9, false) > dp(82)) second = String.format(BR, "%.2f", row.actualLevel);
        center(c, second, cx, line2, 9, secondColor, false, alpha);
    }

    private float[] columns(float left, float right) {
        float usable = right - left, first = usable * .25f, each = (usable - first) / 3f;
        return new float[] { left, left + first + each * .5f, left + first + each * 1.5f,
                left + first + each * 2.5f };
    }
    private static String signed(double value) {
        return (value >= 0 ? "+" : "−") + String.format(BR, "%.0f cm", Math.abs(value));
    }
    private static boolean isToday(long time) {
        if (time <= 0) return false;
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd", Locale.US);
        return format.format(new Date(time)).equals(format.format(new Date()));
    }
    private static float clamp(float value) { return value < 0f ? 0f : Math.min(1f, value); }
}
