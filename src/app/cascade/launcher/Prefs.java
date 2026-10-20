package app.cascade.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class Prefs {
    private static final String FAVORITES = "favorites";
    private static final String HIDDEN = "hidden";
    private static final String HOME_ICONS = "home_icons";
    private static final String ASKED_DEFAULT = "asked_default";

    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getSharedPreferences("cascade", Context.MODE_PRIVATE);
    }

    boolean hasFavorites() {
        return sp.contains(FAVORITES);
    }

    List<String> favorites() {
        String s = sp.getString(FAVORITES, "");
        if (s.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(s.split("\n")));
    }

    void setFavorites(List<String> keys) {
        sp.edit().putString(FAVORITES, TextUtils.join("\n", keys)).apply();
    }

    Set<String> hidden() {
        return new HashSet<>(sp.getStringSet(HIDDEN, Collections.<String>emptySet()));
    }

    void setHidden(Set<String> keys) {
        sp.edit().putStringSet(HIDDEN, new HashSet<>(keys)).apply();
    }

    boolean showHomeIcons() {
        return sp.getBoolean(HOME_ICONS, true);
    }

    void setShowHomeIcons(boolean show) {
        sp.edit().putBoolean(HOME_ICONS, show).apply();
    }

    boolean askedDefault() {
        return sp.getBoolean(ASKED_DEFAULT, false);
    }

    void setAskedDefault() {
        sp.edit().putBoolean(ASKED_DEFAULT, true).apply();
    }
}
