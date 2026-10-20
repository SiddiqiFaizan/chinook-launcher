package app.chinook.launcher;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;
import java.util.Map;

/**
 * Optional notification listener: tracks, per app, how many notifications are showing and the most
 * recent one's title and text, so the launcher can draw count badges and message previews.
 */
public class NotificationDotService extends NotificationListenerService {
    interface Listener {
        void onDotsChanged();
    }

    /** What the launcher shows for one app. */
    static final class Info {
        int count;
        long latestTime;
        CharSequence title;
        CharSequence text;
    }

    private static final Map<String, Info> active = new HashMap<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Listener listener;

    /** "package#profileSerial": the same app in a cloned or work profile counts separately. */
    static String key(String packageName, long userSerial) {
        return packageName + "#" + userSerial;
    }

    static synchronized int count(String badgeKey) {
        Info i = active.get(badgeKey);
        return i == null ? 0 : i.count;
    }

    /** "Title: text" of the newest notification, or null. */
    static synchronized String preview(String badgeKey) {
        Info i = active.get(badgeKey);
        if (i == null) return null;
        String title = i.title == null ? "" : i.title.toString().trim();
        String text = i.text == null ? "" : i.text.toString().trim().replace('\n', ' ');
        if (title.isEmpty()) return text.isEmpty() ? null : text;
        return text.isEmpty() ? title : title + ": " + text;
    }

    static void setListener(Listener l) {
        listener = l;
    }

    /** True while the system has the listener bound and is sending it notifications. */
    private static volatile boolean connected;

    static boolean isConnected() {
        return connected;
    }

    /** Diagnostics for the setup guide: notifications read and apps with a badge. */
    private static volatile int seenNotifications;
    private static volatile int appsWithBadges;

    static int seenNotifications() {
        return seenNotifications;
    }

    static int appsWithBadges() {
        return appsWithBadges;
    }

    /** Whether notification access is granted, using the official check (Android 8.1+). */
    static boolean isEnabled(Context context) {
        ComponentName me = new ComponentName(context, NotificationDotService.class);
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                try {
                    return nm.isNotificationListenerAccessGranted(me);
                } catch (RuntimeException ignored) {
                    // Fall back to the setting below.
                }
            }
        }
        String flat = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        if (flat == null) return false;
        for (String s : flat.split(":")) {
            if (me.flattenToString().equals(s)) return true;
        }
        return false;
    }

    @Override public void onListenerConnected() {
        connected = true;
        refresh();
    }
    @Override public void onNotificationPosted(StatusBarNotification sbn) { refresh(); }
    @Override public void onNotificationRemoved(StatusBarNotification sbn) { refresh(); }

    @Override public void onListenerDisconnected() {
        connected = false;
        synchronized (NotificationDotService.class) {
            active.clear();
        }
        notifyChanged();
    }

    private void refresh() {
        StatusBarNotification[] all;
        try {
            all = getActiveNotifications();
        } catch (RuntimeException e) {
            return;
        }
        seenNotifications = all == null ? 0 : all.length;
        android.os.UserManager um = (android.os.UserManager) getSystemService(Context.USER_SERVICE);
        Map<String, Info> next = new HashMap<>();
        Map<String, Boolean> onlySummary = new HashMap<>();
        if (all != null) {
            for (StatusBarNotification sbn : all) {
                if (sbn.isOngoing() || !sbn.isClearable()) continue;
                Notification n = sbn.getNotification();
                // Key by package *and* profile, so two WhatsApps (original + clone) stay apart.
                long serial = um == null ? -1 : um.getSerialNumberForUser(sbn.getUser());
                // Notifications posted for all users have no single profile: count them on the main one.
                if (serial < 0 && um != null) serial = um.getSerialNumberForUser(android.os.Process.myUserHandle());
                String pkg = key(sbn.getPackageName(), serial);
                boolean summary = (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
                Info info = next.get(pkg);
                if (info == null) {
                    info = new Info();
                    next.put(pkg, info);
                    onlySummary.put(pkg, true);
                }
                // Group summaries duplicate their children, so they don't add to the count.
                if (!summary) {
                    info.count += Math.max(1, n.number);
                    onlySummary.put(pkg, false);
                }
                if (!summary && sbn.getPostTime() >= info.latestTime) {
                    Bundle extras = n.extras;
                    info.latestTime = sbn.getPostTime();
                    info.title = extras == null ? null : extras.getCharSequence(Notification.EXTRA_TITLE);
                    info.text = extras == null ? null : extras.getCharSequence(Notification.EXTRA_TEXT);
                }
            }
        }
        for (Map.Entry<String, Boolean> e : onlySummary.entrySet()) {
            if (e.getValue()) next.get(e.getKey()).count = 1;
        }
        synchronized (NotificationDotService.class) {
            active.clear();
            active.putAll(next);
        }
        appsWithBadges = next.size();
        notifyChanged();
    }

    private static void notifyChanged() {
        main.post(new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onDotsChanged();
            }
        });
    }
}
