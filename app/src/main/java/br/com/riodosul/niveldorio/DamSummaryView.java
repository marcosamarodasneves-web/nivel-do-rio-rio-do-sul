package br.com.riodosul.niveldorio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/** Exibe somente níveis e comportas das barragens, sem duplicar o card do rio. */
public class DamSummaryView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private DamReading taio, ituporanga;

    public DamSummaryView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
    }

    public void setData(DamReading taio, DamReading ituporanga) {
        this.taio = taio; this.ituporanga = ituporanga; invalidate();
    }

    private float dp(float v) { return v * density; }
    private void text(Canvas c, String s, float x, float y, float size, int color, boolean bold) {
        paint.setStyle(Paint.Style.FILL); paint.setColor(color); paint.setTextSize(dp(size));
        paint.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        c.drawText(s, x, y, paint);
    }

    @Override protected void onDraw(Canvas c) {
        float pad = dp(2), gap = dp(10), w = getWidth();
        float cardW = (w - gap - pad * 2) / 2f;
        drawDam(c, pad, cardW, taio, "Taió");
        drawDam(c, pad + cardW + gap, cardW, ituporanga, "Ituporanga");
    }

    private void drawDam(Canvas c, float left, float width, DamReading d, String fallback) {
        paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.FILL);
        c.drawRoundRect(new RectF(left, 0, left + width, getHeight()), dp(12), dp(12), paint);
        String name = d == null || d.name == null ? fallback : d.name.replace("Barragem Oeste ", "").replace("Barragem Sul ", "");
        text(c, name, left + dp(12), dp(25), 13, Color.rgb(28, 65, 94), true);
        if (d == null) { text(c, "Sem dados", left + dp(12), dp(53), 12, Color.GRAY, false); return; }
        text(c, String.format(new Locale("pt", "BR"), "%.2f m", d.levelMeters), left + dp(12), dp(54), 20, Color.rgb(31, 103, 151), true);
        text(c, String.format(new Locale("pt", "BR"), "%.1f%% ocupado", d.capacityPercent), left + dp(12), dp(75), 10, Color.rgb(102,112,133), false);
        paint.setColor(Color.rgb(224,232,241));
        c.drawRoundRect(new RectF(left+dp(12),dp(83),left+width-dp(12),dp(91)),dp(4),dp(4),paint);
        paint.setColor(Color.rgb(31,145,91));
        float pct=(float)Math.max(0,Math.min(100,d.capacityPercent));
        c.drawRoundRect(new RectF(left+dp(12),dp(83),left+dp(12)+(width-dp(24))*pct/100f,dp(91)),dp(4),dp(4),paint);
        text(c, "Comportas  " + d.gatesOpen + "/" + d.gatesTotal + " abertas", left+dp(12), dp(112), 10, Color.rgb(44,55,65), true);
        drawGates(c,left+dp(12),dp(123),width-dp(24),d);
    }

    private void drawGates(Canvas c,float left,float top,float width,DamReading d) {
        int total=Math.max(1,d.gatesTotal); float gap=dp(3), segment=(width-gap*(total-1))/total;
        for(int i=0;i<total;i++) {
            boolean open=d.gateStates!=null&&i<d.gateStates.length?d.gateStates[i]:i<d.gatesOpen;
            float x=left+i*(segment+gap); paint.setColor(open?Color.rgb(31,160,106):Color.rgb(218,91,82));
            c.drawRoundRect(new RectF(x,top,x+segment,top+dp(22)),dp(5),dp(5),paint);
            text(c,open?"A":"F",x+segment/2-dp(4),top+dp(15),9,Color.WHITE,true);
        }
    }
}
