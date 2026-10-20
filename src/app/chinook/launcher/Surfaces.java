package app.chinook.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Full-window dialog hosts for Material 3 bottom sheets and full-screen pages. */
final class Surfaces {
    static final PathInterpolator EMPHASIZED_DECELERATE = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    static final PathInterpolator EMPHASIZED_ACCELERATE = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);

    private Surfaces() {}

    /** Makes a dialog window cover the whole screen, draw under the system bars and resize for the keyboard. */
    static void setupWindow(Window w) {
        if (w == null) return;
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.setWindowAnimations(0);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        if (android.os.Build.VERSION.SDK_INT >= 29) w.setNavigationBarContrastEnforced(false);
    }

    // ------------------------------------------------------------ sheet

    /**
     * Modal bottom sheet: 28dp top corners, drag handle, optional headline, scrollable content and an
     * optional action row. Drag down or tap the scrim to dismiss.
     */
    static final class Sheet {
        final LinearLayout content;
        private final M3 m;
        private final Dialog dialog;
        private final FrameLayout root;
        private final LinearLayout sheet;
        private final LinearLayout actions;
        private boolean dismissing;
        private Runnable onDismiss;

        Sheet(Activity activity, M3 m, String headline) {
            this(activity, m, headline, 0.32f);
        }

        Sheet(Activity activity, final M3 m, String headline, float scrim) {
            this.m = m;
            dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar) {
                @Override public void onBackPressed() {
                    dismiss();
                }
            };
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            setupWindow(dialog.getWindow());

            root = new FrameLayout(activity);
            root.setBackgroundColor(M3.withAlpha(Color.BLACK, scrim));
            root.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dismiss();
                }
            });

            sheet = new LinearLayout(activity);
            sheet.setOrientation(LinearLayout.VERTICAL);
            sheet.setClickable(true);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(m.surfaceContainerLow);
            float r = m.dp(28);
            bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            sheet.setBackground(bg);

            // Drag handle: 32x4dp, inside a 48dp tall touch area.
            FrameLayout handleArea = new FrameLayout(activity);
            View handle = new View(activity);
            handle.setBackground(m.shape(M3.withAlpha(m.onSurfaceVariant, 0.4f), 2));
            handleArea.addView(handle, new FrameLayout.LayoutParams(m.dp(32), m.dp(4), Gravity.CENTER));
            handleArea.setContentDescription("Drag handle");
            sheet.addView(handleArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, m.dp(36)));
            attachDrag(handleArea);

            if (headline != null) {
                TextView h = m.text(headline, M3.HEADLINE_SMALL, m.onSurface);
                h.setPadding(m.dp(24), m.dp(4), m.dp(24), m.dp(12));
                sheet.addView(h);
                attachDrag(h);
            }

            ScrollView scroll = new ScrollView(activity);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            scroll.setVerticalScrollBarEnabled(false);
            content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(content);
            // WRAP_CONTENT + weight: sized to its content, and shrinks (then scrolls) only if the sheet
            // would otherwise grow past the top margin. A 0-height weighted child would measure to 0 here.
            sheet.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            actions = new LinearLayout(activity);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            actions.setPadding(m.dp(24), m.dp(16), m.dp(24), m.dp(8));
            actions.setVisibility(View.GONE);
            sheet.addView(actions);

            int maxWidth = m.dp(640);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            int screenW = activity.getResources().getDisplayMetrics().widthPixels;
            if (screenW > maxWidth) lp.width = maxWidth;
            root.addView(sheet, lp);

            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    int top = insets.getSystemWindowInsetTop();
                    sheet.setPadding(insets.getSystemWindowInsetLeft(), 0,
                            insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom() + m.dp(8));
                    FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) sheet.getLayoutParams();
                    p.topMargin = top + m.dp(72);
                    sheet.setLayoutParams(p);
                    return insets.consumeSystemWindowInsets();
                }
            });
            dialog.setContentView(root, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        private void attachDrag(View area) {
            area.setOnTouchListener(new View.OnTouchListener() {
                private float startY;
                private VelocityTracker vt;

                @Override public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            startY = e.getRawY();
                            vt = VelocityTracker.obtain();
                            vt.addMovement(e);
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            if (vt != null) vt.addMovement(e);
                            sheet.setTranslationY(Math.max(0, e.getRawY() - startY));
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            float vy = 0;
                            if (vt != null) {
                                vt.addMovement(e);
                                vt.computeCurrentVelocity(1000);
                                vy = vt.getYVelocity();
                                vt.recycle();
                                vt = null;
                            }
                            float dy = sheet.getTranslationY();
                            if (dy < m.dp(8) && e.getActionMasked() == MotionEvent.ACTION_UP) {
                                sheet.animate().translationY(0).setDuration(150).start();
                                return true;
                            }
                            if (dy > sheet.getHeight() * 0.3f || vy > m.dp(800)) dismiss();
                            else sheet.animate().translationY(0).setDuration(250)
                                    .setInterpolator(EMPHASIZED_DECELERATE).start();
                            return true;
                    }
                    return false;
                }
            });
        }

        Sheet add(View v) {
            content.addView(v);
            return this;
        }

        Sheet item(int iconRes, String headline, String supporting, final Runnable action) {
            content.addView(m.listItem(iconRes, null, headline, supporting, null, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dismissThen(action);
                }
            }));
            return this;
        }

        Sheet action(View button) {
            actions.setVisibility(View.VISIBLE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = m.dp(8);
            actions.addView(button, lp);
            return this;
        }

        Sheet onDismiss(Runnable r) {
            onDismiss = r;
            return this;
        }

        void show() {
            dialog.show();
            root.setAlpha(0f);
            root.animate().alpha(1f).setDuration(200).start();
            sheet.setTranslationY(m.dp(600));
            sheet.post(new Runnable() {
                @Override public void run() {
                    sheet.setTranslationY(sheet.getHeight());
                    sheet.animate().translationY(0).setDuration(350).setInterpolator(EMPHASIZED_DECELERATE).start();
                }
            });
        }

        void dismiss() {
            dismissThen(null);
        }

        /** Animates the sheet away, then runs the action (so a follow-up screen opens cleanly). */
        void dismissThen(final Runnable after) {
            if (dismissing) return;
            dismissing = true;
            root.animate().alpha(0f).setDuration(200).start();
            sheet.animate().translationY(sheet.getHeight()).setDuration(200)
                    .setInterpolator(EMPHASIZED_ACCELERATE).withEndAction(new Runnable() {
                        @Override public void run() {
                            dialog.dismiss();
                            if (onDismiss != null) onDismiss.run();
                            if (after != null) after.run();
                        }
                    }).start();
        }
    }

    // ------------------------------------------------------------- page

    /**
     * Full-screen page in the style of Android Settings: top app bar with a back button, headline
     * and optional trailing action; slides in along the X axis.
     */
    static final class Page {
        final LinearLayout body;
        private final M3 m;
        private final Dialog dialog;
        private final LinearLayout root;
        private final LinearLayout appBar;
        private final TextView title;
        private boolean closing;
        private Runnable onClose;

        Page(Activity activity, final M3 m, String headline) {
            this.m = m;
            dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar) {
                @Override public void onBackPressed() {
                    close();
                }
            };
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            setupWindow(dialog.getWindow());

            root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(m.surface);

            appBar = new LinearLayout(activity);
            appBar.setOrientation(LinearLayout.HORIZONTAL);
            appBar.setGravity(Gravity.CENTER_VERTICAL);
            appBar.setPadding(m.dp(4), 0, m.dp(12), 0);
            appBar.addView(m.iconButton(R.drawable.ic_arrow_back, m.onSurface, "Back", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    close();
                }
            }));
            title = m.text(headline, M3.TITLE_LARGE, m.onSurface);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tlp.leftMargin = m.dp(4);
            appBar.addView(title, tlp);
            root.addView(appBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, m.dp(64)));

            body = new LinearLayout(activity);
            body.setOrientation(LinearLayout.VERTICAL);
            root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    root.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                            insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                    return insets.consumeSystemWindowInsets();
                }
            });
            dialog.setContentView(root, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        void setTitle(String s) {
            title.setText(s);
        }

        Page action(View v) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = m.dp(8);
            appBar.addView(v, lp);
            return this;
        }

        /** Body that scrolls as one column; returns the column to add rows to. */
        LinearLayout scrollingBody() {
            ScrollView scroll = new ScrollView(root.getContext());
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            scroll.setClipToPadding(false);
            LinearLayout column = new LinearLayout(root.getContext());
            column.setOrientation(LinearLayout.VERTICAL);
            column.setPadding(0, 0, 0, m.dp(24));
            scroll.addView(column);
            body.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            return column;
        }

        Page onClose(Runnable r) {
            onClose = r;
            return this;
        }

        void show() {
            dialog.show();
            root.setAlpha(0f);
            root.setTranslationX(m.dp(30));
            root.animate().alpha(1f).translationX(0).setDuration(300).setInterpolator(EMPHASIZED_DECELERATE).start();
        }

        void close() {
            if (closing) return;
            closing = true;
            root.animate().alpha(0f).translationX(m.dp(30)).setDuration(200)
                    .setInterpolator(EMPHASIZED_ACCELERATE).withEndAction(new Runnable() {
                        @Override public void run() {
                            dialog.dismiss();
                            if (onClose != null) onClose.run();
                        }
                    }).start();
        }
    }
}
