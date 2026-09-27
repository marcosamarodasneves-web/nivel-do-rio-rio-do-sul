package br.com.riodosul.niveldorio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/** Card compacto inspirado na ficha de monitoramento do portal oficial. */
public class RiverStatusCardView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private Bridge bridge;
    private RiverReading reading;

    public RiverStatusCardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void setData(Bridge bridge, RiverReading reading) {
        this.bridge = bridge;
        this.reading = reading;
        invalidate();
    }

    private float dp(float v) { return v * density; }

    private void text(Canvas c, String value, float x, float y, float size, int color, boolean bold) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(dp(size));
        paint.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        c.drawText(value, x, y, paint);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();
        double level = reading == null ? -1 : reading.levelMeters;
        String status = status(level);
        int color = alertColor(level);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        c.drawRoundRect(new RectF(dp(2), dp(2), w - dp(2), h - dp(2)), dp(14), dp(14), paint);
        paint.setColor(color);
        c.drawRoundRect(new RectF(dp(2), dp(2), dp(68), h - dp(2)), dp(14), dp(14), paint);
        c.drawRect(dp(35), dp(2), dp(68), h - dp(2), paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(color);
        c.drawRoundRect(new RectF(dp(2), dp(2), w - dp(2), h - dp(2)), dp(14), dp(14), paint);

        c.save();
        c.rotate(-90, dp(35), h / 2f);
        String verticalStatus = status.toUpperCase(new Locale("pt", "BR"));
        paint.setTextSize(dp(11));
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        text(c, verticalStatus, dp(35) - paint.measureText(verticalStatus) / 2f, h / 2f + dp(4), 11, Color.rgb(20, 25, 20), true);
        c.restore();
        float left = dp(82);
        String title = bridge == null ? "Ponte" : bridge.displayName + " - " + bridge.river;
        text(c, title, left, dp(29), 13, Color.rgb(28, 65, 94), true);
        text(c, "Referência: " + reference(), left, dp(48), 9, Color.rgb(102, 112, 133), false);

        String value = level < 0 ? "--,--" : String.format(new Locale("pt", "BR"), "%.2f", level);
        text(c, value, left, dp(91), 31, darkColor(level), true);
        text(c, "m", left + dp(82), dp(91), 14, darkColor(level), true);
        text(c, "NÍVEL DO RIO", left + dp(102), dp(88), 9, Color.rgb(65, 80, 91), true);

        float barLeft = left, barRight = w - dp(14), barTop = dp(106), barBottom = dp(118);
        drawScale(c, barLeft, barRight, barTop, barBottom, color, level);
        text(c, "Leitura: " + (reading == null ? "--" : reading.readingTime), barLeft, dp(137), 8, Color.rgb(75, 82, 88), false);
        text(c, "4,50", barLeft + (barRight - barLeft) * 4.5f / 8f - dp(11), dp(137), 8, Color.rgb(102, 112, 133), false);
        text(c, "6,00", barLeft + (barRight - barLeft) * 6.0f / 8f - dp(11), dp(137), 8, Color.rgb(102, 112, 133), false);
        text(c, "6,50", barLeft + (barRight - barLeft) * 6.5f / 8f - dp(11), dp(137), 8, Color.rgb(102, 112, 133), false);
        text(c, "8,00 m", barRight - dp(26), dp(137), 8, Color.rgb(102, 112, 133), false);

    }

    private void drawScale(Canvas c, float left, float right, float top, float bottom, int color, double level) {
        float[] limits = {0f, 4.5f, 6.0f, 6.5f, 8f};
        int[] colors = {Color.rgb(38, 170, 108), Color.rgb(250, 205, 34), Color.rgb(239, 139, 45), Color.rgb(221, 83, 82)};
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 4; i++) {
            paint.setColor(colors[i]);
            float x1 = left + (right - left) * limits[i] / 8f;
            float x2 = left + (right - left) * limits[i + 1] / 8f;
            c.drawRect(x1, top, x2, bottom, paint);
        }
        if (level >= 0) {
            float x = left + (right - left) * (float) Math.max(0, Math.min(8, level)) / 8f;
            paint.setColor(Color.WHITE);
            c.drawCircle(x, (top + bottom) / 2, dp(6), paint);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            c.drawCircle(x, (top + bottom) / 2, dp(6), paint);
        }
    }

    private String reference() {
        if (bridge == null) return "não informada";
        switch (bridge) {
            case DOM_TITO: return "Atrás do Catarinão Super";
            case RICARDO_KANITZ: return "Bonfim";
            case BR470: return "Perto do Pamplona";
            default: return "não informada";
        }
    }

    private String status(double level) {
        if (level < 0) return "Sem dados";
        if (level < 4.5) return "Normal";
        if (level < 6.0) return "Atenção";
        if (level < 6.5) return "Alerta";
        return "Emergência";
    }

    private int alertColor(double level) {
        if (level < 0 || level < 4.5) return Color.rgb(31, 145, 91);
        if (level < 6.0) return Color.rgb(214, 164, 8);
        if (level < 6.5) return Color.rgb(211, 104, 20);
        return Color.rgb(185, 39, 39);
    }

    private int darkColor(double level) {
        return level >= 0 && level >= 6.0 ? alertColor(level) : Color.rgb(125, 83, 6);
    }
}
