package app.chinook.launcher;

import android.appwidget.AppWidgetHostView;
import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

/** Widget view that reports a long-press anywhere on it, even over clickable widget content. */
final class WidgetHostView extends AppWidgetHostView {
    interface OnLongPressListener {
        void onWidgetLongPress(WidgetHostView view);
    }

    private OnLongPressListener listener;
    private boolean longPressed;
    private float downX;
    private float downY;
    private final int slop;

    private final Runnable longPressCheck = new Runnable() {
        @Override public void run() {
            if (getParent() == null || listener == null) return;
            longPressed = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            MotionEvent cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0, 0, 0);
            WidgetHostView.super.dispatchTouchEvent(cancel);
            cancel.recycle();
            listener.onWidgetLongPress(WidgetHostView.this);
        }
    };

    WidgetHostView(Context context) {
        super(context);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    void setOnLongPressListener(OnLongPressListener l) {
        listener = l;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                longPressed = false;
                downX = ev.getX();
                downY = ev.getY();
                postDelayed(longPressCheck, ViewConfiguration.getLongPressTimeout());
                break;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(ev.getX() - downX) > slop || Math.abs(ev.getY() - downY) > slop) {
                    removeCallbacks(longPressCheck);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPressCheck);
                break;
        }
        if (longPressed) {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) longPressed = false;
            return true;
        }
        boolean handled = super.dispatchTouchEvent(ev);
        // Keep receiving the gesture so a long-press on empty widget space still registers.
        return handled || ev.getActionMasked() == MotionEvent.ACTION_DOWN;
    }

    @Override public void cancelLongPress() {
        super.cancelLongPress();
        removeCallbacks(longPressCheck);
    }
}
