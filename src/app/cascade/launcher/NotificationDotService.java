package app.cascade.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashSet;
import java.util.Set;

/** Optional: tracks which packages have notifications so the launcher can draw dots. */
public class NotificationDotService extends NotificationListenerService {
    interface Listener {
        void onDotsChanged();
    }

    private static final Set<String> active = new HashSet<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Listener listener;

    static synchronized boolean hasDot(String packageName) {
        return active.contains(packageName);
    }

    static void setListener(Listener l) {
        listener = l;
    }

    static boolean isEnabled(Context context) {
        String flat = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        if (flat == null) return false;
        String me = new ComponentName(context, NotificationDotService.class).flattenToString();
        for (String s : flat.split(":")) {
            if (me.equals(s)) return true;
        }
        return false;
    }

    @Override public void onListenerConnected() { refresh(); }
    @Override public void onNotificationPosted(StatusBarNotification sbn) { refresh(); }
    @Override public void onNotificationRemoved(StatusBarNotification sbn) { refresh(); }

    @Override public void onListenerDisconnected() {
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
        Set<String> packages = new HashSet<>();
        if (all != null) {
            for (StatusBarNotification sbn : all) {
                if (sbn.isOngoing() || !sbn.isClearable()) continue;
                packages.add(sbn.getPackageName());
            }
        }
        synchronized (NotificationDotService.class) {
            active.clear();
            active.addAll(packages);
        }
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
