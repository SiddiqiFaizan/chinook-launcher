package app.cascade.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Hosts home-screen widgets: pick, bind, configure, resize, reorder, remove. */
final class WidgetController {
    static final int REQUEST_BIND = 101;
    static final int REQUEST_CONFIGURE = 102;

    private static final int HOST_ID = 0x0CA5;
    private static final int MIN_HEIGHT_DP = 56;
    private static final int MAX_HEIGHT_DP = 640;

    private final Activity activity;
    private final Prefs prefs;
    private final Theme theme;
    private final AppWidgetManager manager;
    private final AppWidgetHost host;
    private final LinearLayout container;
    private final float density;

    private int pendingId = -1;
    private AppWidgetProviderInfo pendingInfo;

    private static final class Entry {
        final int id;
        int heightDp;

        Entry(int id, int heightDp) {
            this.id = id;
            this.heightDp = heightDp;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    WidgetController(Activity activity, Prefs prefs, Theme theme, LinearLayout container) {
        this.activity = activity;
        this.prefs = prefs;
        this.theme = theme;
        this.container = container;
        this.density = activity.getResources().getDisplayMetrics().density;
        this.manager = AppWidgetManager.getInstance(activity);
        this.host = new AppWidgetHost(activity.getApplicationContext(), HOST_ID) {
            @Override protected AppWidgetHostView onCreateView(Context context, int appWidgetId,
                                                               AppWidgetProviderInfo info) {
                return new WidgetHostView(context);
            }
        };
        for (String s : prefs.widgets()) {
            String[] parts = s.split(":");
            try {
                entries.add(new Entry(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
            } catch (RuntimeException ignored) {
            }
        }
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    void startListening() {
        try {
            host.startListening();
        } catch (RuntimeException ignored) {
        }
    }

    void stopListening() {
        try {
            host.stopListening();
        } catch (RuntimeException ignored) {
        }
    }

    // ------------------------------------------------------------ render

    void render() {
        container.removeAllViews();
        boolean dropped = false;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            AppWidgetProviderInfo info = manager.getAppWidgetInfo(e.id);
            if (info == null) {
                entries.remove(i--);
                dropped = true;
                continue;
            }
            container.addView(createCard(e, info));
        }
        if (dropped) save();
    }

    private View createCard(final Entry e, AppWidgetProviderInfo info) {
        FrameLayout card = new FrameLayout(activity);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(theme.cardBackground);
        bg.setCornerRadius(dp(26));
        card.setBackground(bg);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(26));
            }
        });
        card.setClipToOutline(true);
        if (theme.grayscale) card.setLayerType(View.LAYER_TYPE_HARDWARE, Theme.grayscaleLayerPaint());

