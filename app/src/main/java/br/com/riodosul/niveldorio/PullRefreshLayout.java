package br.com.riodosul.niveldorio;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

/** Contêiner simples, sem dependência AndroidX, para atualizar ao puxar para baixo. */
public class PullRefreshLayout extends FrameLayout {
    public interface OnRefreshListener { void onRefresh(); }

    private final ProgressBar spinner;
    private final float density;
    private final int touchSlop;
    private OnRefreshListener listener;
    private float downY;
    private boolean dragging;
    private boolean refreshing;

    public PullRefreshLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setBackgroundColor(Color.TRANSPARENT);
        spinner = new ProgressBar(context);
        spinner.setIndeterminate(true);
        spinner.setVisibility(View.GONE);
        LayoutParams params = new LayoutParams((int) (36 * density), (int) (36 * density));
        params.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
        params.topMargin = (int) (8 * density);
        addView(spinner, params);
    }

    public void setOnRefreshListener(OnRefreshListener listener) { this.listener = listener; }

    public void setRefreshing(boolean value) {
        refreshing = value;
        spinner.setVisibility(value ? View.VISIBLE : View.GONE);
        spinner.bringToFront();
    }

    private boolean childCanScrollUp() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child != spinner && child.canScrollVertically(-1)) return true;
        }
        return false;
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (refreshing) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = event.getY();
                dragging = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                float distance = event.getY() - downY;
                if (distance > touchSlop && !childCanScrollUp()) {
                    dragging = true;
                    return true;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                dragging = false;
                break;
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && dragging) return true;
        if ((event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) && dragging) {
            float distance = event.getY() - downY;
            dragging = false;
            if (distance >= 72 * density && listener != null) {
                setRefreshing(true);
                listener.onRefresh();
            }
            return true;
        }
        return true;
    }
}
