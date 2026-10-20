package app.chinook.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The swipeable top area: a clock card, widget cards and app-shortcut cards, in any order. */
final class TopCards {
    interface Host {
        AppInfo findApp(String key);
        List<AppInfo> visibleApps();
        void launch(AppInfo app, View source);
        void onAppLongPress(AppInfo app, View anchor);
        /** The clock + date + world clocks view, reused as the clock card's content. */
        View clockContent();
    }

    static final String CLOCK = "clock";
    private static final String WIDGET = "widget:";
    private static final String APPS = "apps:";
    private static final int MAX_APPS = 8;
    private static final int MIN_HEIGHT_DP = 140;
    private static final int MAX_HEIGHT_DP = 520;

    private final Activity activity;
    private final Prefs prefs;
    private final Theme theme;
    private final M3 m;
    private final Host host;
    private final WidgetBinder binder;
    private final CardPager pager;
    private final LinearLayout dots;
    private final float density;
    private final List<String> cards;
    /** Badges on app-card icons, refreshed in place when notifications change. */
    private final List<TextView> badges = new ArrayList<>();

    TopCards(Activity activity, Prefs prefs, M3 m, Host host, WidgetBinder binder,
             CardPager pager, LinearLayout dots) {
        this.activity = activity;
        this.prefs = prefs;
        this.m = m;
        this.theme = m.theme;
        this.host = host;
        this.binder = binder;
        this.pager = pager;
        this.dots = dots;
        this.density = activity.getResources().getDisplayMetrics().density;

        if (prefs.hasCards()) {
            cards = prefs.cards();
        } else {
            // First run of the card slider: start with the clock, then any v2 home widgets.
            cards = new ArrayList<>();
            cards.add(CLOCK);
            for (String w : prefs.widgets()) cards.add(WIDGET + w.split(":")[0]);
            prefs.setWidgets(new ArrayList<String>());
            save();
        }
        pager.setListener(new CardPager.Listener() {
            @Override public void onPageSelected(int index) {
                updateDots();
            }
        });
    }

    boolean isWidgetCardShowing() {
        int i = pager.currentIndex();
        return i < cards.size() && cards.get(i).startsWith(WIDGET);
    }

    // ------------------------------------------------------------ render

