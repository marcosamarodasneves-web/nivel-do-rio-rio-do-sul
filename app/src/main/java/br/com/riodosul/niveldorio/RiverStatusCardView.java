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
        int color = alertColor(level);
        int light = lightColor(level);

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

        text(c, "≋", dp(25), dp(86), 29, Color.rgb(20, 25, 20), true);
        float left = dp(82);
        String title = bridge == null ? "Ponte" : bridge.displayName + " - " + bridge.river;
        text(c, title, left, dp(29), 13, Color.rgb(28, 65, 94), true);
        text(c, "Referência: " + reference(), left, dp(48), 9, Color.rgb(102, 112, 133), false);

        String status = status(level);
        paint.setColor(light);
        paint.setStyle(Paint.Style.FILL);
        c.drawRoundRect(new RectF(w - dp(92), dp(14), w - dp(13), dp(38)), dp(6), dp(6), paint);
        text(c, status.toUpperCase(new Locale("pt", "BR")), w - dp(83), dp(29), 9, color, true);

        String value = level < 0 ? "--,--" : String.format(new Locale("pt", "BR"), "%.2f", level);
        text(c, value, left, dp(91), 31, darkColor(level), true);
        text(c, "m", left + dp(82), dp(91), 14, darkColor(level), true);
        text(c, "NÍVEL DO RIO", left + dp(102), dp(88), 9, Color.rgb(65, 80, 91), true);

        float barLeft = left, barRight = w - dp(14), barTop = dp(106), barBottom = dp(118);
        drawScale(c, barLeft, barRight, barTop, barBottom, color, level);
        text(c, "4,50", barLeft + (barRight - barLeft) * 4.5f / 8f - dp(11), dp(137), 8, Color.rgb(102, 112, 133), false);
        text(c, "5,50", barLeft + (barRight - barLeft) * 5.5f / 8f - dp(11), dp(137), 8, Color.rgb(102, 112, 133), false);
        text(c, "6,50", barLeft + (barRight - barLeft) * 6.5f / 8f - dp(11), dp(137), 8, Color.rgb(102, 112, 133), false);
        text(c, "8,00 m", barRight - dp(26), dp(137), 8, Color.rgb(102, 112, 133), false);

        String time = reading == null ? "Leitura indisponível" : "Leitura: " + reading.readingTime;
        text(c, time, w - dp(185), dp(168), 9, Color.rgb(75, 82, 88), false);
    }

    private void drawScale(Canvas c, float left, float right, float top, float bottom, int color, double level) {
        float[] limits = {0f, 4.5f, 5.5f, 6.5f, 8f};
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
        if (level < 5.5) return "Atenção";
        if (level < 6.5) return "Alerta";
        return "Emergência";
    }

    private int alertColor(double level) {
        if (level < 0 || level < 4.5) return Color.rgb(31, 145, 91);
        if (level < 5.5) return Color.rgb(214, 164, 8);
        if (level < 6.5) return Color.rgb(211, 104, 20);
        return Color.rgb(185, 39, 39);
    }

    private int lightColor(double level) {
        if (level < 0 || level < 4.5) return Color.rgb(224, 247, 234);
        if (level < 5.5) return Color.rgb(255, 247, 193);
        if (level < 6.5) return Color.rgb(255, 232, 207);
        return Color.rgb(255, 222, 222);
    }

    private int darkColor(double level) {
        return level >= 0 && level >= 5.5 ? alertColor(level) : Color.rgb(125, 83, 6);
    }
}
