package app.cascade.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity implements AppRepository.Listener,
        AlphabetScrollerView.Listener, NotificationDotService.Listener {

    private static final int ACCENT = 0xFF8AB4FF;
    private static final int TEXT_SHADOW = 0x99000000;
    private static final int MAX_SHORTCUTS = 5;

    private Prefs prefs;
    private AppRepository repo;
    private float density;

    private List<AppInfo> rawApps = new ArrayList<>();
    private List<AppInfo> visibleApps = new ArrayList<>();
    private final Map<String, AppInfo> byKey = new HashMap<>();
    private List<String> favorites = new ArrayList<>();
    private boolean appsLoaded;

    private FrameLayout root;
    private LinearLayout home;
    private View clockBlock;
    private LinearLayout favoritesContainer;
    private FrameLayout drawer;
    private LinearLayout drawerContent;
    private EditText search;
    private ListView list;
    private DrawerAdapter adapter;
    private AlphabetScrollerView scroller;

    private boolean drawerOpen;
    private GestureDetector homeGestures;
    private GestureDetector drawerGestures;
    private boolean gestureStreamIgnored;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        prefs = new Prefs(this);
        repo = new AppRepository(this);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        buildUi();
        setContentView(root);
        setupGestures();

        favorites = prefs.favorites();
        repo.start(this);
        NotificationDotService.setListener(this);

        if (!prefs.askedDefault()) {
            prefs.setAskedDefault();
            requestDefaultLauncher();
        }
    }

    @Override protected void onDestroy() {
        repo.stop();
        NotificationDotService.setListener(null);
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshDots();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while we are already in front: go back to the home page.
        if (drawerOpen) closeDrawer(true);
    }

    @Override public void onBackPressed() {
        if (drawerOpen) closeDrawer(true);
        // A launcher never finishes on back.
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        root = new FrameLayout(this);
        GradientDrawable scrim = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x40000000, 0x00000000, 0x00000000, 0x59000000});
        root.setBackground(scrim);

        // Home page: clock + date at top, favorites lower down within thumb reach.
        home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);

        LinearLayout clockLayout = new LinearLayout(this);
        clockLayout.setOrientation(LinearLayout.VERTICAL);
        clockLayout.setPadding(dp(8), 0, 0, 0);
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        styleOnWallpaper(clock);
        clock.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                safeStart(new Intent(AlarmClock.ACTION_SHOW_ALARMS));
            }
        });
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, MMMM d");
        date.setFormat24Hour("EEEE, MMMM d");
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        styleOnWallpaper(date);
        date.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                safeStart(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR));
            }
        });
        clockLayout.addView(clock, wrap());
        clockLayout.addView(date, wrap());
        clockBlock = clockLayout;
        LinearLayout.LayoutParams clockLp = wrap();
        clockLp.topMargin = dp(48);
        home.addView(clockLayout, clockLp);

        home.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        favoritesContainer = new LinearLayout(this);
        favoritesContainer.setOrientation(LinearLayout.VERTICAL);
        home.addView(favoritesContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        home.addView(new View(this), new LinearLayout.LayoutParams(1, dp(56)));
        root.addView(home, match());

        // App drawer: search + full alphabetical list.
        drawer = new FrameLayout(this);
        drawer.setBackgroundColor(0xE60E1015);
        drawer.setVisibility(View.GONE);
        drawerContent = new LinearLayout(this);
        drawerContent.setOrientation(LinearLayout.VERTICAL);

        search = new EditText(this);
        search.setHint("Search apps");
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(0x99FFFFFF);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setColor(0x24FFFFFF);
        searchBg.setCornerRadius(dp(26));
        search.setBackground(searchBg);
        search.setPadding(dp(20), dp(12), dp(20), dp(12));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                adapter.setQuery(s.toString().trim());
                list.setSelection(0);
            }
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                String q = search.getText().toString().trim();
                if (q.isEmpty()) return true;
                AppInfo first = adapter.firstApp();
                if (first != null) launch(first, null);
                else webSearch(q);
                return true;
            }
        });
        drawerContent.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new GradientDrawable());
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        adapter = new DrawerAdapter();
        list.setAdapter(adapter);
        drawerContent.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        drawer.addView(drawerContent, match());
        root.addView(drawer, match());

        // Alphabet strip stays on top of both pages.
        scroller = new AlphabetScrollerView(this);
        scroller.setAccentColor(ACCENT);
        scroller.setListener(this);
        root.addView(scroller, new FrameLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int left = insets.getSystemWindowInsetLeft();
                int top = insets.getSystemWindowInsetTop();
                int right = insets.getSystemWindowInsetRight();
                int bottom = insets.getSystemWindowInsetBottom();
                home.setPadding(dp(20) + left, top, dp(56) + right, bottom);
                drawerContent.setPadding(dp(16) + left, top + dp(12), dp(52) + right, 0);
                list.setPadding(0, dp(8), 0, bottom + dp(16));
                scroller.setPadding(0, top + dp(32), right, bottom + dp(32));
                return insets.consumeSystemWindowInsets();
            }
        });
    }

    private void setupGestures() {
        homeGestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null) return false;
                float dy = e2.getY() - e1.getY();
                float dx = e2.getX() - e1.getX();
                if (Math.abs(dy) < dp(60) || Math.abs(dy) < Math.abs(dx) * 1.5f) return false;
                if (dy < 0) openDrawer(true);
                else expandNotifications();
                return true;
            }

            @Override public void onLongPress(MotionEvent e) {
                if (hitsChild(favoritesContainer, e) || hitsView(clockBlock, e)) return;
                root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                showSettings();
            }
        });
        drawerGestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null) return false;
                float dy = e2.getY() - e1.getY();
                if (dy > dp(80) && Math.abs(dy) > Math.abs(e2.getX() - e1.getX()) * 1.5f
                        && !list.canScrollVertically(-1)) {
                    closeDrawer(true);
                    return true;
                }
                return false;
            }
        });
    }

    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            gestureStreamIgnored = scroller.isInStrip(ev.getX() - scroller.getLeft());
        }
        if (!gestureStreamIgnored) {
            (drawerOpen ? drawerGestures : homeGestures).onTouchEvent(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    // ------------------------------------------------------------ data

    @Override public void onAppsChanged(List<AppInfo> apps) {
        rawApps = apps;
        byKey.clear();
        for (AppInfo a : apps) byKey.put(a.key, a);
        if (!appsLoaded && !prefs.hasFavorites()) {
            favorites = defaultFavorites();
            prefs.setFavorites(favorites);
        }
        appsLoaded = true;
        applyApps();
    }

    private void applyApps() {
        Set<String> hidden = prefs.hidden();
        visibleApps = new ArrayList<>();
        LinkedHashSet<String> sections = new LinkedHashSet<>();
        for (AppInfo a : rawApps) {
            if (hidden.contains(a.key)) continue;
            visibleApps.add(a);
            sections.add(a.section);
        }
        adapter.setApps(visibleApps);
        scroller.setLetters(new ArrayList<>(sections));
        renderFavorites();
    }

    private List<String> defaultFavorites() {
        Intent[] intents = {
                new Intent(Intent.ACTION_DIAL),
                new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
                new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY),
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL),
        };
        PackageManager pm = getPackageManager();
        List<String> keys = new ArrayList<>();
        for (Intent intent : intents) {
            String pkg = null;
            ResolveInfo ri = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null && !"android".equals(ri.activityInfo.packageName)) {
                pkg = ri.activityInfo.packageName;
            } else {
                List<ResolveInfo> all = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
                if (!all.isEmpty()) pkg = all.get(0).activityInfo.packageName;
            }
            if (pkg == null) continue;
            for (AppInfo a : rawApps) {
                if (a.packageName().equals(pkg) && a.user.equals(Process.myUserHandle())) {
                    if (!keys.contains(a.key)) keys.add(a.key);
                    break;
                }
            }
        }
        return keys;
    }

    private void renderFavorites() {
        favoritesContainer.removeAllViews();
        int shown = 0;
        for (String key : favorites) {
            AppInfo a = byKey.get(key);
            if (a == null) continue;
            View row = createRow(true);
            bindRow(row, a, true);
            favoritesContainer.addView(row, wrap());
            shown++;
        }
        if (shown == 0 && appsLoaded) {
            TextView hint = new TextView(this);
            hint.setText("Drag along the letters on the right to find apps.\nLong-press an app to add it here.");
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            hint.setPadding(dp(8), 0, 0, 0);
            styleOnWallpaper(hint);
            hint.setAlpha(0.8f);
            favoritesContainer.addView(hint, wrap());
        }
    }

    @Override public void onDotsChanged() {
        refreshDots();
    }

    private void refreshDots() {
        if (!appsLoaded) return;
        renderFavorites();
        adapter.notifyDataSetChanged();
    }

    // ---------------------------------------------------------- drawer

    private void openDrawer(boolean focusSearch) {
        if (!drawerOpen) {
            drawerOpen = true;
            drawer.animate().cancel();
            drawer.setVisibility(View.VISIBLE);
            drawer.setAlpha(0f);
            drawer.setTranslationY(dp(32));
            drawer.animate().alpha(1f).translationY(0).setDuration(200).withEndAction(null).start();
            home.animate().alpha(0f).setDuration(150).start();
        }
        if (focusSearch) {
            search.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void closeDrawer(boolean animate) {
        if (!drawerOpen) return;
        drawerOpen = false;
        hideKeyboard();
        search.clearFocus();
        drawer.animate().cancel();
        home.animate().cancel();
        Runnable end = new Runnable() {
            @Override public void run() {
                drawer.setVisibility(View.GONE);
                if (search.length() > 0) search.setText("");
                list.setSelection(0);
            }
        };
        if (animate) {
            drawer.animate().alpha(0f).translationY(dp(32)).setDuration(170).withEndAction(end).start();
            home.animate().alpha(1f).setDuration(200).start();
        } else {
            end.run();
            home.setAlpha(1f);
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
    }

    @Override public void onScrubStart() {
        if (search.length() > 0) search.setText("");
        hideKeyboard();
        search.clearFocus();
        openDrawer(false);
    }

    @Override public void onLetterSelected(String letter) {
        Integer pos = adapter.positionForSection(letter);
        if (pos != null) list.setSelectionFromTop(pos, 0);
    }

    @Override public void onScrubEnd() {
    }

    // --------------------------------------------------------- actions

    private void launch(AppInfo app, View source) {
        try {
            repo.launch(app, source);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Can't open " + app.label, Toast.LENGTH_SHORT).show();
            return;
        }
        if (drawerOpen) {
            root.postDelayed(new Runnable() {
                @Override public void run() {
                    closeDrawer(false);
                }
            }, 350);
        }
    }

    private void webSearch(String query) {
        Intent i = new Intent(Intent.ACTION_WEB_SEARCH);
        i.putExtra(SearchManager.QUERY, query);
        if (!safeStart(i)) {
            safeStart(new Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(query))));
        }
    }

    private boolean safeStart(Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    private void expandNotifications() {
        try {
            Object sbm = getSystemService("statusbar");
            sbm.getClass().getMethod("expandNotificationsPanel").invoke(sbm);
        } catch (Exception ignored) {
        }
    }

    private void requestDefaultLauncher() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager rm = getSystemService(RoleManager.class);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                if (rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    Toast.makeText(this, "Cascade is already your launcher", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 1);
                    return;
                } catch (ActivityNotFoundException ignored) {
                }
            }
        }
        safeStart(new Intent(Settings.ACTION_HOME_SETTINGS));
    }

    private void showAppMenu(final AppInfo app, View anchor) {
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        PopupMenu popup = new PopupMenu(this, anchor, Gravity.START);
        Menu menu = popup.getMenu();
        final List<ShortcutInfo> shortcuts = loadShortcuts(app);
        for (int i = 0; i < shortcuts.size(); i++) {
            CharSequence label = shortcuts.get(i).getShortLabel();
            menu.add(0, 1000 + i, i, label == null ? "Shortcut" : label);
        }
        final int favIndex = favorites.indexOf(app.key);
        if (favIndex >= 0) {
            menu.add(1, 1, 100, "Remove from home");
            if (favIndex > 0) menu.add(1, 2, 101, "Move up");
            if (favIndex < favorites.size() - 1) menu.add(1, 3, 102, "Move down");
        } else {
            menu.add(1, 4, 100, "Add to home");
        }
        menu.add(2, 5, 200, "App info");
        menu.add(2, 6, 201, "Hide");
        menu.add(2, 7, 202, "Uninstall");
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                int id = item.getItemId();
                if (id >= 1000) {
                    startShortcut(shortcuts.get(id - 1000));
                    return true;
                }
                switch (id) {
                    case 1:
                        favorites.remove(app.key);
                        break;
                    case 2:
                        Collections.swap(favorites, favIndex, favIndex - 1);
                        break;
                    case 3:
                        Collections.swap(favorites, favIndex, favIndex + 1);
                        break;
                    case 4:
                        favorites.add(app.key);
                        Toast.makeText(MainActivity.this, app.label + " added to home", Toast.LENGTH_SHORT).show();
                        break;
                    case 5:
                        try {
                            repo.showDetails(app);
                        } catch (RuntimeException ignored) {
                        }
                        return true;
                    case 6: {
                        Set<String> hidden = prefs.hidden();
                        hidden.add(app.key);
                        prefs.setHidden(hidden);
                        favorites.remove(app.key);
                        prefs.setFavorites(favorites);
                        applyApps();
                        Toast.makeText(MainActivity.this, app.label + " hidden. Unhide it from settings.", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    case 7: {
                        Intent i = new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName(), null));
                        i.putExtra(Intent.EXTRA_USER, app.user);
                        safeStart(i);
                        return true;
                    }
                    default:
                        return false;
                }
                prefs.setFavorites(favorites);
                renderFavorites();
                return true;
            }
        });
        popup.show();
    }

    private List<ShortcutInfo> loadShortcuts(AppInfo app) {
        List<ShortcutInfo> result = new ArrayList<>();
        LauncherApps la = repo.launcherApps();
        try {
            if (!la.hasShortcutHostPermission()) return result;
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
            q.setPackage(app.packageName());
            q.setActivity(app.component);
            q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                    | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST);
            List<ShortcutInfo> found = la.getShortcuts(q, app.user);
            if (found == null) return result;
            for (ShortcutInfo s : found) {
                if (!s.isEnabled()) continue;
                result.add(s);
                if (result.size() >= MAX_SHORTCUTS) break;
            }
        } catch (RuntimeException ignored) {
        }
        return result;
    }

    private void startShortcut(ShortcutInfo shortcut) {
        try {
            repo.launcherApps().startShortcut(shortcut, null, null);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Shortcut unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    // -------------------------------------------------------- settings

    private void showSettings() {
        final boolean icons = prefs.showHomeIcons();
        final boolean dots = NotificationDotService.isEnabled(this);
        String[] items = {
                "Change wallpaper",
                icons ? "Hide icons on home screen" : "Show icons on home screen",
                dots ? "Notification dots: on" : "Notification dots: off (tap to enable)",
                "Hidden apps",
                "Set as default launcher",
                "About Cascade",
        };
        new AlertDialog.Builder(this)
                .setTitle("Cascade settings")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0:
                                safeStart(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Choose wallpaper"));
                                break;
                            case 1:
                                prefs.setShowHomeIcons(!icons);
                                renderFavorites();
                                break;
                            case 2:
                                safeStart(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                                break;
                            case 3:
                                showHiddenApps();
                                break;
                            case 4:
                                requestDefaultLauncher();
                                break;
                            case 5:
                                showAbout();
                                break;
                        }
                    }
                })
                .show();
    }

    private void showHiddenApps() {
        final Set<String> hidden = prefs.hidden();
        final List<AppInfo> hiddenApps = new ArrayList<>();
        for (AppInfo a : rawApps) if (hidden.contains(a.key)) hiddenApps.add(a);
        if (hiddenApps.isEmpty()) {
            Toast.makeText(this, "No hidden apps", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[hiddenApps.size()];
        final boolean[] checked = new boolean[hiddenApps.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = hiddenApps.get(i).label;
            checked[i] = true;
        }
        new AlertDialog.Builder(this)
                .setTitle("Hidden apps (untick to unhide)")
                .setMultiChoiceItems(labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface d, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        for (int i = 0; i < checked.length; i++) {
                            if (!checked[i]) hidden.remove(hiddenApps.get(i).key);
                        }
                        prefs.setHidden(hidden);
                        applyApps();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("Cascade Launcher")
                .setMessage("A minimal, open-source launcher.\n\n"
                        + "• Drag the letters on the right edge to browse apps\n"
                        + "• Swipe up for search, swipe down for notifications\n"
                        + "• Long-press an app for shortcuts and options\n"
                        + "• Long-press empty space for settings")
                .setPositiveButton("OK", null)
                .show();
    }

    // ------------------------------------------------------------ rows

    private static final class Row {
        ImageView icon;
        TextView label;
        View dot;
        AppInfo app;
        String webQuery;
    }

    private final View.OnClickListener rowClick = new View.OnClickListener() {
        @Override public void onClick(View v) {
            Row r = (Row) v.getTag();
            if (r.app != null) launch(r.app, v);
            else if (r.webQuery != null) webSearch(r.webQuery);
        }
    };

    private final View.OnLongClickListener rowLongClick = new View.OnLongClickListener() {
        @Override public boolean onLongClick(View v) {
            Row r = (Row) v.getTag();
            if (r.app == null) return false;
            showAppMenu(r.app, v);
            return true;
        }
    };

    private View createRow(boolean onHome) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int vp = dp(onHome ? 9 : 7);
        row.setPadding(dp(8), vp, dp(12), vp);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(16));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, mask));

        Row r = new Row();
        r.icon = new ImageView(this);
        int size = dp(onHome ? 34 : 40);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(size, size);
        ip.rightMargin = dp(16);
        row.addView(r.icon, ip);

        r.label = new TextView(this);
        r.label.setTextColor(Color.WHITE);
        r.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, onHome ? 21 : 17);
        r.label.setSingleLine(true);
        r.label.setEllipsize(TextUtils.TruncateAt.END);
        if (onHome) styleOnWallpaper(r.label);
        row.addView(r.label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        r.dot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(ACCENT);
        r.dot.setBackground(dot);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotLp.leftMargin = dp(10);
        row.addView(r.dot, dotLp);

        row.setTag(r);
        row.setOnClickListener(rowClick);
        row.setOnLongClickListener(rowLongClick);
        return row;
    }

    private void bindRow(View v, AppInfo app, boolean onHome) {
        Row r = (Row) v.getTag();
        r.app = app;
        r.webQuery = null;
        r.icon.setImageDrawable(app.icon);
        r.icon.setImageTintList(null);
        r.icon.setVisibility(onHome && !prefs.showHomeIcons() ? View.GONE : View.VISIBLE);
        r.label.setText(app.label);
        r.dot.setVisibility(NotificationDotService.hasDot(app.packageName()) ? View.VISIBLE : View.GONE);
    }

    private void bindWebRow(View v, String query) {
        Row r = (Row) v.getTag();
        r.app = null;
        r.webQuery = query;
        Drawable icon = getDrawable(android.R.drawable.ic_menu_search);
        r.icon.setImageDrawable(icon);
        r.icon.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        r.icon.setVisibility(View.VISIBLE);
        r.label.setText("Search the web for “" + query + "”");
        r.dot.setVisibility(View.GONE);
    }

    // --------------------------------------------------------- adapter

    private final class DrawerAdapter extends BaseAdapter {
        private static final int TYPE_HEADER = 0;
        private static final int TYPE_APP = 1;
        private static final int TYPE_WEB = 2;

        private final List<Object> items = new ArrayList<>();
        private final Map<String, Integer> sectionPositions = new HashMap<>();
        private List<AppInfo> apps = new ArrayList<>();
        private String query = "";

        void setApps(List<AppInfo> apps) {
            this.apps = apps;
            rebuild();
        }

        void setQuery(String q) {
            query = q;
            rebuild();
        }

        Integer positionForSection(String section) {
            return sectionPositions.get(section);
        }

        AppInfo firstApp() {
            for (Object o : items) if (o instanceof AppInfo) return (AppInfo) o;
            return null;
        }

        private void rebuild() {
            items.clear();
            sectionPositions.clear();
            if (query.isEmpty()) {
                String last = null;
                for (AppInfo a : apps) {
                    if (!a.section.equals(last)) {
                        sectionPositions.put(a.section, items.size());
                        items.add(a.section);
                        last = a.section;
                    }
                    items.add(a);
                }
            } else {
                String q = AppInfo.normalize(query);
                List<AppInfo> prefix = new ArrayList<>();
                List<AppInfo> wordPrefix = new ArrayList<>();
                List<AppInfo> contains = new ArrayList<>();
                for (AppInfo a : apps) {
                    if (a.searchLabel.startsWith(q)) prefix.add(a);
                    else if (a.searchLabel.contains(" " + q)) wordPrefix.add(a);
                    else if (a.searchLabel.contains(q)) contains.add(a);
                }
                items.addAll(prefix);
                items.addAll(wordPrefix);
                items.addAll(contains);
                items.add(new WebSearch(query));
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 3; }

        @Override public int getItemViewType(int position) {
            Object o = items.get(position);
            if (o instanceof String) return TYPE_HEADER;
            if (o instanceof WebSearch) return TYPE_WEB;
            return TYPE_APP;
        }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Object o = items.get(position);
            if (o instanceof String) {
                TextView header = (TextView) convertView;
                if (header == null) {
                    header = new TextView(MainActivity.this);
                    header.setTextColor(ACCENT);
                    header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                    header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    header.setPadding(dp(14), dp(18), 0, dp(4));
                }
                header.setText((String) o);
                return header;
            }
            View row = convertView != null ? convertView : createRow(false);
            if (o instanceof WebSearch) bindWebRow(row, ((WebSearch) o).query);
            else bindRow(row, (AppInfo) o, false);
            return row;
        }
    }

    private static final class WebSearch {
        final String query;

        WebSearch(String query) {
            this.query = query;
        }
    }

    // --------------------------------------------------------- helpers

    private int dp(float v) {
        return Math.round(v * density);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void styleOnWallpaper(TextView tv) {
        tv.setTextColor(Color.WHITE);
        tv.setShadowLayer(dp(4), 0, dp(1), TEXT_SHADOW);
    }

    private static boolean hitsView(View v, MotionEvent e) {
        Rect r = new Rect();
        if (v.getVisibility() != View.VISIBLE || !v.getGlobalVisibleRect(r)) return false;
        return r.contains((int) e.getRawX(), (int) e.getRawY());
    }

    private static boolean hitsChild(ViewGroup group, MotionEvent e) {
        for (int i = 0; i < group.getChildCount(); i++) {
            if (hitsView(group.getChildAt(i), e)) return true;
        }
        return false;
    }
}
