package app.chinook.launcher;

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
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Owns the AppWidgetHost: lets the user pick a widget, binds and configures it, creates its view. */
final class WidgetBinder {
    interface Callback {
        void onWidgetReady(int appWidgetId);
    }

    static final int REQUEST_BIND = 101;
    static final int REQUEST_CONFIGURE = 102;

    private static final int HOST_ID = 0x0CA5;

    private final Activity activity;
    private final M3 m;
    private final AppWidgetManager manager;
    private final AppWidgetHost host;
    private final float density;

    private int pendingId = -1;
    private AppWidgetProviderInfo pendingInfo;
    private Callback pendingCallback;

    WidgetBinder(Activity activity, M3 m) {
        this.activity = activity;
        this.m = m;
        this.density = activity.getResources().getDisplayMetrics().density;
        this.manager = AppWidgetManager.getInstance(activity);
        this.host = new AppWidgetHost(activity.getApplicationContext(), HOST_ID) {
            @Override protected AppWidgetHostView onCreateView(Context context, int appWidgetId,
                                                               AppWidgetProviderInfo info) {
                return new WidgetHostView(context);
            }
        };
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

    /** The widget's view, or null if the widget no longer exists (e.g. its app was removed). */
    WidgetHostView createView(int appWidgetId) {
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(appWidgetId);
        if (info == null) return null;
        WidgetHostView view = (WidgetHostView) host.createView(activity, appWidgetId, info);
        view.setPadding(0, 0, 0, 0);
        return view;
    }

    void delete(int appWidgetId) {
        try {
            host.deleteAppWidgetId(appWidgetId);
        } catch (RuntimeException ignored) {
        }
    }

    void pick(final Callback callback) {
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

        List<SearchablePicker.Item> items = new ArrayList<>();
        final int dpi = activity.getResources().getDisplayMetrics().densityDpi;
        for (final AppWidgetProviderInfo p : providers) {
            String size = Math.round(p.minWidth / density) + " \u00D7 " + Math.round(p.minHeight / density) + " dp";
            items.add(new SearchablePicker.Item(labels.get(p),
                    appLabels.get(p.provider.getPackageName()) + "  \u00B7  " + size,
                    new SearchablePicker.IconSource() {
                        @Override public Drawable load() {
                            return p.loadIcon(activity, dpi);
                        }
                    }, null));
        }
        SearchablePicker.single(activity, m, "Add widget", items, "Search widgets or apps",
                new SearchablePicker.OnPick() {
                    @Override public void onPick(int index) {
                        bind(providers.get(index), callback);
                    }
                });
    }

    private void bind(AppWidgetProviderInfo info, Callback callback) {
        pendingId = host.allocateAppWidgetId();
        pendingInfo = info;
        pendingCallback = callback;
        boolean allowed;
        try {
            allowed = manager.bindAppWidgetIdIfAllowed(pendingId, info.getProfile(), info.provider, null);
        } catch (RuntimeException e) {
            allowed = false;
        }
        if (allowed) {
            configureOrFinish();
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

    private void configureOrFinish() {
        if (pendingInfo != null && pendingInfo.configure != null) {
            try {
                host.startAppWidgetConfigureActivityForResult(activity, pendingId, 0, REQUEST_CONFIGURE, null);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // Fall through and add it unconfigured.
            }
        }
        finishPending();
    }

    /** Returns true if the result belonged to the widget flow. */
    boolean onActivityResult(int requestCode, int resultCode) {
        if (requestCode != REQUEST_BIND && requestCode != REQUEST_CONFIGURE) return false;
        if (pendingId == -1) return true;
        if (resultCode != Activity.RESULT_OK) cancelPending();
        else if (requestCode == REQUEST_BIND) configureOrFinish();
        else finishPending();
        return true;
    }

    private void finishPending() {
        int id = pendingId;
        Callback cb = pendingCallback;
        pendingId = -1;
        pendingInfo = null;
        pendingCallback = null;
        if (cb != null) cb.onWidgetReady(id);
    }

    private void cancelPending() {
        if (pendingId != -1) delete(pendingId);
        pendingId = -1;
        pendingInfo = null;
        pendingCallback = null;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
