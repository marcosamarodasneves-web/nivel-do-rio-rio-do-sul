package br.com.riodosul.niveldorio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class RiverChartView extends View {
    private List<Sample> samples = new ArrayList<>();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    public RiverChartView(Context c, AttributeSet attrs) {
        super(c, attrs);
        text.setColor(0xFF667085);
        text.setTextSize(30f);
        setPadding(8, 8, 8, 8);
    }

    public void setSamples(List<Sample> samples) {
        this.samples = samples == null ? new ArrayList<>() : new ArrayList<>(samples);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = Math.max(1, getWidth() - getPaddingLeft() - getPaddingRight());
        int h = Math.max(1, getHeight() - getPaddingTop() - getPaddingBottom() - 34);
        Bitmap bmp = ChartRenderer.render(samples, w, h);
        canvas.drawBitmap(bmp, getPaddingLeft(), getPaddingTop() + 28, null);
        canvas.drawText("Histórico coletado no aparelho • até 24 h", getPaddingLeft() + 8, getPaddingTop() + 22, text);
    }
}
