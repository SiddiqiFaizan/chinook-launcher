package app.chinook.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.UserHandle;
import android.os.UserManager;
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
    private static final String SHORTCUT = "s:";
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
    private final LauncherApps launcherApps;
    private final UserManager userManager;
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
        this.launcherApps = (LauncherApps) activity.getSystemService(android.content.Context.LAUNCHER_APPS_SERVICE);
        this.userManager = (UserManager) activity.getSystemService(android.content.Context.USER_SERVICE);

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

    // ------------------------------------------------------- app cards

    /** One tile on an app card: an app, or another app's shortcut (e.g. a Gmail account). */
    private static final class Cell {
        String key;
        String label;
        Drawable icon;
        AppInfo app;
        ShortcutInfo shortcut;
    }

    private Cell resolveCell(String key) {
        Cell c = new Cell();
        c.key = key;
        if (key.startsWith(SHORTCUT)) {
            ShortcutInfo si = findShortcut(key);
            if (si == null) return null;
            c.shortcut = si;
            c.label = shortcutLabel(si);
            c.icon = shortcutIcon(si);
        } else {
            AppInfo a = host.findApp(key);
            if (a == null) return null;
            c.app = a;
            c.label = a.label;
            c.icon = a.icon;
        }
        return c;
    }

    private View appsCard(int index, List<String> keys) {
        List<Cell> cells = new ArrayList<>();
        for (String k : keys) {
            Cell c = resolveCell(k);
            if (c != null) cells.add(c);
        }
        FrameLayout card = roundedCard();
        if (keys.isEmpty()) {
            TextView t = m.text("Long-press to choose apps", M3.BODY_LARGE, theme.textSecondary);
            t.setGravity(Gravity.CENTER);
            card.addView(t, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return card;
        }
        if (cells.isEmpty()) {
            if (host.visibleApps().isEmpty()) return card; // apps still loading
            TextView t = m.text(launcherApps.hasShortcutHostPermission() ? "Long-press to choose apps"
                    : "Make Chinook your home app to show shortcuts", M3.BODY_MEDIUM, theme.textSecondary);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(16), 0, dp(16), 0);
            card.addView(t, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return card;
        }

        // Pick a grid that suits the count, then size icons to the space each cell gets.
        int n = cells.size();
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
                line.addView(cellView(index, cells.get(i), iconDp, showLabels),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            }
            grid.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        card.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return card;
    }

    private View cellView(final int cardIndex, final Cell cell, int iconDp, boolean showLabel) {
        LinearLayout v = new LinearLayout(activity);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(4), dp(4), dp(4), dp(4));
        v.setBackground(m.ripple(theme.text, null, 20));

        // Icon, with a notification badge on apps (shortcuts don't have their own counts).
        FrameLayout iconBox = new FrameLayout(activity);
        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(cell.icon);
        if (theme.grayscale) icon.setColorFilter(Theme.grayscaleFilter());
        iconBox.addView(icon, new FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER));
        if (cell.app != null) {
            TextView badge = m.badge();
            badge.setTag(cell.app.packageName());
            M3.setBadgeCount(badge, NotificationDotService.count(cell.app.packageName()));
            iconBox.addView(badge, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(M3.BADGE_DP), Gravity.TOP | Gravity.END));
            badges.add(badge);
        }
        v.addView(iconBox, new LinearLayout.LayoutParams(dp(iconDp + 10), dp(iconDp + 6)));

        if (showLabel) {
            TextView label = m.text(cell.label, M3.LABEL_MEDIUM, theme.text);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, iconDp >= 56 ? 13 : 12);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            v.addView(label, lp);
        }
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (cell.app != null) host.launch(cell.app, view);
                else startShortcut(cell.shortcut, view);
            }
        });
        // The tiles fill the whole card, so their long-press menu also carries the card's options.
        v.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View view) {
                showCardMenu(cardIndex, view, cell);
                return true;
            }
        });
        return v;
    }

    /** Refreshes app-card badges after notifications change, without rebuilding the cards. */
    void updateBadges() {
        for (TextView b : badges) M3.setBadgeCount(b, NotificationDotService.count((String) b.getTag()));
    }

    // ------------------------------------------------------- shortcuts

    /** "s:<userSerial>:<package>:<encoded shortcut id>" */
    private String shortcutKey(ShortcutInfo si) {
        return SHORTCUT + userManager.getSerialNumberForUser(si.getUserHandle()) + ":" + si.getPackage()
                + ":" + Uri.encode(si.getId());
    }

    private ShortcutInfo findShortcut(String key) {
        String[] parts = key.substring(SHORTCUT.length()).split(":", 3);
        if (parts.length < 3) return null;
        try {
            UserHandle user = userManager.getUserForSerialNumber(Long.parseLong(parts[0]));
            if (user == null || !launcherApps.hasShortcutHostPermission()) return null;
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
            q.setPackage(parts[1]);
            q.setShortcutIds(Collections.singletonList(Uri.decode(parts[2])));
            q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                    | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                    | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
            List<ShortcutInfo> found = launcherApps.getShortcuts(q, user);
            return found == null || found.isEmpty() ? null : found.get(0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Every shortcut apps currently offer, across profiles (needs Chinook to be the home app). */
    private List<ShortcutInfo> allShortcuts() {
        List<ShortcutInfo> out = new ArrayList<>();
        try {
            if (!launcherApps.hasShortcutHostPermission()) return out;
            Set<String> seen = new HashSet<>();
            for (UserHandle user : userManager.getUserProfiles()) {
                LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
                q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                        | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST);
                List<ShortcutInfo> found = launcherApps.getShortcuts(q, user);
                if (found == null) continue;
                for (ShortcutInfo si : found) {
                    if (si.isEnabled() && seen.add(shortcutKey(si))) out.add(si);
                }
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    private String shortcutLabel(ShortcutInfo si) {
        CharSequence l = si.getShortLabel();
        if (TextUtils.isEmpty(l)) l = si.getLongLabel();
        return l == null ? appLabel(si.getPackage()) : l.toString();
    }

    private Drawable shortcutIcon(ShortcutInfo si) {
        try {
            Drawable d = launcherApps.getShortcutIconDrawable(si, activity.getResources().getDisplayMetrics().densityDpi);
            if (d != null) return d;
        } catch (RuntimeException ignored) {
        }
        for (AppInfo a : host.visibleApps()) if (a.packageName().equals(si.getPackage())) return a.icon;
        return null;
    }

    private String appLabel(String pkg) {
        for (AppInfo a : host.visibleApps()) if (a.packageName().equals(pkg)) return a.label;
        return pkg;
    }

    private void startShortcut(ShortcutInfo si, View source) {
        try {
            int[] pos = new int[2];
            source.getLocationOnScreen(pos);
            Rect bounds = new Rect(pos[0], pos[1], pos[0] + source.getWidth(), pos[1] + source.getHeight());
            launcherApps.startShortcut(si.getPackage(), si.getId(), bounds, null, si.getUserHandle());
        } catch (RuntimeException e) {
            Toast.makeText(activity, "This shortcut isn't available any more", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Pins every shortcut placed on a card, so it keeps working even if the app stops offering it
     * (dynamic shortcuts like Gmail accounts can change). Unpins packages no longer used.
     */
    private void syncPins() {
        try {
            if (!launcherApps.hasShortcutHostPermission()) return;
            java.util.Map<String, List<String>> byPackage = new java.util.HashMap<>();
            for (String card : cards) {
                if (!card.startsWith(APPS)) continue;
                for (String k : appKeys(card)) {
                    if (!k.startsWith(SHORTCUT)) continue;
                    String[] parts = k.substring(SHORTCUT.length()).split(":", 3);
                    if (parts.length < 3) continue;
                    String group = parts[0] + ":" + parts[1];
                    List<String> ids = byPackage.get(group);
                    if (ids == null) byPackage.put(group, ids = new ArrayList<>());
                    ids.add(Uri.decode(parts[2]));
                }
            }
            Set<String> previous = prefs.pinnedShortcutGroups();
            for (String group : previous) {
                if (!byPackage.containsKey(group)) pin(group, new ArrayList<String>());
            }
            for (java.util.Map.Entry<String, List<String>> e : byPackage.entrySet()) pin(e.getKey(), e.getValue());
            prefs.setPinnedShortcutGroups(byPackage.keySet());
        } catch (RuntimeException ignored) {
        }
    }

    private void pin(String group, List<String> ids) {
        String[] parts = group.split(":", 2);
        UserHandle user = userManager.getUserForSerialNumber(Long.parseLong(parts[0]));
        if (user != null) launcherApps.pinShortcuts(parts[1], ids, user);
    }

    /** From an app's long-press menu: pick some of its shortcuts and put them on a card. */
    void addShortcutsFrom(AppInfo app, final List<ShortcutInfo> shortcuts) {
        if (shortcuts.isEmpty()) return;
        List<SearchablePicker.Item> items = new ArrayList<>();
        for (final ShortcutInfo si : shortcuts) {
            items.add(new SearchablePicker.Item(shortcutLabel(si), app.label, new SearchablePicker.IconSource() {
                @Override public Drawable load() {
                    return shortcutIcon(si);
                }
            }, null));
        }
        SearchablePicker.multi(activity, m, app.label + " shortcuts", items, null, MAX_APPS, "Search shortcuts",
                "Shortcuts", new SearchablePicker.OnSave() {
                    @Override public void onSave(List<Integer> selectedInOrder) {
                        if (selectedInOrder.isEmpty()) return;
                        List<String> keys = new ArrayList<>();
                        for (int i : selectedInOrder) keys.add(shortcutKey(shortcuts.get(i)));
                        chooseTargetCard(keys);
                    }
                });
    }

    private void chooseTargetCard(final List<String> keys) {
        List<Integer> appCards = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) if (cards.get(i).startsWith(APPS)) appCards.add(i);
        if (appCards.isEmpty()) {
            insertCard(APPS + TextUtils.join("|", keys));
            return;
        }
        Surfaces.Sheet sheet = new Surfaces.Sheet(activity, m, "Add to which card?");
        for (final int index : appCards) {
            List<String> existing = appKeys(cards.get(index));
            List<String> names = new ArrayList<>();
            for (String k : existing) {
                Cell c = resolveCell(k);
                if (c != null) names.add(c.label);
            }
            String summary = existing.size() + " of " + MAX_APPS
                    + (names.isEmpty() ? "" : " · " + TextUtils.join(", ", names));
            sheet.item(R.drawable.ic_apps, "Card " + (index + 1), summary, new Runnable() {
                @Override public void run() {
                    List<String> merged = appKeys(cards.get(index));
                    int added = 0;
                    for (String k : keys) {
                        if (merged.contains(k)) continue;
                        if (merged.size() >= MAX_APPS) break;
                        merged.add(k);
                        added++;
                    }
                    if (added < keys.size()) {
                        Toast.makeText(activity, "That card holds " + MAX_APPS + " tiles; added " + added,
                                Toast.LENGTH_SHORT).show();
                    }
                    cards.set(index, APPS + TextUtils.join("|", merged));
                    save();
                    render();
                    pager.setCurrent(index);
                }
            });
        }
        sheet.item(R.drawable.ic_add, "New card", "Start a new app card with these", new Runnable() {
            @Override public void run() {
                insertCard(APPS + TextUtils.join("|", keys));
            }
        });
        sheet.show();
    }

    // ------------------------------------------------------------- edit

    /** Card options; when opened from a tile on an app card, that tile's options come first. */
    private void showCardMenu(final int index, final View anchor, final Cell cell) {
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        final String card = cards.get(index);
        PopupMenu menu = new PopupMenu(activity, anchor, Gravity.CENTER);
        if (cell != null) {
            if (cell.app != null) menu.getMenu().add(0, 7, 0, cell.label + " options");
            menu.getMenu().add(0, 8, 0, "Remove " + cell.label + " from card");
        }
        if (card.startsWith(APPS)) menu.getMenu().add(0, 1, 0, "Choose apps & shortcuts");
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
                        host.onAppLongPress(cell.app, anchor);
                        break;
                    case 8: {
                        List<String> keys = appKeys(card);
                        keys.remove(cell.key);
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
        sheet.item(R.drawable.ic_apps, "Apps & shortcuts", "Up to " + MAX_APPS + " apps or shortcuts, e.g. Gmail accounts",
                new Runnable() {
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
        final List<String> keys = new ArrayList<>();
        List<SearchablePicker.Item> items = new ArrayList<>();
        for (AppInfo a : host.visibleApps()) {
            keys.add(a.key);
            items.add(new SearchablePicker.Item(a.label, null, a.icon));
        }
        // Other apps' shortcuts too, e.g. each Gmail account or a WhatsApp chat.
        for (final ShortcutInfo si : allShortcuts()) {
            keys.add(shortcutKey(si));
            items.add(new SearchablePicker.Item(shortcutLabel(si), "Shortcut \u00B7 " + appLabel(si.getPackage()),
                    new SearchablePicker.IconSource() {
                        @Override public Drawable load() {
                            return shortcutIcon(si);
                        }
                    }, null));
        }
        List<Integer> initial = new ArrayList<>();
        for (String k : appKeys(cards.get(index))) {
            int i = keys.indexOf(k);
            if (i >= 0) initial.add(i);
        }
        SearchablePicker.multi(activity, m, "Apps & shortcuts", items, initial, MAX_APPS,
                "Search apps or shortcuts", "Apps, then shortcuts", new SearchablePicker.OnSave() {
                    @Override public void onSave(List<Integer> selectedInOrder) {
                        List<String> chosen = new ArrayList<>();
                        for (int i : selectedInOrder) chosen.add(keys.get(i));
                        cards.set(index, APPS + TextUtils.join("|", chosen));
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
        syncPins();
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