    void render() {
        int current = pager.currentIndex();
        pager.removeAllViews();
        badges.clear();
        View clock = host.clockContent();
        if (clock.getParent() != null) ((ViewGroup) clock.getParent()).removeView(clock);

        boolean dropped = false;
        for (int i = 0; i < cards.size(); i++) {
            String c = cards.get(i);
            View card;
            if (CLOCK.equals(c)) card = clockCard(clock);
            else if (c.startsWith(WIDGET)) card = widgetCard(parseId(c));
            else if (c.startsWith(APPS)) card = appsCard(i, appKeys(c));
            else card = null;
            if (card == null) {
                if (c.startsWith(WIDGET)) {
                    cards.remove(i--);
                    dropped = true;
                }
                continue;
            }
            final int index = i;
            card.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    showCardMenu(index, v, null);
                    return true;
                }
            });
            pager.addView(card, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (dropped) save();

        ViewGroup.LayoutParams lp = pager.getLayoutParams();
        lp.height = dp(prefs.cardHeightDp());
        pager.setLayoutParams(lp);
        pager.setCurrent(Math.min(current, Math.max(0, pager.count() - 1)));
        updateDots();
    }

    private void updateDots() {
        dots.removeAllViews();
        int n = pager.count();
        dots.setVisibility(n > 1 ? View.VISIBLE : View.GONE);
        if (n <= 1) return;
        int current = pager.currentIndex();
        if (n > 12) {
            TextView t = new TextView(activity);
            t.setText((current + 1) + " / " + n);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            t.setTypeface(Fonts.medium);
            t.setTextColor(theme.textSecondary);
            dots.addView(t);
            return;
        }
        for (int i = 0; i < n; i++) {
            View d = new View(activity);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(theme.text);
            d.setBackground(g);
            d.setAlpha(i == current ? 0.95f : 0.3f);
            int size = dp(i == current ? 7 : 5);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.leftMargin = lp.rightMargin = dp(3);
            lp.gravity = Gravity.CENTER_VERTICAL;
            dots.addView(d, lp);
        }
    }

    private View clockCard(View clock) {
        FitFrame card = new FitFrame(activity);
        card.addView(clock, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        return card;
    }

    /**
     * Holds the clock card's content at its natural height: centred vertically when it fits, scaled
     * down when it's taller than the card (large clock sizes, short cards).
     */
    private static final class FitFrame extends FrameLayout {
        FitFrame(android.content.Context context) {
            super(context);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, heightSpec);
            View c = getChildAt(0);
            if (c == null) return;
            c.measure(MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            View c = getChildAt(0);
            if (c == null) return;
            int avail = b - t;
            float scale = c.getMeasuredHeight() > avail && avail > 0 ? avail / (float) c.getMeasuredHeight() : 1f;
            int top = scale < 1f ? 0 : (avail - c.getMeasuredHeight()) / 2;
            c.layout(0, top, c.getMeasuredWidth(), top + c.getMeasuredHeight());
            c.setPivotX(0);
            c.setPivotY(0);
            c.setScaleX(scale);
            c.setScaleY(scale);
        }
    }

    private FrameLayout roundedCard() {
        FrameLayout card = new FrameLayout(activity);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(theme.cardBackground);
        bg.setCornerRadius(dp(28));
        card.setBackground(bg);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(28));
            }
        });
        card.setClipToOutline(true);
        return card;
    }

    private View widgetCard(final int appWidgetId) {
        final WidgetHostView view = binder.createView(appWidgetId);
        if (view == null) return null;
        // Most widgets draw their own background; a second one behind it looks muddy, so the card
        // only clips the widget to the rounded shape.
        final FrameLayout card = roundedCard();
        card.setBackground(null);
        if (theme.grayscale) card.setLayerType(View.LAYER_TYPE_HARDWARE, Theme.grayscaleLayerPaint());
        card.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        view.setOnLongPressListener(new WidgetHostView.OnLongPressListener() {
            @Override public void onWidgetLongPress(WidgetHostView v) {
                card.performLongClick();
            }
        });
        card.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                 int ol, int ot, int or, int ob) {
                if (r - l == or - ol && b - t == ob - ot) return;
                int w = Math.round((r - l) / density), h = Math.round((b - t) / density);
                try {
                    // Exact size lets responsive widgets (Android 12+) pick the layout that fills the card.
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        view.updateAppWidgetSize(new android.os.Bundle(),
                                Collections.singletonList(new android.util.SizeF(w, h)));
                    } else {
                        view.updateAppWidgetSize(null, w, h, w, h);
                    }
                } catch (RuntimeException ignored) {
                }
            }
        });
        return card;
    }

    private View appsCard(int index, List<String> keys) {
        List<AppInfo> apps = new ArrayList<>();
        for (String k : keys) {
            AppInfo a = host.findApp(k);
            if (a != null) apps.add(a);
        }
        FrameLayout card = roundedCard();
        if (keys.isEmpty()) {
            TextView t = m.text("Long-press to choose apps", M3.BODY_LARGE, theme.textSecondary);
            t.setGravity(Gravity.CENTER);
            card.addView(t, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return card;
        }
        if (apps.isEmpty()) return card;

        // Pick a grid that suits the count, then size icons to the space each cell gets.
        int n = apps.size();
        int columns = n <= 4 ? n : n <= 6 ? 3 : 4;
        int rows = (n + columns - 1) / columns;
        int pad = 12;
        float cardW = activity.getResources().getDisplayMetrics().widthPixels / density - 74 - pad * 2;
        float cardH = prefs.cardHeightDp() - pad * 2;
        float cellW = cardW / columns;
        float cellH = cardH / rows;
        boolean showLabels = cellH >= 76;
        float labelSpace = showLabels ? 26 : 0;
        int iconDp = Math.round(Math.min(cellW * 0.62f, (cellH - labelSpace - 16) * 0.9f));
        iconDp = Math.max(32, Math.min(76, iconDp));

        LinearLayout grid = new LinearLayout(activity);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(pad), dp(pad), dp(pad), dp(pad));
        for (int row = 0; row < rows; row++) {
            LinearLayout line = new LinearLayout(activity);
            line.setOrientation(LinearLayout.HORIZONTAL);
            // Every cell gets 1/columns of the width; a short last row is centred, not left-aligned.
            line.setWeightSum(columns);
            line.setGravity(Gravity.CENTER);
            for (int c = 0; c < columns; c++) {
                int i = row * columns + c;
                if (i >= n) break;
                line.addView(appCell(index, apps.get(i), iconDp, showLabels),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            }
            grid.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        card.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return card;
    }

    private View appCell(final int cardIndex, final AppInfo app, int iconDp, boolean showLabel) {
        LinearLayout cell = new LinearLayout(activity);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(4), dp(4), dp(4), dp(4));
        cell.setBackground(m.ripple(theme.text, null, 20));

        // Icon with a notification badge on its top-right corner.
        FrameLayout iconBox = new FrameLayout(activity);
        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(app.icon);
        if (theme.grayscale) icon.setColorFilter(Theme.grayscaleFilter());
        iconBox.addView(icon, new FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER));
        TextView badge = m.badge();
        badge.setTag(app.packageName());
        M3.setBadgeCount(badge, NotificationDotService.count(app.packageName()));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(16), Gravity.TOP | Gravity.END);
        iconBox.addView(badge, blp);
        badges.add(badge);
        cell.addView(iconBox, new LinearLayout.LayoutParams(dp(iconDp + 10), dp(iconDp + 6)));

        if (showLabel) {
            TextView label = m.text(app.label, M3.LABEL_MEDIUM, theme.text);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, iconDp >= 56 ? 13 : 12);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            cell.addView(label, lp);
        }
        cell.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.launch(app, v);
            }
        });
        // The apps fill the whole card, so their long-press menu also carries the card's options.
        cell.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                showCardMenu(cardIndex, v, app);
                return true;
            }
        });
        return cell;
    }

    /** Refreshes app-card badges after notifications change, without rebuilding the cards. */
    void updateBadges() {
        for (TextView b : badges) M3.setBadgeCount(b, NotificationDotService.count((String) b.getTag()));
    }

    // ------------------------------------------------------------- edit

    /** Card options; when opened from an app on an app card, that app's options come first. */
    private void showCardMenu(final int index, final View anchor, final AppInfo app) {
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        final String card = cards.get(index);
        PopupMenu menu = new PopupMenu(activity, anchor, Gravity.CENTER);
        if (app != null) {
            menu.getMenu().add(0, 7, 0, app.label + " options");
            menu.getMenu().add(0, 8, 0, "Remove " + app.label + " from card");
        }
        if (card.startsWith(APPS)) menu.getMenu().add(0, 1, 0, "Choose apps");
        if (cards.size() > 1) {
            menu.getMenu().add(0, 2, 1, "Move left");
            menu.getMenu().add(0, 3, 2, "Move right");
        }
        menu.getMenu().add(0, 4, 3, "Add card");
        menu.getMenu().add(0, 5, 4, "Card height");
        menu.getMenu().add(0, 6, 5, CLOCK.equals(card) ? "Remove clock card" : "Remove card");
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                int n = cards.size();
                switch (item.getItemId()) {
                    case 1:
                        chooseApps(index);
                        break;
                    case 2: {
                        int to = (index - 1 + n) % n;
                        Collections.swap(cards, index, to);
                        save();
                        render();
                        pager.setCurrent(to);
                        break;
                    }
                    case 3: {
                        int to = (index + 1) % n;
                        Collections.swap(cards, index, to);
                        save();
                        render();
                        pager.setCurrent(to);
                        break;
                    }
                    case 4:
                        showAddCard();
                        break;
                    case 5:
                        showHeight(null);
                        break;
                    case 6:
                        if (card.startsWith(WIDGET)) binder.delete(parseId(card));
                        cards.remove(index);
                        save();
                        render();
                        break;
                    case 7:
                        host.onAppLongPress(app, anchor);
                        break;
                    case 8: {
                        List<String> keys = appKeys(card);
                        keys.remove(app.key);
                        cards.set(index, APPS + TextUtils.join("|", keys));
                        save();
                        render();
                        pager.setCurrent(index);
                        break;
                    }
                }
                return true;
            }
        });
        menu.show();
    }

    void showAddCard() {
        final Surfaces.Sheet sheet = new Surfaces.Sheet(activity, m, "Add a top card");
        sheet.item(R.drawable.ic_widgets, "Widget", "Weather, calendar, wallet, music and more", new Runnable() {
            @Override public void run() {
                binder.pick(new WidgetBinder.Callback() {
                    @Override public void onWidgetReady(int appWidgetId) {
                        insertCard(WIDGET + appWidgetId);
                    }
                });
            }
        });
        sheet.item(R.drawable.ic_apps, "App shortcuts", "Up to " + MAX_APPS + " apps on one card", new Runnable() {
            @Override public void run() {
                insertCard(APPS);
                chooseApps(cards.indexOf(APPS));
            }
        });
        if (!cards.contains(CLOCK)) {
            sheet.item(R.drawable.ic_schedule, "Clock", "Time, date, next alarm and world clocks", new Runnable() {
                @Override public void run() {
                    insertCard(CLOCK);
                }
            });
        }
        sheet.show();
    }

    private void insertCard(String card) {
        int at = cards.isEmpty() ? 0 : Math.min(cards.size(), pager.currentIndex() + 1);
        cards.add(at, card);
        save();
        render();
        pager.setCurrent(at);
    }

    private void chooseApps(final int index) {
        if (index < 0) return;
        final List<AppInfo> apps = new ArrayList<>(host.visibleApps());
        List<SearchablePicker.Item> items = new ArrayList<>();
        java.util.Map<String, Integer> indexByKey = new java.util.HashMap<>();
        for (int i = 0; i < apps.size(); i++) {
            items.add(new SearchablePicker.Item(apps.get(i).label, null, apps.get(i).icon));
            indexByKey.put(apps.get(i).key, i);
        }
        List<Integer> initial = new ArrayList<>();
        for (String k : appKeys(cards.get(index))) {
            Integer i = indexByKey.get(k);
            if (i != null) initial.add(i);
        }
        SearchablePicker.multi(activity, m, "Apps on this card", items, initial, MAX_APPS, "Search apps", "All apps",
                new SearchablePicker.OnSave() {
                    @Override public void onSave(List<Integer> selectedInOrder) {
                        List<String> keys = new ArrayList<>();
                        for (int i : selectedInOrder) keys.add(apps.get(i).key);
                        cards.set(index, APPS + TextUtils.join("|", keys));
                        save();
                        render();
                        pager.setCurrent(index);
                    }
                });
    }

    void showHeight(final Runnable after) {
        final int original = prefs.cardHeightDp();
        final int[] chosen = {original};
        // A light scrim keeps the cards visible while resizing.
        final Surfaces.Sheet sheet = new Surfaces.Sheet(activity, m, "Top card height", 0.08f);
        final TextView value = m.text(original + " dp", M3.TITLE_MEDIUM, m.onSurface);
        value.setPadding(m.dp(24), 0, m.dp(24), m.dp(4));
        sheet.add(value);
        TextView hint = m.text("Drag to resize every card in the top slider.", M3.BODY_MEDIUM, m.onSurfaceVariant);
        hint.setPadding(m.dp(24), 0, m.dp(24), m.dp(8));
        sheet.add(hint);
        SeekBar seek = new SeekBar(activity);
        m.tint(seek);
        seek.setMax(MAX_HEIGHT_DP - MIN_HEIGHT_DP);
        seek.setProgress(original - MIN_HEIGHT_DP);
        seek.setPadding(m.dp(24), m.dp(12), m.dp(24), m.dp(12));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                chosen[0] = MIN_HEIGHT_DP + progress;
                value.setText(chosen[0] + " dp");
                setPagerHeight(chosen[0]);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        sheet.add(seek);
        final boolean[] saved = {false};
        sheet.action(m.textButton("Cancel", new View.OnClickListener() {
            @Override public void onClick(View v) {
                sheet.dismiss();
            }
        }));
        sheet.action(m.filledButton("Done", 0, new View.OnClickListener() {
            @Override public void onClick(View v) {
                saved[0] = true;
                prefs.setCardHeightDp(chosen[0]);
                sheet.dismiss();
            }
        }));
        sheet.onDismiss(new Runnable() {
            @Override public void run() {
                if (!saved[0]) setPagerHeight(original);
                else render(); // app cards re-fit their grid to the new height
                if (after != null) after.run();
            }
        });
        sheet.show();
    }

    private void setPagerHeight(int heightDp) {
        ViewGroup.LayoutParams lp = pager.getLayoutParams();
        lp.height = dp(heightDp);
        pager.setLayoutParams(lp);
    }

    // ----------------------------------------------------------- helpers

    private void save() {
        prefs.setCards(cards);
    }

    private static int parseId(String card) {
        try {
            return Integer.parseInt(card.substring(WIDGET.length()));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static List<String> appKeys(String card) {
        String s = card.substring(APPS.length());
        if (s.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(s.split("\\|")));
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