        final WidgetHostView view = (WidgetHostView) host.createView(activity, e.id, info);
        view.setPadding(0, 0, 0, 0);
        view.setOnLongPressListener(new WidgetHostView.OnLongPressListener() {
            @Override public void onWidgetLongPress(WidgetHostView v) {
                showMenu(e, v);
            }
        });
        card.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(e.heightDp));
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);
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

    private void showMenu(final Entry e, View anchor) {
        PopupMenu menu = new PopupMenu(activity, anchor, Gravity.CENTER);
        final int index = entries.indexOf(e);
        menu.getMenu().add(0, 1, 0, "Resize");
        if (index > 0) menu.getMenu().add(0, 2, 1, "Move up");
        if (index < entries.size() - 1) menu.getMenu().add(0, 3, 2, "Move down");
        menu.getMenu().add(0, 4, 3, "Remove widget");
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(android.view.MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        showResize(e);
                        break;
                    case 2:
                        Collections.swap(entries, index, index - 1);
                        save();
                        render();
                        break;
                    case 3:
                        Collections.swap(entries, index, index + 1);
                        save();
                        render();
                        break;
                    case 4:
                        host.deleteAppWidgetId(e.id);
                        entries.remove(e);
                        save();
                        render();
                        break;
                }
                return true;
            }
        });
        menu.show();
    }

    private void showResize(final Entry e) {
        final int original = e.heightDp;
        final TextView label = new TextView(activity);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setText("Height: " + e.heightDp + " dp");
        final SeekBar seek = new SeekBar(activity);
        seek.setMax(MAX_HEIGHT_DP - MIN_HEIGHT_DP);
        seek.setProgress(e.heightDp - MIN_HEIGHT_DP);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                e.heightDp = MIN_HEIGHT_DP + progress;
                label.setText("Height: " + e.heightDp + " dp");
                View card = container.getChildAt(entries.indexOf(e));
                if (card != null) {
                    ViewGroup.LayoutParams lp = card.getLayoutParams();
                    lp.height = dp(e.heightDp);
                    card.setLayoutParams(lp);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), 0);
        box.addView(label);
        box.addView(seek);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Resize widget")
                .setView(box)
                .setPositiveButton("Done", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        save();
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        e.heightDp = original;
                        render();
                    }
                })
                .create();
        // Keep the widget visible behind the dialog while resizing.
        if (dialog.getWindow() != null) dialog.getWindow().setDimAmount(0.1f);
        dialog.show();
    }

    // -------------------------------------------------------------- pick

    void showPicker() {
        final PackageManager pm = activity.getPackageManager();
        UserManager um = (UserManager) activity.getSystemService(Context.USER_SERVICE);
        final List<AppWidgetProviderInfo> providers = new ArrayList<>();
        for (UserHandle user : um.getUserProfiles()) {
            try {
                providers.addAll(manager.getInstalledProvidersForProfile(user));
            } catch (RuntimeException ignored) {
            }
        }
        if (providers.isEmpty()) {
            Toast.makeText(activity, "No widgets available", Toast.LENGTH_SHORT).show();
            return;
        }
        final Map<String, String> appLabels = new HashMap<>();
        final Map<AppWidgetProviderInfo, String> labels = new HashMap<>();
        for (AppWidgetProviderInfo p : providers) {
            String pkg = p.provider.getPackageName();
            if (!appLabels.containsKey(pkg)) {
                String label = pkg;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    label = pm.getApplicationLabel(ai).toString();
                } catch (PackageManager.NameNotFoundException ignored) {
                }
                appLabels.put(pkg, label);
            }
            labels.put(p, p.loadLabel(pm));
        }
        final Collator collator = Collator.getInstance();
        Collections.sort(providers, new Comparator<AppWidgetProviderInfo>() {
            @Override public int compare(AppWidgetProviderInfo a, AppWidgetProviderInfo b) {
                int c = collator.compare(appLabels.get(a.provider.getPackageName()),
                        appLabels.get(b.provider.getPackageName()));
                return c != 0 ? c : collator.compare(String.valueOf(labels.get(a)), String.valueOf(labels.get(b)));
            }
        });

        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return providers.size(); }
            @Override public Object getItem(int i) { return providers.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override public View getView(int i, View convert, ViewGroup parent) {
                LinearLayout row = (LinearLayout) convert;
                if (row == null) {
                    row = new LinearLayout(activity);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(20), dp(10), dp(20), dp(10));
                    ImageView icon = new ImageView(activity);
                    LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(40), dp(40));
                    ip.rightMargin = dp(16);
                    row.addView(icon, ip);
                    LinearLayout texts = new LinearLayout(activity);
                    texts.setOrientation(LinearLayout.VERTICAL);
                    TextView title = new TextView(activity);
                    title.setTextColor(Color.WHITE);
                    title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                    title.setTypeface(Fonts.medium);
                    TextView sub = new TextView(activity);
                    sub.setTextColor(0x99FFFFFF);
                    sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                    sub.setTypeface(Fonts.regular);
                    texts.addView(title);
                    texts.addView(sub);
                    row.addView(texts);
                }
                AppWidgetProviderInfo p = providers.get(i);
                ImageView icon = (ImageView) row.getChildAt(0);
                LinearLayout texts = (LinearLayout) row.getChildAt(1);
                Drawable d = null;
                try {
                    d = p.loadIcon(activity, activity.getResources().getDisplayMetrics().densityDpi);
                } catch (RuntimeException ignored) {
                }
                icon.setImageDrawable(d);
                icon.setColorFilter(theme.grayscale ? Theme.grayscaleFilter() : null);
                ((TextView) texts.getChildAt(0)).setText(labels.get(p));
                String size = Math.round(p.minWidth / density) + " × " + Math.round(p.minHeight / density) + " dp";
                ((TextView) texts.getChildAt(1)).setText(appLabels.get(p.provider.getPackageName()) + "  ·  " + size);
                return row;
            }
        };

        new AlertDialog.Builder(activity)
                .setTitle("Add widget")
                .setAdapter(adapter, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        bind(providers.get(which));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void bind(AppWidgetProviderInfo info) {
        pendingId = host.allocateAppWidgetId();
        pendingInfo = info;
        boolean allowed;
        try {
            allowed = manager.bindAppWidgetIdIfAllowed(pendingId, info.getProfile(), info.provider, null);
        } catch (RuntimeException e) {
            allowed = false;
        }
        if (allowed) {
            configureOrAdd();
            return;
        }
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.getProfile());
        try {
            activity.startActivityForResult(intent, REQUEST_BIND);
        } catch (ActivityNotFoundException e) {
            cancelPending();
            Toast.makeText(activity, "This device doesn't allow adding widgets", Toast.LENGTH_SHORT).show();
        }
    }

    private void configureOrAdd() {
        if (pendingInfo != null && pendingInfo.configure != null) {
            try {
                host.startAppWidgetConfigureActivityForResult(activity, pendingId, 0, REQUEST_CONFIGURE, null);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // Fall through and add it unconfigured.
            }
        }
        addPending();
    }

    /** Returns true if the result belonged to the widget flow. */
    boolean onActivityResult(int requestCode, int resultCode) {
        if (requestCode != REQUEST_BIND && requestCode != REQUEST_CONFIGURE) return false;
        if (pendingId == -1) return true;
        if (resultCode != Activity.RESULT_OK) {
            cancelPending();
        } else if (requestCode == REQUEST_BIND) {
            configureOrAdd();
        } else {
            addPending();
        }
        return true;
    }

    private void addPending() {
        int h = Math.round(pendingInfo.minHeight / density) + 16;
        h = Math.max(110, Math.min(MAX_HEIGHT_DP, h));
        entries.add(new Entry(pendingId, h));
        pendingId = -1;
        pendingInfo = null;
        save();
        render();
    }

    private void cancelPending() {
        if (pendingId != -1) host.deleteAppWidgetId(pendingId);
        pendingId = -1;
        pendingInfo = null;
    }

    private void save() {
        List<String> out = new ArrayList<>();
        for (Entry e : entries) out.add(e.id + ":" + e.heightDp);
        prefs.setWidgets(out);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
