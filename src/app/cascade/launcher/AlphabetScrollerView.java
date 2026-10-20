package app.cascade.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;

/**
 * Vertical A–Z strip on the right edge. While dragging, letters near the finger
 * bulge outwards in a wave and the selected letter is reported to the listener.
 */
public final class AlphabetScrollerView extends View {
    interface Listener {
        void onScrubStart();
        void onLetterSelected(String letter);
        void onScrubEnd();
    }

    private static final float STRIP_DP = 36f;
    private static final float MAX_STEP_DP = 26f;
    private static final float WAVE_RADIUS_DP = 120f;
    private static final float WAVE_SHIFT_DP = 56f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final float scaledDensity;
    private String[] letters = new String[0];
    private Listener listener;
    private int accentColor = 0xFF8AB4FF;

    private boolean touching;
    private float touchY;
    private float progress;
    private int selected = -1;
    private ValueAnimator animator;

    private float step;
    private float firstY;

    public AlphabetScrollerView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setShadowLayer(3 * density, 0, density, 0x80000000);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setAccentColor(int color) {
        accentColor = color;
        invalidate();
    }

    void setLetters(List<String> list) {
        letters = list.toArray(new String[0]);
        selected = -1;
        invalidate();
    }

    /** True if a touch at this x (in this view's coordinates) belongs to the strip. */
    boolean isInStrip(float x) {
        return letters.length > 0 && x >= getWidth() - getPaddingRight() - STRIP_DP * density * 1.25f;
    }

    private void computeGeometry() {
        float top = getPaddingTop();
        float avail = Math.max(0, getHeight() - getPaddingBottom() - top);
        int n = Math.max(1, letters.length);
        step = Math.min(avail / n, MAX_STEP_DP * density);
        firstY = top + (avail - step * n) / 2f + step / 2f;
    }

    @Override protected void onDraw(Canvas canvas) {
        if (letters.length == 0) return;
        computeGeometry();
        float baseX = getWidth() - getPaddingRight() - STRIP_DP * density / 2f;
        float baseSize = Math.min(12 * scaledDensity, step * 0.85f);
        float radius = WAVE_RADIUS_DP * density;
        float shift = WAVE_SHIFT_DP * density;
        for (int i = 0; i < letters.length; i++) {
            float y = firstY + i * step;
            float w = 0;
            if (progress > 0) {
                float d = Math.abs(y - touchY);
                if (d < radius) w = (float) ((Math.cos(Math.PI * d / radius) + 1) / 2) * progress;
            }
            boolean isSelected = touching && i == selected;
            paint.setTextSize(baseSize * (1 + 1.2f * w));
            paint.setColor(isSelected ? accentColor : Color.WHITE);
            paint.setAlpha(isSelected ? 255 : (int) (175 + 80 * w));
            Paint.FontMetrics fm = paint.getFontMetrics();
            float baseline = y - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(letters[i], baseX - shift * w, baseline, paint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!isInStrip(e.getX())) return false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                touching = true;
                selected = -1;
                animateTo(1f);
                if (listener != null) listener.onScrubStart();
                track(e.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (touching) track(e.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (touching) {
                    touching = false;
                    animateTo(0f);
                    if (listener != null) listener.onScrubEnd();
                }
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void track(float y) {
        touchY = y;
        computeGeometry();
        int idx = (int) Math.floor((y - (firstY - step / 2f)) / step);
        idx = Math.max(0, Math.min(letters.length - 1, idx));
        if (idx != selected) {
            selected = idx;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            if (listener != null) listener.onLetterSelected(letters[idx]);
        }
        invalidate();
    }

    private void animateTo(float target) {
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(170);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                progress = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        animator.start();
    }
}
