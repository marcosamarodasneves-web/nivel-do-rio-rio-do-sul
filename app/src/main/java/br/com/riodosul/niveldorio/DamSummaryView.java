package br.com.riodosul.niveldorio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * Cards das barragens. A água ao fundo sobe até a ocupação do reservatório e ondula; as comportas
 * abertas mostram a água passando.
 *
 * Cada card tem uma borda fina e arredondada em três estados — verde (normal), âmbar (operando ou
 * cheio) e vermelho (no limite) — e responde a dois toques seguidos para abrir o histórico de 24 h
 * com chuva.
 */
public class DamSummaryView extends View {
    public static final String KEY_TAIO = "taio";
    public static final String KEY_ITUPORANGA = "ituporanga";

    /** Estados da borda. */
    public static final int ESTADO_NORMAL = 0;
    public static final int ESTADO_ATENCAO = 1;
    public static final int ESTADO_LIMITE = 2;

    private static final Locale BR = new Locale("pt", "BR");
    private static final long DOUBLE_TAP_MS = 320L;
    private static final float DOUBLE_TAP_SLOP_DP = 36f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;
    private DamReading taio, ituporanga;

    private OnDamDoubleTapListener doubleTapListener;
    private long lastTapAt;
    private float lastTapX, lastTapY;
    private String lastTapDam;

    public interface OnDamDoubleTapListener {
        /** Dois toques seguidos sobre o card de uma barragem ({@link #KEY_TAIO} ou {@link #KEY_ITUPORANGA}). */
        void onDamDoubleTap(String damKey);
    }

