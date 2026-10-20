package app.cascade.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.List;

/**
 * A–Z strip on the right edge. The view spans the full screen but only accepts touches that start
 * on the strip; once a drag starts it follows the finger anywhere, so the finger can slide left
 * onto the letter's app list. Letters near the finger bulge outward in a wave.
 */
public final class AlphabetScrollerView extends View {
    /** Section key for favourites; drawn as a star at the top of the strip. */
    static final String STAR = "\u2605";

    interface Listener {
        void onScrubStart();
        /** y is the centre of the selected letter, in this view's coordinates. */
        void onLetterSelected(String letter, float y);
        void onScrubMove(float x, float y, boolean inStrip);
        void onScrubEnd(float x, float y, boolean inStrip);
    }

    private static final float STRIP_DP = 34f;
    private static final float MAX_STEP_DP = 26f;
    private static final float WAVE_RADIUS_DP = 130f;
    private static final float WAVE_SHIFT_DP = 44f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path starPath = new Path();
    private final float density;
    private final float scaledDensity;
    private String[] letters = new String[0];
    private Listener listener;
    private int accentColor = 0xFF8AB4FF;
    private int textColor = 0xFFFFFFFF;

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
        paint.setShadowLayer(3 * density, 0, density, 0x80000000);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setColors(int text, int accent) {
        textColor = text;
        accentColor = accent;
        invalidate();
    }

    void setTypeface(Typeface typeface) {
        paint.setTypeface(typeface);
        invalidate();
    }

    void setLetters(List<String> list) {
        letters = list.toArray(new String[0]);
        selected = -1;
        invalidate();
    }

    private float stripLeft() {
        return getWidth() - getPaddingRight() - STRIP_DP * density * 1.3f;
    }

    /** True if a touch at this x (in this view's coordinates) belongs to the strip. */
    boolean isInStrip(float x) {
        return letters.length > 0 && x >= stripLeft();
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
        float baseSize = Math.min(11.5f * scaledDensity, step * 0.8f);
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
            paint.setTextSize(baseSize * (1 + 0.9f * w));
            paint.setColor(isSelected ? accentColor : textColor);
            paint.setAlpha(isSelected ? 255 : (int) (150 + 105 * w));
            if (STAR.equals(letters[i])) {
                drawStar(canvas, baseX - shift * w, y, paint.getTextSize() * 0.5f);
            } else {
                Paint.FontMetrics fm = paint.getFontMetrics();
                float baseline = y - (fm.ascent + fm.descent) / 2f;
                canvas.drawText(letters[i], baseX - shift * w, baseline, paint);
            }
        }
    }

    private void drawStar(Canvas canvas, float cx, float cy, float r) {
        starPath.reset();
        for (int k = 0; k < 10; k++) {
            double a = -Math.PI / 2 + k * Math.PI / 5;
            float rr = k % 2 == 0 ? r : r * 0.45f;
            float x = cx + (float) (Math.cos(a) * rr);
            float y = cy + (float) (Math.sin(a) * rr);
            if (k == 0) starPath.moveTo(x, y);
            else starPath.lineTo(x, y);
        }
        starPath.close();
        canvas.drawPath(starPath, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!isInStrip(x)) return false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                touching = true;
                selected = -1;
                animateTo(1f);
                if (listener != null) listener.onScrubStart();
                track(x, y);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (touching) track(x, y);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (touching) {
                    touching = false;
                    animateTo(0f);
                    if (listener != null) {
                        listener.onScrubEnd(x, y, e.getActionMasked() == MotionEvent.ACTION_UP && isInStrip(x));
                    }
                }
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void track(float x, float y) {
        boolean inStrip = isInStrip(x);
        if (inStrip) {
            // Letters only change while on the strip; off it the letter is locked so the
            // finger can travel to the app list.
            touchY = y;
            computeGeometry();
            int idx = (int) Math.floor((y - (firstY - step / 2f)) / step);
            idx = Math.max(0, Math.min(letters.length - 1, idx));
            if (idx != selected) {
                selected = idx;
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                if (listener != null) listener.onLetterSelected(letters[idx], firstY + idx * step);
            }
            invalidate();
        }
        if (listener != null) listener.onScrubMove(x, y, inStrip);
    }

    private void animateTo(float target) {
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(200);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                progress = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        animator.start();
    }
}
