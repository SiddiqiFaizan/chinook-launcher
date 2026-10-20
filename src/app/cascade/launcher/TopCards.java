package app.cascade.launcher;

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
    private final Host host;
    private final WidgetBinder binder;
    private final CardPager pager;
    private final LinearLayout dots;
    private final float density;
    private final List<String> cards;

    TopCards(Activity activity, Prefs prefs, Theme theme, Host host, WidgetBinder binder,
             CardPager pager, LinearLayout dots) {
        this.activity = activity;
        this.prefs = prefs;
        this.theme = theme;
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
        View clock = host.clockContent();
        if (clock.getParent() != null) ((ViewGroup) clock.getParent()).removeView(clock);

        boolean dropped = false;
        for (int i = 0; i < cards.size(); i++) {
            String c = cards.get(i);
            View card;
            if (CLOCK.equals(c)) card = clockCard(clock);
            else if (c.startsWith(WIDGET)) card = widgetCard(parseId(c));
            else if (c.startsWith(APPS)) card = appsCard(appKeys(c));
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
                    showCardMenu(index, v);
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
        FrameLayout card = new FrameLayout(activity);
        card.addView(clock, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        return card;
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
        final FrameLayout card = roundedCard();
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
                    view.updateAppWidgetSize(null, w, h, w, h);
                } catch (RuntimeException ignored) {
                }
            }
        });
        return card;
    }

    private View appsCard(List<String> keys) {
        List<AppInfo> apps = new ArrayList<>();
        for (String k : keys) {
            AppInfo a = host.findApp(k);
            if (a != null) apps.add(a);
        }
        FrameLayout card = roundedCard();
        LinearLayout grid = new LinearLayout(activity);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setGravity(Gravity.CENTER);
        grid.setPadding(dp(12), dp(12), dp(12), dp(12));
        if (keys.isEmpty()) {
            TextView t = new TextView(activity);
            t.setText("Long-press to choose apps");
            t.setTextColor(theme.textSecondary);
            t.setTypeface(Fonts.regular);
            t.setGravity(Gravity.CENTER);
            grid.addView(t);
        }
        int columns = apps.size() <= 3 ? Math.max(1, apps.size()) : 4;
        for (int start = 0; start < apps.size(); start += columns) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < columns; c++) {
                int i = start + c;
                View cell = i < apps.size() ? appCell(apps.get(i), apps.size() <= 3) : new View(activity);
                row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = lp.bottomMargin = dp(4);
            grid.addView(row, lp);
        }
        card.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return card;
    }

    private View appCell(final AppInfo app, boolean large) {
        LinearLayout cell = new LinearLayout(activity);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(4), dp(8), dp(4), dp(8));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(18));
        cell.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, mask));
        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(app.icon);
        if (theme.grayscale) icon.setColorFilter(Theme.grayscaleFilter());
        int s = dp(large ? 56 : 46);
        cell.addView(icon, new LinearLayout.LayoutParams(s, s));
        TextView label = new TextView(activity);
        label.setText(app.label);
        label.setTextColor(theme.text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, large ? 14 : 12);
        label.setTypeface(Fonts.medium);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        cell.addView(label, lp);
        cell.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.launch(app, v);
            }
        });
        cell.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                host.onAppLongPress(app, v);
                return true;
            }
        });
        return cell;
    }

    // ------------------------------------------------------------- edit

    private void showCardMenu(final int index, View anchor) {
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        final String card = cards.get(index);
        PopupMenu menu = new PopupMenu(activity, anchor, Gravity.CENTER);
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
                        showHeight();
                        break;
                    case 6:
                        if (card.startsWith(WIDGET)) binder.delete(parseId(card));
                        cards.remove(index);
                        save();
                        render();
                        break;
                }
                return true;
            }
        });
        menu.show();
    }

    void showAddCard() {
        final boolean hasClock = cards.contains(CLOCK);
        List<String> items = new ArrayList<>(Arrays.asList("Widget (weather, calendar, wallet…)", "App shortcuts"));
        if (!hasClock) items.add("Clock");
        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Add a top card")
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            binder.pick(new WidgetBinder.Callback() {
                                @Override public void onWidgetReady(int appWidgetId) {
                                    insertCard(WIDGET + appWidgetId);
                                }
                            });
                        } else if (which == 1) {
                            insertCard(APPS);
                            chooseApps(cards.indexOf(APPS));
                        } else {
                            insertCard(CLOCK);
                        }
                    }
                })
                .show();
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
        final List<AppInfo> apps = host.visibleApps();
        final Set<String> current = new HashSet<>(appKeys(cards.get(index)));
        final List<String> order = new ArrayList<>(appKeys(cards.get(index)));
        String[] labels = new String[apps.size()];
        final boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = apps.get(i).label;
            checked[i] = current.contains(apps.get(i).key);
        }
        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Apps on this card (up to " + MAX_APPS + ")")
                .setMultiChoiceItems(labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface d, int which, boolean isChecked) {
                        String key = apps.get(which).key;
                        if (isChecked && order.size() >= MAX_APPS) {
                            ((AlertDialog) d).getListView().setItemChecked(which, false);
                            checked[which] = false;
                            Toast.makeText(activity, "A card holds up to " + MAX_APPS + " apps", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        checked[which] = isChecked;
                        if (isChecked) order.add(key);
                        else order.remove(key);
                    }
                })
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        cards.set(index, APPS + TextUtils.join("|", order));
                        save();
                        render();
                        pager.setCurrent(index);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showHeight() {
        final int original = prefs.cardHeightDp();
        final TextView label = new TextView(activity);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setText("Height: " + original + " dp");
        final SeekBar seek = new SeekBar(activity);
        seek.setMax(MAX_HEIGHT_DP - MIN_HEIGHT_DP);
        seek.setProgress(original - MIN_HEIGHT_DP);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int h = MIN_HEIGHT_DP + progress;
                label.setText("Height: " + h + " dp");
                ViewGroup.LayoutParams lp = pager.getLayoutParams();
                lp.height = dp(h);
                pager.setLayoutParams(lp);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), 0);
        box.addView(label);
        box.addView(seek);
        AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Top card height")
                .setView(box)
                .setPositiveButton("Done", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        prefs.setCardHeightDp(MIN_HEIGHT_DP + seek.getProgress());
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        ViewGroup.LayoutParams lp = pager.getLayoutParams();
                        lp.height = dp(original);
                        pager.setLayoutParams(lp);
                    }
                })
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setDimAmount(0.1f);
            dialog.getWindow().setGravity(Gravity.BOTTOM);
        }
        dialog.show();
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