    public DamSummaryView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        // Precisa ser clicável para receber o toque; a rolagem da tela continua funcionando porque
        // o ScrollView intercepta o arrasto assim que ele passa do limiar.
        setClickable(true);
    }

    public void setOnDamDoubleTapListener(OnDamDoubleTapListener listener) {
        this.doubleTapListener = listener;
    }

    public void setData(DamReading taio, DamReading ituporanga) {
        this.taio = taio;
        this.ituporanga = ituporanga;
        invalidate();
    }

    // ---------------------------------------------------------------- estados

    /**
     * Estado da borda de uma barragem:
     * 0 verde (normal), 1 âmbar (operando com comportas abertas ou reservatório acima de 80%),
     * 2 vermelho (reservatório no limite, 95% ou mais).
     */
    public static int estado(DamReading d) {
        if (d == null) return -1;
        double pct = d.capacityPercent;
        if (pct >= 95) return ESTADO_LIMITE;
        if (pct >= 80 || d.gatesOpen > 0) return ESTADO_ATENCAO;
        return ESTADO_NORMAL;
    }

    public static int corBorda(int estado) {
        switch (estado) {
            case ESTADO_LIMITE: return Cotas.COR_EMERGENCIA;
            case ESTADO_ATENCAO: return Cotas.COR_ATENCAO;
            case ESTADO_NORMAL: return Cotas.COR_NORMAL;
            default: return Color.rgb(150, 158, 168);
        }
    }

    public static String nomeEstado(int estado) {
        switch (estado) {
            case ESTADO_LIMITE: return "NO LIMITE";
            case ESTADO_ATENCAO: return "ATENCAO";
            case ESTADO_NORMAL: return "NORMAL";
            default: return "SEM DADOS";
        }
    }

    // ---------------------------------------------------------------- desenho

    private float dp(float v) { return v * density; }

    private float loop(long periodMs) {
        return (System.currentTimeMillis() % periodMs) / (float) periodMs;
    }

    private void text(Canvas c, String s, float x, float y, float size, int color, boolean bold) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setAlpha(255);
        paint.setColor(color);
        paint.setTextSize(dp(size));
        paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        c.drawText(s, x, y, paint);
    }

    private float measure(String s, float size, boolean bold) {
        paint.setTextSize(dp(size));
        paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return paint.measureText(s);
    }

    @Override protected void onDraw(Canvas c) {
        float pad = dp(2), gap = dp(10), w = getWidth();
        if (w <= 0 || getHeight() <= 0) return;
        float cardW = (w - gap - pad * 2) / 2f;
        try {
            drawDam(c, pad, cardW, taio, "Taió", 0f, KEY_TAIO);
            drawDam(c, pad + cardW + gap, cardW, ituporanga, "Ituporanga", 0.37f, KEY_ITUPORANGA);
        } catch (RuntimeException ignored) {
            // Um dado inesperado não pode fechar a tela.
        }
        if (isShown()) postInvalidateDelayed(40);
    }

    private void drawDam(Canvas c, float left, float width, DamReading d, String fallback,
                         float phaseShift, String key) {
        float h = getHeight();
        float inset = dp(1.2f);
        float radius = dp(12);
        RectF card = new RectF(left, inset, left + width, h - inset);
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        c.drawRoundRect(card, radius, radius, paint);

        int state = estado(d);
        int edge = corBorda(state);

        // Água ao fundo, na altura da ocupação do reservatório.
        if (d != null) {
            float pct = (float) Math.max(0.04, Math.min(1.0, d.capacityPercent / 100.0));
            float surface = h - h * pct * 0.94f;
            c.save();
            path.reset();
            path.addRoundRect(card, radius, radius, Path.Direction.CW);
            c.clipPath(path);
            float p = loop(5200) + phaseShift;
            wave(c, left, width, h, surface + dp(4), dp(4), p, Color.argb(34, 33, 150, 243));
            wave(c, left, width, h, surface, dp(3), 1.3f - p, Color.argb(30, 21, 101, 192));
            c.restore();
        }

        String name = d == null || d.name == null ? fallback
                : d.name.replace("Barragem Oeste ", "").replace("Barragem Sul ", "");
        text(c, name, left + dp(12), dp(25), 13, Color.rgb(28, 65, 94), true);

        // Estado, no canto superior direito, já na cor da borda.
        paint.setTextSize(dp(8));
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        String stateName = nomeEstado(state);
        float stateW = paint.measureText(stateName);
        if (left + dp(12) + measure(name, 13, true) + dp(10) + stateW < left + width - dp(10)) {
            text(c, stateName, left + width - dp(10) - stateW, dp(24), 8, edge, true);
        }

        if (d == null) {
            text(c, "Sem dados", left + dp(12), dp(53), 12, Color.GRAY, false);
            drawBorder(c, card, radius, edge);
            return;
        }

        text(c, String.format(BR, "%.2f m", d.levelMeters), left + dp(12), dp(54), 20, Color.rgb(31, 103, 151), true);

        String pctText = String.format(BR, "%.1f%% ocupado", d.capacityPercent);
        float pctW = measure(pctText, 10, false);
        text(c, pctText, left + dp(12), dp(75), 10, Color.rgb(75, 85, 99), false);

        // Chuva das últimas 24 h sobre a barragem, quando soubermos.
        if (!Double.isNaN(d.rain24hMm)) {
            String rain = String.format(BR, "%.1f mm", d.rain24hMm);
            float rainW = measure(rain, 9, true);
            float iconSize = dp(16);
            float rx = left + dp(12) + pctW + dp(8);
            if (rx + iconSize + dp(3) + rainW < left + width - dp(10)) {
                WeatherIcon.draw(c, paint, rx + iconSize / 2f, dp(71), iconSize, d.rain24hMm, System.currentTimeMillis());
                text(c, rain, rx + iconSize + dp(3), dp(75), 9,
                        d.rain24hMm >= 0.2 ? Color.rgb(21, 101, 192) : Color.rgb(150, 158, 168), true);
            }
        }

        paint.setColor(Color.rgb(224, 232, 241));
        paint.setStyle(Paint.Style.FILL);
        c.drawRoundRect(new RectF(left + dp(12), dp(83), left + width - dp(12), dp(91)), dp(4), dp(4), paint);
        float pctBar = (float) Math.max(0, Math.min(100, d.capacityPercent));
        int barColor = pctBar >= 90 ? Color.rgb(221, 107, 18) : Color.rgb(31, 145, 91);
        paint.setColor(barColor);
        float barEnd = left + dp(12) + (width - dp(24)) * pctBar / 100f;
        c.drawRoundRect(new RectF(left + dp(12), dp(83), barEnd, dp(91)), dp(4), dp(4), paint);
        // Brilho que percorre a barra.
        float shine = left + dp(12) + (barEnd - left - dp(12)) * loop(2600);
        paint.setColor(Color.argb(90, 255, 255, 255));
        c.drawRoundRect(new RectF(Math.max(left + dp(12), shine - dp(10)), dp(83), Math.min(barEnd, shine + dp(10)), dp(91)), dp(4), dp(4), paint);

        text(c, "Comportas  " + d.gatesOpen + "/" + d.gatesTotal + " abertas", left + dp(12), dp(112), 10, Color.rgb(44, 55, 65), true);
        drawGates(c, left + dp(12), dp(120), width - dp(24), d);

        // Aviso de que o card abre o histórico em dois toques.
        String hint = "2× toque: 24 h";
        float hintW = measure(hint, 8, false);
        text(c, hint, left + width - dp(10) - hintW, dp(152), 8, Color.rgb(150, 158, 168), false);

        drawBorder(c, card, radius, edge);
    }

    /** Borda fina, com cantos arredondados, na cor do estado da barragem. */
    private void drawBorder(Canvas c, RectF card, float radius, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setShader(null);
        paint.setAlpha(255);
        paint.setStrokeWidth(dp(1.6f));
        paint.setColor(color);
        c.drawRoundRect(card, radius, radius, paint);
    }

    private void wave(Canvas c, float left, float width, float h, float baseY, float amp, float phase, int color) {
        path.reset();
        path.moveTo(left, h);
        float len = width / 1.2f;
        for (float x = 0; x <= width + dp(6); x += dp(6)) {
            path.lineTo(left + x, baseY + (float) Math.sin((x / len + phase) * 2 * Math.PI) * amp);
        }
        path.lineTo(left + width, h);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setAlpha(255);
        paint.setColor(color);
        c.drawPath(path, paint);
    }

    private void drawGates(Canvas c, float left, float top, float width, DamReading d) {
        int total = Math.max(1, d.gatesTotal);
        float gap = dp(3), segment = (width - gap * (total - 1)) / total;
        float flow = loop(1100);
        for (int i = 0; i < total; i++) {
            boolean open = d.gateStates != null && i < d.gateStates.length ? d.gateStates[i] : i < d.gatesOpen;
            float x = left + i * (segment + gap);
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(255);
            paint.setColor(open ? Color.rgb(31, 160, 106) : Color.rgb(218, 91, 82));
            c.drawRoundRect(new RectF(x, top, x + segment, top + dp(22)), dp(5), dp(5), paint);
            if (open) {
                // Gotas descendo pela comporta aberta.
                for (int k = 0; k < 2; k++) {
                    float f = (flow + k * 0.5f + i * 0.17f) % 1f;
                    paint.setColor(Color.argb((int) (200 * (1f - Math.abs(f - 0.5f) * 2f)), 255, 255, 255));
                    c.drawCircle(x + segment - dp(5), top + dp(3) + dp(16) * f, dp(1.6f), paint);
                }
            }
            text(c, open ? "A" : "F", x + segment / 2 - dp(5), top + dp(15), 9, Color.WHITE, true);
        }
    }

    // ---------------------------------------------------------------- toque duplo

    /** Qual card está sob este x? */
    private String damAt(float x) {
        float pad = dp(2), gap = dp(10), w = getWidth();
        if (w <= 0) return null;
        float cardW = (w - gap - pad * 2) / 2f;
        if (x >= pad && x <= pad + cardW) return KEY_TAIO;
        float right = pad + cardW + gap;
        if (x >= right && x <= right + cardW) return KEY_ITUPORANGA;
        return null;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP) {
            float x = event.getX(), y = event.getY();
            long now = System.currentTimeMillis();
            String dam = damAt(x);
            float slop = DOUBLE_TAP_SLOP_DP * density;
            boolean samePlace = dam != null && dam.equals(lastTapDam)
                    && now - lastTapAt <= DOUBLE_TAP_MS
                    && Math.abs(x - lastTapX) <= slop && Math.abs(y - lastTapY) <= slop;
            if (samePlace) {
                lastTapAt = 0L;
                lastTapDam = null;
                if (doubleTapListener != null) doubleTapListener.onDamDoubleTap(dam);
            } else {
                lastTapAt = now;
                lastTapX = x;
                lastTapY = y;
                lastTapDam = dam;
            }
            performClick();
            return true;
        }
        return super.onTouchEvent(event);
    }
}
