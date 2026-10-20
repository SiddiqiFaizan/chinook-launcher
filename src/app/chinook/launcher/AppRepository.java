package app.chinook.launcher;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.View;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads launchable apps for every user profile and watches for package changes. */
final class AppRepository {
    interface Listener {
        void onAppsChanged(List<AppInfo> apps);
    }

    private final Context context;
    private final LauncherApps launcherApps;
    private final UserManager userManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Listener listener;

    private final Runnable reloadTask = new Runnable() {
        @Override public void run() {
            reload();
        }
    };

    private final LauncherApps.Callback callback = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String packageName, UserHandle user) { scheduleReload(); }
        @Override public void onPackageAdded(String packageName, UserHandle user) { scheduleReload(); }
        @Override public void onPackageChanged(String packageName, UserHandle user) { scheduleReload(); }
        @Override public void onPackagesAvailable(String[] packageNames, UserHandle user, boolean replacing) { scheduleReload(); }
        @Override public void onPackagesUnavailable(String[] packageNames, UserHandle user, boolean replacing) { scheduleReload(); }
        @Override public void onPackagesSuspended(String[] packageNames, UserHandle user) { scheduleReload(); }
        @Override public void onPackagesUnsuspended(String[] packageNames, UserHandle user) { scheduleReload(); }
    };

    AppRepository(Context context) {
        this.context = context.getApplicationContext();
        this.launcherApps = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        this.userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
    }

    LauncherApps launcherApps() {
        return launcherApps;
    }

    void start(Listener listener) {
        this.listener = listener;
        launcherApps.registerCallback(callback, main);
        reload();
    }

    void stop() {
        launcherApps.unregisterCallback(callback);
        main.removeCallbacks(reloadTask);
        listener = null;
    }

    /** Coalesces bursts of package events (e.g. an app update) into one reload. */
    void scheduleReload() {
        main.removeCallbacks(reloadTask);
        main.postDelayed(reloadTask, 250);
    }

    void reload() {
        executor.execute(new Runnable() {
            @Override public void run() {
                final List<AppInfo> loaded = load();
                main.post(new Runnable() {
                    @Override public void run() {
                        if (listener != null) listener.onAppsChanged(loaded);
                    }
                });
            }
        });
    }

    private List<AppInfo> load() {
        String self = context.getPackageName();
        List<AppInfo> result = new ArrayList<>();
        for (UserHandle user : userManager.getUserProfiles()) {
            long serial = userManager.getSerialNumberForUser(user);
            List<LauncherActivityInfo> infos;
            try {
                infos = launcherApps.getActivityList(null, user);
            } catch (SecurityException e) {
                continue;
            }
            for (LauncherActivityInfo info : infos) {
                if (self.equals(info.getComponentName().getPackageName())) continue;
                CharSequence l = info.getLabel();
                String label = l == null ? info.getComponentName().getPackageName() : l.toString().trim();
                if (!user.equals(android.os.Process.myUserHandle())) label = profileLabel(label, user);
                Drawable icon;
                try {
                    icon = info.getBadgedIcon(0);
                } catch (RuntimeException e) {
                    icon = null;
                }
                result.add(new AppInfo(info.getComponentName(), user, serial, label, icon));
            }
        }
        final Collator collator = Collator.getInstance();
        collator.setStrength(Collator.PRIMARY);
        Collections.sort(result, new Comparator<AppInfo>() {
            @Override public int compare(AppInfo a, AppInfo b) {
                boolean aHash = "#".equals(a.section), bHash = "#".equals(b.section);
                if (aHash != bHash) return aHash ? -1 : 1;
                int c = collator.compare(a.label, b.label);
                return c != 0 ? c : a.key.compareTo(b.key);
            }
        });
        return result;
    }

    /**
     * Name for an app in another profile, so a cloned or work copy is distinguishable from the
     * original: Android's own wording when it has one ("Work WhatsApp"), otherwise "(Clone)".
     */
    private String profileLabel(String label, UserHandle user) {
        try {
            CharSequence badged = context.getPackageManager().getUserBadgedLabel(label, user);
            if (badged != null && !label.contentEquals(badged)) return badged.toString().trim();
        } catch (RuntimeException ignored) {
        }
        return label + " (Clone)";
    }

    void launch(AppInfo app, View source) {
        Rect bounds = null;
        Bundle opts = null;
        if (source != null) {
            int[] pos = new int[2];
            source.getLocationOnScreen(pos);
            bounds = new Rect(pos[0], pos[1], pos[0] + source.getWidth(), pos[1] + source.getHeight());
            opts = ActivityOptions.makeClipRevealAnimation(source, 0, 0, source.getWidth(), source.getHeight()).toBundle();
        }
        launcherApps.startMainActivity(app.component, app.user, bounds, opts);
    }

    void showDetails(AppInfo app) {
        launcherApps.startAppDetailsActivity(app.component, app.user, null, null);
    }
}
