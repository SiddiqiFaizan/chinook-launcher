package app.chinook.launcher;

import android.content.ComponentName;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;

import java.text.Normalizer;

/** One launchable activity, for one user profile. */
final class AppInfo {
    final ComponentName component;
    final UserHandle user;
    final String label;
    final String searchLabel;
    final String key;
    final String section;
    final Drawable icon;

    AppInfo(ComponentName component, UserHandle user, long userSerial, String label, Drawable icon) {
        this.component = component;
        this.user = user;
        this.label = label;
        this.icon = icon;
        this.key = component.flattenToString() + "#" + userSerial;
        this.searchLabel = normalize(label);
        this.section = sectionFor(searchLabel);
    }

    String packageName() {
        return component.getPackageName();
    }

    /** Lower-case, accent-free form used for searching and sectioning. */
    static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}+", "").toLowerCase();
    }

    private static String sectionFor(String normalized) {
        if (normalized.isEmpty()) return "#";
        char c = Character.toUpperCase(normalized.charAt(0));
        return (c >= 'A' && c <= 'Z') ? String.valueOf(c) : "#";
    }
}
