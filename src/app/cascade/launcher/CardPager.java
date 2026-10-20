package app.cascade.launcher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * Horizontal card slider. Every child fills the pager; children are placed by translationX from a
 * continuous position, which lets the pager wrap around endlessly once it has three or more cards.
 */
final class CardPager extends FrameLayout {
    interface Listener {
        void onPageSelected(int index);
    }

    private final int touchSlop;
    private final int flingVelocity;
    private final float gap;
    private Listener listener;

    private float position;
    private float downX;
    private float downY;
    private float lastX;
    private boolean dragging;
    private VelocityTracker velocity;
    private ValueAnimator animator;

    CardPager(Context context) {
        super(context);
        ViewConfiguration vc = ViewConfiguration.get(context);
        touchSlop = vc.getScaledTouchSlop();
        flingVelocity = vc.getScaledMinimumFlingVelocity() * 4;
        gap = 16 * getResources().getDisplayMetrics().density;
        setClipChildren(false);
    }

    void setListener(Listener l) {
        listener = l;
    }

    int count() {
        return getChildCount();
    }

    private boolean loops() {
        return getChildCount() >= 3;
    }

    int currentIndex() {
        int n = getChildCount();
        if (n == 0) return 0;
        return ((Math.round(position) % n) + n) % n;
    }

    void setCurrent(int index) {
        if (animator != null) animator.cancel();
        position = Math.max(0, Math.min(Math.max(0, getChildCount() - 1), index));
        applyPositions();
        if (listener != null) listener.onPageSelected(currentIndex());
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        applyPositions();
    }

    @Override public void onViewAdded(View child) {
        super.onViewAdded(child);
        applyPositions();
    }

    private float stride() {
        return getWidth() + gap;
    }

    private void applyPositions() {
        int n = getChildCount();
        float stride = stride();
        for (int i = 0; i < n; i++) {
            float off = i - position;
            if (loops()) {
                off = ((off % n) + n) % n;
                if (off > n / 2f) off -= n;
            }
            View c = getChildAt(i);
            c.setTranslationX(off * stride);
            c.setVisibility(Math.abs(off) < 1.02f ? VISIBLE : INVISIBLE);
        }
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = ev.getX();
                downY = ev.getY();
                dragging = false;
                if (animator != null && animator.isRunning()) {
                    animator.cancel();
                    startDrag();
                    return true;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (shouldStartDrag(ev)) {
                    startDrag();
                    lastX = ev.getX();
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
        }
        return false;
    }

    private boolean shouldStartDrag(MotionEvent ev) {
        float dx = Math.abs(ev.getX() - downX);
        float dy = Math.abs(ev.getY() - downY);
        return getChildCount() > 1 && dx > touchSlop && dx > dy * 1.2f;
    }

    private void startDrag() {
        dragging = true;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        if (velocity == null) velocity = VelocityTracker.obtain();
        velocity.clear();
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        if (velocity != null) velocity.addMovement(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = ev.getX();
                downY = ev.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) {
                    if (!shouldStartDrag(ev)) return true;
                    startDrag();
                    velocity.addMovement(ev);
                }
                position -= (ev.getX() - lastX) / stride();
                lastX = ev.getX();
                if (!loops()) position = Math.max(-0.25f, Math.min(getChildCount() - 0.75f, position));
                applyPositions();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    float vx = 0;
                    if (velocity != null) {
                        velocity.computeCurrentVelocity(1000);
                        vx = velocity.getXVelocity();
                    }
                    float target;
                    if (vx < -flingVelocity) target = (float) Math.floor(position) + 1;
                    else if (vx > flingVelocity) target = (float) Math.ceil(position) - 1;
                    else target = Math.round(position);
                    if (!loops()) target = Math.max(0, Math.min(getChildCount() - 1, target));
                    animateTo(target);
                }
                dragging = false;
                return true;
        }
        return true;
    }

    private void animateTo(final float target) {
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(position, target);
        animator.setDuration(280);
        animator.setInterpolator(new DecelerateInterpolator(1.6f));
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                position = (Float) a.getAnimatedValue();
                applyPositions();
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override public void onAnimationCancel(Animator a) {
                cancelled = true;
            }

            @Override public void onAnimationEnd(Animator a) {
                if (cancelled) return;
                int n = getChildCount();
                position = n == 0 ? 0 : ((Math.round(target) % n) + n) % n;
                applyPositions();
                if (listener != null) listener.onPageSelected(currentIndex());
            }
        });
        animator.start();
    }
}
