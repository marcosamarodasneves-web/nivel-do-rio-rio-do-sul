package br.com.riodosul.niveldorio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

public final class ChartRenderer {
    private ChartRenderer() {}

    public static Bitmap render(List<Sample> input, int width, int height) {
        width = Math.max(width, 320);
        height = Math.max(height, 120);
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.TRANSPARENT);

        Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        grid.setColor(Color.rgb(220, 229, 239));
        grid.setStrokeWidth(1.2f);
        for (int i = 1; i <= 3; i++) {
            float y = height * i / 4f;
            c.drawLine(14, y, width - 8, y, grid);
        }

        List<Sample> data = input == null ? new ArrayList<>() : input;
        if (data.size() < 2) {
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setColor(Color.rgb(102, 112, 133));
            t.setTextSize(Math.max(22, width / 28f));
            c.drawText("Coletando histórico…", 18, height / 2f + 8, t);
            return bmp;
        }

        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (Sample s : data) { min = Math.min(min, s.value); max = Math.max(max, s.value); }
        if (max - min < 0.40) { max += 0.20; min -= 0.20; }
        double pad = (max - min) * 0.15;
        min = Math.max(0, min - pad); max += pad;
        long t0 = data.get(0).timeMillis, t1 = data.get(data.size() - 1).timeMillis;
        if (t1 <= t0) t1 = t0 + 1;

        float left = 14, right = width - 10, top = 10, bottom = height - 14;
        Path line = new Path();
        Path fill = new Path();
        for (int i = 0; i < data.size(); i++) {
            Sample s = data.get(i);
            float x = left + (float)((s.timeMillis - t0) / (double)(t1 - t0)) * (right - left);
            float y = bottom - (float)((s.value - min) / (max - min)) * (bottom - top);
            if (i == 0) { line.moveTo(x, y); fill.moveTo(x, bottom); fill.lineTo(x, y); }
            else { line.lineTo(x, y); fill.lineTo(x, y); }
        }
        fill.lineTo(right, bottom); fill.close();

        Paint area = new Paint(Paint.ANTI_ALIAS_FLAG);
        area.setColor(Color.argb(55, 21, 101, 192));
        c.drawPath(fill, area);

        Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
        lp.setStyle(Paint.Style.STROKE);
        lp.setStrokeWidth(Math.max(4, width / 120f));
        lp.setStrokeCap(Paint.Cap.ROUND);
        lp.setStrokeJoin(Paint.Join.ROUND);
        lp.setColor(Color.rgb(21, 101, 192));
        c.drawPath(line, lp);

        Sample last = data.get(data.size() - 1);
        float lx = right;
        float ly = bottom - (float)((last.value - min) / (max - min)) * (bottom - top);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(Color.rgb(21, 101, 192));
        c.drawCircle(lx - 1, ly, Math.max(5, width / 80f), dot);
        return bmp;
    }
}
