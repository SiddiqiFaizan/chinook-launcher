package app.cascade.launcher;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
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
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity implements AppRepository.Listener,
        AlphabetScrollerView.Listener, NotificationDotService.Listener {

    private static final int MAX_SHORTCUTS = 5;
    private static final int REQUEST_HOME_ROLE = 1;

    private static final int STYLE_HOME = 0;
    private static final int STYLE_PANEL = 1;
    private static final int STYLE_DRAWER = 2;

    private Prefs prefs;
    private Theme theme;
    private AppRepository repo;
    private WidgetController widgets;
    private float density;

    private List<AppInfo> rawApps = new ArrayList<>();
    private List<AppInfo> visibleApps = new ArrayList<>();
    private final Map<String, AppInfo> byKey = new HashMap<>();
    private List<String> favorites = new ArrayList<>();
    private boolean appsLoaded;

    private FrameLayout root;
    private LinearLayout home;
    private View clockBlock;
    private TextView alarmView;
    private HorizontalScrollView worldScroll;
    private LinearLayout worldRow;
    private final List<TextView> worldOffsetViews = new ArrayList<>();
    private ScrollView widgetScroll;
    private LinearLayout widgetContainer;
    private LinearLayout favoritesContainer;
    private View editBar;

    private FrameLayout panelLayer;
    private ScrollView panelScroll;
    private LinearLayout panelList;
    private boolean panelOpen;
    private String panelLetter;
    private View highlightedRow;

    private FrameLayout drawer;
    private LinearLayout drawerContent;
    private EditText search;
    private ListView list;
    private DrawerAdapter adapter;
    private AlphabetScrollerView scroller;

    private boolean drawerOpen;
    private boolean editMode;
    private GestureDetector homeGestures;
    private GestureDetector drawerGestures;
    private boolean gestureStreamIgnored;
    private boolean receiverRegistered;

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateWorldOffsets();
            updateAlarm();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        prefs = new Prefs(this);
        theme = Theme.forId(prefs.theme());
        Fonts.init(this, prefs.bundledFont());
        repo = new AppRepository(this);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        buildUi();
        setContentView(root);
        setupGestures();

        widgets = new WidgetController(this, prefs, theme, widgetContainer);
        widgets.render();
        renderWorldClocks();
        updateAlarm();

        favorites = prefs.favorites();
        repo.start(this);
        NotificationDotService.setListener(this);

        if (!prefs.askedDefault()) {
            prefs.setAskedDefault();
            requestDefaultLauncher();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        widgets.startListening();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(Intent.ACTION_DATE_CHANGED);
        f.addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(timeReceiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(timeReceiver, f);
        receiverRegistered = true;
        updateWorldOffsets();
        updateAlarm();
    }

    @Override protected void onStop() {
        widgets.stopListening();
        if (receiverRegistered) {
            unregisterReceiver(timeReceiver);
            receiverRegistered = false;
        }
        super.onStop();
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
        // Home pressed while already in front: return to a clean home page.
        hidePanel(true);
        if (drawerOpen) closeDrawer(true);
        if (editMode) setEditMode(false);
    }

    @Override public void onBackPressed() {
        if (panelOpen) hidePanel(true);
        else if (drawerOpen) closeDrawer(true);
        else if (editMode) setEditMode(false);
        // A launcher never finishes on back.
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        widgets.onActivityResult(requestCode, resultCode);
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        root = new FrameLayout(this);
        if (theme.solidBackground != 0) {
            root.setBackgroundColor(theme.solidBackground);
        } else {
            root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{theme.scrimTop, mix(theme.scrimTop, theme.scrimBottom), theme.scrimBottom}));
        }

        home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);

        // Clock, date and next alarm.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(8), 0, 0, 0);
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 68);
        clock.setTypeface(Fonts.light);
        clock.setIncludeFontPadding(false);
        styleText(clock, theme.text);
        clock.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                safeStart(new Intent(AlarmClock.ACTION_SHOW_ALARMS));
            }
        });
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, MMMM d");
        date.setFormat24Hour("EEEE, MMMM d");
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        date.setTypeface(Fonts.regular);
        styleText(date, theme.text);
        date.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                safeStart(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR));
            }
        });
        alarmView = new TextView(this);
        alarmView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        alarmView.setTypeface(Fonts.regular);
        alarmView.setCompoundDrawablePadding(dp(6));
        alarmView.setGravity(Gravity.CENTER_VERTICAL);
        styleText(alarmView, theme.textSecondary);
        alarmView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                safeStart(new Intent(AlarmClock.ACTION_SHOW_ALARMS));
            }
        });
        header.addView(clock, wrap());
        LinearLayout.LayoutParams dateLp = wrap();
        dateLp.topMargin = dp(2);
        header.addView(date, dateLp);
        LinearLayout.LayoutParams alarmLp = wrap();
        alarmLp.topMargin = dp(4);
        header.addView(alarmView, alarmLp);
        clockBlock = header;
        LinearLayout.LayoutParams headerLp = wrap();
        headerLp.topMargin = dp(40);
        home.addView(header, headerLp);

        // World clocks.
        worldScroll = new HorizontalScrollView(this);
        worldScroll.setHorizontalScrollBarEnabled(false);
        worldScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        worldRow = new LinearLayout(this);
        worldRow.setOrientation(LinearLayout.HORIZONTAL);
        worldRow.setPadding(dp(8), 0, 0, 0);
        worldScroll.addView(worldRow);
        LinearLayout.LayoutParams worldLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        worldLp.topMargin = dp(22);
        home.addView(worldScroll, worldLp);

        // Widgets fill the flexible middle and scroll if they overflow.
        widgetScroll = new ScrollView(this);
        widgetScroll.setVerticalScrollBarEnabled(false);
        widgetScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        widgetScroll.setClipToPadding(false);
        widgetScroll.setPadding(0, dp(24), 0, dp(8));
        widgetContainer = new LinearLayout(this);
        widgetContainer.setOrientation(LinearLayout.VERTICAL);
        widgetScroll.addView(widgetContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        home.addView(widgetScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Favourites, low on the screen within thumb reach.
        favoritesContainer = new LinearLayout(this);
        favoritesContainer.setOrientation(LinearLayout.VERTICAL);
        home.addView(favoritesContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        editBar = buildEditBar();
        editBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        editLp.topMargin = dp(12);
        home.addView(editBar, editLp);

        home.addView(new View(this), new LinearLayout.LayoutParams(1, dp(44)));
        root.addView(home, match());

        // Letter pop-out: the apps for the letter under your finger.
        panelLayer = new FrameLayout(this);
        panelLayer.setBackgroundColor(theme.panelBackground);
        panelLayer.setVisibility(View.GONE);
        panelLayer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                hidePanel(true);
            }
        });
        panelScroll = new ScrollView(this);
        panelScroll.setVerticalScrollBarEnabled(false);
        panelScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        panelList = new LinearLayout(this);
        panelList.setOrientation(LinearLayout.VERTICAL);
        panelScroll.addView(panelList);
        panelLayer.addView(panelScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        root.addView(panelLayer, match());

        // Full app drawer with search.
        drawer = new FrameLayout(this);
        drawer.setBackgroundColor(theme.drawerBackground);
        drawer.setVisibility(View.GONE);
        drawerContent = new LinearLayout(this);
        drawerContent.setOrientation(LinearLayout.VERTICAL);

        search = new EditText(this);
        search.setHint("Search apps");
        search.setTextColor(theme.text);
        search.setHintTextColor(theme.textSecondary);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        search.setTypeface(Fonts.regular);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setBackground(pill(theme.searchBackground, 0));
        search.setPadding(dp(22), dp(13), dp(22), dp(13));
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

        // The alphabet strip spans the screen so a drag can travel onto the pop-out.
        scroller = new AlphabetScrollerView(this);
        scroller.setColors(theme.text, theme.accent);
        scroller.setTypeface(Fonts.medium);
        scroller.setListener(this);
        root.addView(scroller, match());

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int left = insets.getSystemWindowInsetLeft();
                int top = insets.getSystemWindowInsetTop();
                int right = insets.getSystemWindowInsetRight();
                int bottom = insets.getSystemWindowInsetBottom();
                home.setPadding(dp(20) + left, top, dp(54) + right, bottom);
                panelScroll.setPadding(dp(20) + left, 0, dp(76) + right, 0);
                drawerContent.setPadding(dp(16) + left, top + dp(12), dp(50) + right, 0);
                list.setPadding(0, dp(8), 0, bottom + dp(16));
                scroller.setPadding(0, top + dp(36), right, bottom + dp(36));
                return insets.consumeSystemWindowInsets();
            }
        });
    }

    private View buildEditBar() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(8), 0, 0, 0);
        bar.addView(editButton("+ Apps", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                showFavoritesPicker();
            }
        }));
        bar.addView(editButton("+ Widget", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                widgets.showPicker();
            }
        }));
        bar.addView(editButton("+ Clock", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                showZonePicker();
            }
        }));
        bar.addView(editButton("Done", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                setEditMode(false);
            }
        }));
        scroll.addView(bar);
        return scroll;
    }

    private TextView editButton(String text, boolean primary, View.OnClickListener click) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Fonts.medium);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(9), dp(16), dp(9));
        if (primary) {
            b.setTextColor(theme.solidBackground != 0 || theme.grayscale ? Color.BLACK : 0xFF0E1015);
            b.setBackground(pill(theme.accent, 0));
        } else {
            b.setTextColor(theme.text);
            b.setBackground(pill(0x1AFFFFFF, theme.textSecondary));
        }
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = wrap();
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void setupGestures() {
        homeGestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || editMode) return false;
                float dy = e2.getY() - e1.getY();
                float dx = e2.getX() - e1.getX();
                if (Math.abs(dy) < dp(60) || Math.abs(dy) < Math.abs(dx) * 1.5f) return false;
                if (dy < 0) openDrawer(true);
                else expandNotifications();
                return true;
            }

            @Override public void onLongPress(MotionEvent e) {
                if (hitsChild(favoritesContainer, e) || hitsView(clockBlock, e) || hitsChild(worldRow, e)
                        || hitsChild(widgetContainer, e) || hitsView(editBar, e)) return;
                root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                showHomeMenu();
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
            gestureStreamIgnored = panelOpen
                    || scroller.isInStrip(ev.getX())
                    || (!drawerOpen && widgetContainer.getChildCount() > 0 && hitsChild(widgetContainer, ev));
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

    private void saveFavorites() {
        prefs.setFavorites(favorites);
        renderFavorites();
    }

    private void renderFavorites() {
        favoritesContainer.removeAllViews();
        int shown = 0;
        for (int i = 0; i < favorites.size(); i++) {
            AppInfo a = byKey.get(favorites.get(i));
            if (a == null) continue;
            LinearLayout row = (LinearLayout) createRow(STYLE_HOME);
            bindRow(row, a, STYLE_HOME);
            if (editMode) {
                addEditControls(row, i);
                favoritesContainer.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                favoritesContainer.addView(row, wrap());
            }
            shown++;
        }
        if (shown == 0 && appsLoaded) {
            TextView hint = new TextView(this);
            hint.setText(editMode
                    ? "Tap “+ Apps” to choose your home apps."
                    : "Slide along the letters on the right to find apps.\nLong-press empty space to customise.");
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            hint.setTypeface(Fonts.regular);
            hint.setPadding(dp(8), 0, 0, 0);
            styleText(hint, theme.textSecondary);
            favoritesContainer.addView(hint, wrap());
        }
    }

    private void addEditControls(LinearLayout row, final int index) {
        Row r = (Row) row.getTag();
        r.label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.dot.setVisibility(View.GONE);
        if (index > 0) row.addView(controlButton("↑", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Collections.swap(favorites, index, index - 1);
                saveFavorites();
            }
        }));
        if (index < favorites.size() - 1) row.addView(controlButton("↓", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Collections.swap(favorites, index, index + 1);
                saveFavorites();
            }
        }));
        row.addView(controlButton("✕", new View.OnClickListener() {
            @Override public void onClick(View v) {
                favorites.remove(index);
                saveFavorites();
            }
        }));
    }

    private TextView controlButton(String glyph, View.OnClickListener click) {
        TextView b = new TextView(this);
        b.setText(glyph);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setTypeface(Fonts.medium);
        b.setTextColor(theme.textSecondary);
        b.setGravity(Gravity.CENTER);
        b.setBackground(ripple(dp(20)));
        b.setOnClickListener(click);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        return b;
    }

    private void setEditMode(boolean on) {
        editMode = on;
        editBar.setVisibility(on ? View.VISIBLE : View.GONE);
        renderFavorites();
        renderWorldClocks();
        if (on) Toast.makeText(this, "Long-press a widget or clock to change it", Toast.LENGTH_SHORT).show();
    }

    @Override public void onDotsChanged() {
        refreshDots();
    }

    private void refreshDots() {
        if (!appsLoaded) return;
        renderFavorites();
        adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------ world time

    private void renderWorldClocks() {
        worldRow.removeAllViews();
        worldOffsetViews.clear();
        final List<String> zones = prefs.worldClocks();
        worldScroll.setVisibility(zones.isEmpty() ? View.GONE : View.VISIBLE);
        for (final String zone : zones) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(0, dp(4), dp(10), dp(4));

            TextView city = new TextView(this);
            city.setText(WorldClocks.city(zone).toUpperCase(Locale.getDefault()));
            city.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            city.setLetterSpacing(0.12f);
            city.setTypeface(Fonts.medium);
            styleText(city, theme.textSecondary);

            LinearLayout timeRow = new LinearLayout(this);
            timeRow.setOrientation(LinearLayout.HORIZONTAL);
            TextClock time = new TextClock(this);
            time.setTimeZone(zone);
            time.setFormat12Hour("h:mm a");
            time.setFormat24Hour("H:mm");
            time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
            time.setTypeface(Fonts.regular);
            styleText(time, theme.text);
            TextView offset = new TextView(this);
            offset.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            offset.setTypeface(Fonts.medium);
            offset.setPadding(dp(3), 0, 0, 0);
            styleText(offset, theme.accent);
            offset.setTag(zone);
            worldOffsetViews.add(offset);
            timeRow.addView(time, wrap());
            timeRow.addView(offset, wrap());

            item.addView(city, wrap());
            item.addView(timeRow, wrap());
            item.setBackground(ripple(dp(12)));
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    showWorldClockMenu(zone, v);
                }
            });
            item.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    showWorldClockMenu(zone, v);
                    return true;
                }
            });
            LinearLayout.LayoutParams lp = wrap();
            lp.rightMargin = dp(18);
            worldRow.addView(item, lp);
        }
        updateWorldOffsets();
    }

    private void updateWorldOffsets() {
        for (TextView v : worldOffsetViews) {
            int d = WorldClocks.dayOffset((String) v.getTag());
            v.setText(d > 0 ? "+1" : d < 0 ? "−1" : "");
        }
    }

    private void showWorldClockMenu(final String zone, View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        final List<String> zones = prefs.worldClocks();
        final int index = zones.indexOf(zone);
        menu.getMenu().add(0, 1, 0, WorldClocks.city(zone) + "  ·  " + WorldClocks.gmtOffset(zone)).setEnabled(false);
        if (index > 0) menu.getMenu().add(0, 2, 1, "Move left");
        if (index < zones.size() - 1) menu.getMenu().add(0, 3, 2, "Move right");
        menu.getMenu().add(0, 4, 3, "Add another city");
        menu.getMenu().add(0, 5, 4, "Remove");
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 2: Collections.swap(zones, index, index - 1); break;
                    case 3: Collections.swap(zones, index, index + 1); break;
                    case 4: showZonePicker(); return true;
                    case 5: zones.remove(index); break;
                    default: return true;
                }
                prefs.setWorldClocks(zones);
                renderWorldClocks();
                return true;
            }
        });
        menu.show();
    }

    private void showZonePicker() {
        final List<String> all = WorldClocks.allZones();
        final List<String> shown = new ArrayList<>(all);
        final List<String> labels = new ArrayList<>();
        for (String z : shown) labels.add(zoneLabel(z));
        final ArrayAdapter<String> zoneAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText filter = new EditText(this);
        filter.setHint("Search city or region");
        filter.setSingleLine(true);
        filter.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(filter);
        ListView zoneList = new ListView(this);
        zoneList.setAdapter(zoneAdapter);
        box.addView(zoneList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));

        final AlertDialog dialog = dialog()
                .setTitle("Add world clock")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .create();
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                String q = AppInfo.normalize(s.toString().trim());
                shown.clear();
                labels.clear();
                for (String z : all) {
                    String label = zoneLabel(z);
                    if (q.isEmpty() || AppInfo.normalize(label).contains(q)) {
                        shown.add(z);
                        labels.add(label);
                    }
                }
                zoneAdapter.notifyDataSetChanged();
            }
        });
        zoneList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                List<String> zones = prefs.worldClocks();
                String zone = shown.get(position);
                if (!zones.contains(zone)) zones.add(zone);
                prefs.setWorldClocks(zones);
                renderWorldClocks();
                dialog.dismiss();
            }
        });
        dialog.show();
    }

    private static String zoneLabel(String zone) {
        return WorldClocks.city(zone) + ", " + WorldClocks.region(zone) + "  ·  " + WorldClocks.gmtOffset(zone);
    }

    private void updateAlarm() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        AlarmManager.AlarmClockInfo next = am == null ? null : am.getNextAlarmClock();
        if (next == null || !prefs.showAlarm()) {
            alarmView.setVisibility(View.GONE);
            return;
        }
        String skeleton = DateFormat.is24HourFormat(this) ? "EEEHm" : "EEEhma";
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton);
        alarmView.setText(new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(next.getTriggerTime())));
        Drawable icon = getDrawable(android.R.drawable.ic_lock_idle_alarm);
        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(theme.textSecondary);
            icon.setBounds(0, 0, dp(15), dp(15));
        }
        alarmView.setCompoundDrawablesRelative(icon, null, null, null);
        alarmView.setVisibility(View.VISIBLE);
    }

    // -------------------------------------------------- letter pop-out

    @Override public void onScrubStart() {
        hideKeyboard();
        if (drawerOpen && search.length() > 0) search.setText("");
    }

    @Override public void onLetterSelected(String letter, float y) {
        if (drawerOpen) {
            Integer pos = adapter.positionForSection(letter);
            if (pos != null) list.setSelectionFromTop(pos, 0);
        } else {
            showPanel(letter, y);
        }
    }

    @Override public void onScrubMove(float x, float y, boolean inStrip) {
        if (drawerOpen || !panelOpen) return;
        setHighlight(inStrip ? null : panelRowAt(y));
    }

    @Override public void onScrubEnd(float x, float y, boolean inStrip) {
        if (drawerOpen) return;
        View row = highlightedRow;
        if (row != null) {
            setHighlight(null);
            launch(((Row) row.getTag()).app, row);
        } else if (!inStrip) {
            hidePanel(true);
        }
        // Released on the strip: the pop-out stays open so its apps can be tapped.
    }

    private void showPanel(String letter, float letterY) {
        boolean letterChanged = !letter.equals(panelLetter);
        panelLetter = letter;
        highlightedRow = null;
        if (letterChanged || panelList.getChildCount() == 0) {
            panelList.removeAllViews();
            TextView header = new TextView(this);
            header.setText(letter);
            header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            header.setTypeface(Fonts.medium);
            header.setPadding(dp(8), 0, 0, dp(6));
            header.setTextColor(theme.accent);
            panelList.addView(header, wrap());
            for (AppInfo a : visibleApps) {
                if (!a.section.equals(letter)) continue;
                View row = createRow(STYLE_PANEL);
                bindRow(row, a, STYLE_PANEL);
                panelList.addView(row, wrap());
            }
        }

        // Centre the list on the letter, kept inside the safe area.
        int width = root.getWidth() - panelScroll.getPaddingLeft() - panelScroll.getPaddingRight();
        panelList.measure(View.MeasureSpec.makeMeasureSpec(Math.max(width, 1), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int top = scroller.getPaddingTop();
        int bottom = root.getHeight() - scroller.getPaddingBottom();
        int maxH = Math.max(dp(120), bottom - top);
        int h = Math.min(panelList.getMeasuredHeight(), maxH);
        ViewGroup.LayoutParams lp = panelScroll.getLayoutParams();
        if (lp.height != h) {
            lp.height = h;
            panelScroll.setLayoutParams(lp);
        }
        float ty = Math.max(top, Math.min(bottom - h, letterY - h / 2f));
        panelScroll.setTranslationY(ty);
        panelScroll.scrollTo(0, 0);

        if (letterChanged) {
            for (int i = 0; i < panelList.getChildCount(); i++) {
                View c = panelList.getChildAt(i);
                c.setAlpha(0f);
                c.setTranslationX(dp(28));
                c.animate().alpha(1f).translationX(0).setStartDelay(i * 14L).setDuration(180)
                        .setInterpolator(new DecelerateInterpolator()).start();
            }
        }

        if (!panelOpen) {
            panelOpen = true;
            panelLayer.animate().cancel();
            panelLayer.setVisibility(View.VISIBLE);
            panelLayer.setAlpha(0f);
            panelLayer.animate().alpha(1f).setDuration(160).start();
            home.animate().alpha(0.08f).setDuration(160).start();
        }
    }

    private View panelRowAt(float y) {
        int[] rootPos = new int[2];
        root.getLocationOnScreen(rootPos);
        float rawY = rootPos[1] + y;
        int[] pos = new int[2];
        for (int i = 0; i < panelList.getChildCount(); i++) {
            View c = panelList.getChildAt(i);
            if (!(c.getTag() instanceof Row)) continue;
            c.getLocationOnScreen(pos);
            if (rawY >= pos[1] && rawY < pos[1] + c.getHeight()) return c;
        }
        return null;
    }

    private void setHighlight(View row) {
        if (row == highlightedRow) return;
        if (highlightedRow != null) {
            highlightedRow.animate().translationX(0).setDuration(120).start();
            ((Row) highlightedRow.getTag()).label.setTextColor(theme.text);
        }
        highlightedRow = row;
        if (row != null) {
            row.animate().translationX(dp(12)).setDuration(120).start();
            ((Row) row.getTag()).label.setTextColor(theme.accent);
            row.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    private void hidePanel(boolean animate) {
        if (!panelOpen) return;
        panelOpen = false;
        setHighlight(null);
        panelLetter = null;
        panelLayer.animate().cancel();
        home.animate().cancel();
        if (animate) {
            panelLayer.animate().alpha(0f).setDuration(150).withEndAction(new Runnable() {
                @Override public void run() {
                    panelLayer.setVisibility(View.GONE);
                    panelList.removeAllViews();
                }
            }).start();
            home.animate().alpha(1f).setDuration(180).start();
        } else {
            panelLayer.setVisibility(View.GONE);
            panelList.removeAllViews();
            home.setAlpha(1f);
        }
    }

    // ---------------------------------------------------------- drawer

    private void openDrawer(boolean focusSearch) {
        hidePanel(false);
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

    // --------------------------------------------------------- actions

    private void launch(AppInfo app, View source) {
        try {
            repo.launch(app, source);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Can't open " + app.label, Toast.LENGTH_SHORT).show();
            return;
        }
        if (drawerOpen || panelOpen) {
            root.postDelayed(new Runnable() {
                @Override public void run() {
                    hidePanel(false);
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
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQUEST_HOME_ROLE);
                    return;
                } catch (ActivityNotFoundException ignored) {
                }
            }
        }
        safeStart(new Intent(Settings.ACTION_HOME_SETTINGS));
    }

    private void showAppMenu(final AppInfo app, View anchor) {
        anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
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
            menu.add(1, 2, 101, "Edit home screen");
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
                        saveFavorites();
                        return true;
                    case 2:
                        hidePanel(false);
                        closeDrawer(false);
                        setEditMode(true);
                        return true;
                    case 4:
                        if (!favorites.contains(app.key)) favorites.add(app.key);
                        saveFavorites();
                        Toast.makeText(MainActivity.this, app.label + " added to home", Toast.LENGTH_SHORT).show();
                        return true;
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
                        hidePanel(false);
                        applyApps();
                        Toast.makeText(MainActivity.this, app.label + " hidden. Unhide it in settings.", Toast.LENGTH_SHORT).show();
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

    private void showFavoritesPicker() {
        final List<AppInfo> apps = new ArrayList<>(visibleApps);
        String[] labels = new String[apps.size()];
        final boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = apps.get(i).label;
            checked[i] = favorites.contains(apps.get(i).key);
        }
        dialog()
                .setTitle("Home apps")
                .setMultiChoiceItems(labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface d, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        Set<String> selected = new HashSet<>();
                        for (int i = 0; i < apps.size(); i++) if (checked[i]) selected.add(apps.get(i).key);
                        List<String> next = new ArrayList<>();
                        for (String k : favorites) if (selected.contains(k)) next.add(k);
                        for (AppInfo a : apps) if (selected.contains(a.key) && !next.contains(a.key)) next.add(a.key);
                        favorites = next;
                        saveFavorites();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // -------------------------------------------------------- settings

    private void showHomeMenu() {
        String[] items = {
                "Edit home screen",
                "Add widget",
                "Add world clock",
                "Theme: " + Theme.NAMES[theme.id],
                "Wallpaper",
                "More settings",
        };
        dialog()
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0: setEditMode(true); break;
                            case 1: widgets.showPicker(); break;
                            case 2: showZonePicker(); break;
                            case 3: showThemePicker(); break;
                            case 4:
                                safeStart(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Choose wallpaper"));
                                break;
                            case 5: showSettings(); break;
                        }
                    }
                })
                .show();
    }

    private void showThemePicker() {
        dialog()
                .setTitle("Theme")
                .setSingleChoiceItems(Theme.NAMES, theme.id, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        if (which != theme.id) {
                            prefs.setTheme(which);
                            recreate();
                        }
                    }
                })
                .show();
    }

    private void showSettings() {
        final boolean icons = prefs.showHomeIcons();
        final boolean font = prefs.bundledFont();
        final boolean alarm = prefs.showAlarm();
        final boolean dots = NotificationDotService.isEnabled(this);
        String[] items = {
                "Icons on home: " + (icons ? "on" : "off"),
                "Font: " + (font ? "Manrope" : "System"),
                "Next alarm under date: " + (alarm ? "on" : "off"),
                "Notification dots: " + (dots ? "on" : "off"),
                "Hidden apps",
                "Set as default launcher",
                "About Cascade",
        };
        dialog()
                .setTitle("Settings")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0:
                                prefs.setShowHomeIcons(!icons);
                                renderFavorites();
                                break;
                            case 1:
                                prefs.setBundledFont(!font);
                                recreate();
                                break;
                            case 2:
                                prefs.setShowAlarm(!alarm);
                                updateAlarm();
                                break;
                            case 3:
                                safeStart(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                                break;
                            case 4: showHiddenApps(); break;
                            case 5: requestDefaultLauncher(); break;
                            case 6: showAbout(); break;
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
        dialog()
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
        dialog()
                .setTitle("Cascade Launcher")
                .setMessage("A minimal, open-source launcher.\n\n"
                        + "• Slide along the letters, then onto an app and let go to open it\n"
                        + "• Swipe up to search, swipe down for notifications\n"
                        + "• Long-press an app for shortcuts and options\n"
                        + "• Long-press empty space to edit home, add widgets, clocks or change theme\n\n"
                        + "Font: Manrope (SIL Open Font License).")
                .setPositiveButton("OK", null)
                .show();
    }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
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
            if (editMode) return;
            Row r = (Row) v.getTag();
            if (r.app != null) launch(r.app, v);
            else if (r.webQuery != null) webSearch(r.webQuery);
        }
    };

    private final View.OnLongClickListener rowLongClick = new View.OnLongClickListener() {
        @Override public boolean onLongClick(View v) {
            Row r = (Row) v.getTag();
            if (r.app == null || editMode) return false;
            showAppMenu(r.app, v);
            return true;
        }
    };

    private View createRow(int style) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int vp = dp(style == STYLE_DRAWER ? 7 : style == STYLE_PANEL ? 8 : 9);
        row.setPadding(dp(8), vp, dp(14), vp);
        row.setBackground(ripple(dp(18)));

        Row r = new Row();
        r.icon = new ImageView(this);
        int size = dp(style == STYLE_DRAWER ? 36 : style == STYLE_PANEL ? 34 : 30);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(size, size);
        ip.rightMargin = dp(style == STYLE_DRAWER ? 16 : 18);
        row.addView(r.icon, ip);

        r.label = new TextView(this);
        r.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, style == STYLE_DRAWER ? 18 : style == STYLE_PANEL ? 25 : 23);
        r.label.setTypeface(style == STYLE_DRAWER ? Fonts.regular : Fonts.medium);
        r.label.setSingleLine(true);
        r.label.setEllipsize(TextUtils.TruncateAt.END);
        if (style == STYLE_HOME) styleText(r.label, theme.text);
        else r.label.setTextColor(theme.text);
        row.addView(r.label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        r.dot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(theme.accent);
        r.dot.setBackground(dot);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotLp.leftMargin = dp(10);
        row.addView(r.dot, dotLp);

        row.setTag(r);
        row.setOnClickListener(rowClick);
        row.setOnLongClickListener(rowLongClick);
        return row;
    }

    private void bindRow(View v, AppInfo app, int style) {
        Row r = (Row) v.getTag();
        r.app = app;
        r.webQuery = null;
        r.icon.setImageDrawable(app.icon);
        r.icon.setImageTintList(null);
        r.icon.setColorFilter(theme.grayscale ? Theme.grayscaleFilter() : null);
        r.icon.setVisibility(style == STYLE_HOME && !prefs.showHomeIcons() ? View.GONE : View.VISIBLE);
        r.label.setText(app.label);
        r.label.setTextColor(theme.text);
        r.dot.setVisibility(NotificationDotService.hasDot(app.packageName()) ? View.VISIBLE : View.GONE);
    }

    private void bindWebRow(View v, String query) {
        Row r = (Row) v.getTag();
        r.app = null;
        r.webQuery = query;
        r.icon.setImageDrawable(getDrawable(android.R.drawable.ic_menu_search));
        r.icon.setColorFilter(null);
        r.icon.setImageTintList(ColorStateList.valueOf(theme.text));
        r.icon.setVisibility(View.VISIBLE);
        r.label.setText("Search the web for “" + query + "”");
        r.dot.setVisibility(View.GONE);
    }

    // --------------------------------------------------------- adapter

    private final class DrawerAdapter extends BaseAdapter {
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
            if (o instanceof String) return 0;
            if (o instanceof WebSearch) return 2;
            return 1;
        }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Object o = items.get(position);
            if (o instanceof String) {
                TextView header = (TextView) convertView;
                if (header == null) {
                    header = new TextView(MainActivity.this);
                    header.setTextColor(theme.accent);
                    header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                    header.setTypeface(Fonts.medium);
                    header.setPadding(dp(14), dp(18), 0, dp(4));
                }
                header.setText((String) o);
                return header;
            }
            View row = convertView != null ? convertView : createRow(STYLE_DRAWER);
            if (o instanceof WebSearch) bindWebRow(row, ((WebSearch) o).query);
            else bindRow(row, (AppInfo) o, STYLE_DRAWER);
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

    /** Text drawn straight on the wallpaper gets a soft shadow; on solid black it doesn't need one. */
    private void styleText(TextView tv, int color) {
        tv.setTextColor(color);
        if (theme.solidBackground == 0) tv.setShadowLayer(dp(4), 0, dp(1), 0x99000000);
    }

    private GradientDrawable pill(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(26));
        if (stroke != 0) d.setStroke(Math.max(1, dp(1)), stroke);
        return d;
    }

    private RippleDrawable ripple(int radius) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, mask);
    }

    private static int mix(int a, int b) {
        int alpha = (Color.alpha(a) + Color.alpha(b)) / 2;
        return Color.argb(alpha / 2, 0, 0, 0);
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
