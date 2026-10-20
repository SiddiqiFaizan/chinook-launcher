package app.chinook.launcher;

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
    private static final String THEME = "theme";
    private static final String BUNDLED_FONT = "bundled_font";
    private static final String WORLD_CLOCKS = "world_clocks";
    private static final String WIDGETS = "widgets";
    private static final String SHOW_ALARM = "show_alarm";
    private static final String CARDS = "cards";
    private static final String CARD_HEIGHT = "card_height";
    private static final String LOCK_NOTE = "lock_note";
    private static final String LABEL_SIZE = "label_size_sp";
    private static final String ICON_SIZE = "icon_size_dp";
    private static final String CLOCK_SIZE = "clock_size_sp";
    private static final String LIST_LABEL_SIZE = "list_label_size_sp";
    private static final String LIST_ICON_SIZE = "list_icon_size_dp";
    private static final String PREVIEWS = "message_previews";
    private static final String ASKED_DOTS = "asked_dots";
    private static final String PINNED_GROUPS = "pinned_shortcut_groups";

    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getSharedPreferences("chinook", Context.MODE_PRIVATE);
    }

    boolean hasFavorites() {
        return sp.contains(FAVORITES);
    }

    List<String> favorites() {
        return getList(FAVORITES);
    }

    void setFavorites(List<String> keys) {
        putList(FAVORITES, keys);
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

    int theme() {
        return sp.getInt(THEME, Theme.COLORED);
    }

    void setTheme(int theme) {
        sp.edit().putInt(THEME, theme).commit();
    }

    boolean bundledFont() {
        return sp.getBoolean(BUNDLED_FONT, true);
    }

    void setBundledFont(boolean on) {
        sp.edit().putBoolean(BUNDLED_FONT, on).commit();
    }

    boolean showAlarm() {
        return sp.getBoolean(SHOW_ALARM, true);
    }

    void setShowAlarm(boolean on) {
        sp.edit().putBoolean(SHOW_ALARM, on).apply();
    }

    /** Time zone ids, e.g. "Europe/London". */
    List<String> worldClocks() {
        return getList(WORLD_CLOCKS);
    }

    void setWorldClocks(List<String> zones) {
        putList(WORLD_CLOCKS, zones);
    }

    boolean hasCards() {
        return sp.contains(CARDS);
    }

    /** Top slider cards: "clock", "widget:<appWidgetId>" or "apps:<key>|<key>|...". */
    List<String> cards() {
        return getList(CARDS);
    }

    void setCards(List<String> cards) {
        putList(CARDS, cards);
    }

    int cardHeightDp() {
        return sp.getInt(CARD_HEIGHT, 250);
    }

    void setCardHeightDp(int dp) {
        sp.edit().putInt(CARD_HEIGHT, dp).apply();
    }

    /** Home-screen app name size in sp; drawer and pop-out sizes follow it. */
    int labelSizeSp() {
        return sp.getInt(LABEL_SIZE, 23);
    }

    void setLabelSizeSp(int sp) {
        this.sp.edit().putInt(LABEL_SIZE, sp).apply();
    }

    /** Home-screen icon size in dp; drawer and pop-out sizes follow it. */
    int iconSizeDp() {
        return sp.getInt(ICON_SIZE, 30);
    }

    void setIconSizeDp(int dp) {
        sp.edit().putInt(ICON_SIZE, dp).apply();
    }

    /** App list (drawer) name size in sp. */
    int listLabelSizeSp() {
        return sp.getInt(LIST_LABEL_SIZE, 18);
    }

    void setListLabelSizeSp(int value) {
        sp.edit().putInt(LIST_LABEL_SIZE, value).apply();
    }

    /** App list (drawer) icon size in dp. */
    int listIconSizeDp() {
        return sp.getInt(LIST_ICON_SIZE, 36);
    }

    void setListIconSizeDp(int value) {
        sp.edit().putInt(LIST_ICON_SIZE, value).apply();
    }

    int clockSizeSp() {
        return sp.getInt(CLOCK_SIZE, 68);
    }

    void setClockSizeSp(int sp) {
        this.sp.edit().putInt(CLOCK_SIZE, sp).apply();
    }

    boolean messagePreviews() {
        return sp.getBoolean(PREVIEWS, true);
    }

    void setMessagePreviews(boolean on) {
        sp.edit().putBoolean(PREVIEWS, on).apply();
    }

    boolean askedDots() {
        return sp.getBoolean(ASKED_DOTS, false);
    }

    void setAskedDots() {
        sp.edit().putBoolean(ASKED_DOTS, true).apply();
    }

    /** "userSerial:package" groups whose shortcuts Chinook has pinned for app cards. */
    Set<String> pinnedShortcutGroups() {
        return new HashSet<>(sp.getStringSet(PINNED_GROUPS, Collections.<String>emptySet()));
    }

    void setPinnedShortcutGroups(Set<String> groups) {
        sp.edit().putStringSet(PINNED_GROUPS, new HashSet<>(groups)).apply();
    }

    String lockNote() {
        return sp.getString(LOCK_NOTE, "");
    }

    void setLockNote(String note) {
        sp.edit().putString(LOCK_NOTE, note).apply();
    }

    /** v2 home widgets as "appWidgetId:heightDp"; read once to migrate them into cards. */
    List<String> widgets() {
        return getList(WIDGETS);
    }

    void setWidgets(List<String> entries) {
        putList(WIDGETS, entries);
    }

    private List<String> getList(String key) {
        String s = sp.getString(key, "");
        if (s.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(s.split("\n")));
    }

    private void putList(String key, List<String> values) {
        sp.edit().putString(key, TextUtils.join("\n", values)).apply();
    }
}
