package app.chinook.launcher;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
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
        AlphabetScrollerView.Listener, NotificationDotService.Listener, TopCards.Host {

    private static final int MAX_SHORTCUTS = 5;
    private static final int REQUEST_HOME_ROLE = 1;

    private static final int STYLE_HOME = 0;
    private static final int STYLE_PANEL = 1;
    private static final int STYLE_DRAWER = 2;

    private Prefs prefs;
    private Theme theme;
    private M3 m3;
    private Surfaces.Page settingsPage;
    private LinearLayout settingsColumn;
    /** Set before recreate() (theme/font change) so Settings reopens where the user was. */
    private static boolean reopenSettings;
    /** True while the user is off in system settings granting notification access. */
    private boolean awaitingNotificationAccess;
    private AppRepository repo;
    private WidgetBinder widgetBinder;
    private TopCards topCards;
    private float density;

    private List<AppInfo> rawApps = new ArrayList<>();
    private List<AppInfo> visibleApps = new ArrayList<>();
    private final Map<String, AppInfo> byKey = new HashMap<>();
    private List<String> favorites = new ArrayList<>();
    private boolean appsLoaded;

    private FrameLayout root;
    private LinearLayout home;
    private View clockBlock;
    private TextClock clockView;
    private LinearLayout clockContent;
    private CardPager pager;
    private LinearLayout pagerDots;
    private TextView alarmView;
    private HorizontalScrollView worldScroll;
    private LinearLayout worldRow;
    private final List<TextView> worldOffsetViews = new ArrayList<>();
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
        m3 = M3.of(this, theme);
        repo = new AppRepository(this);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        buildUi();
        setContentView(root);
        setupGestures();

        widgetBinder = new WidgetBinder(this, m3);
        topCards = new TopCards(this, prefs, m3, this, widgetBinder, pager, pagerDots);
        topCards.render();
        renderWorldClocks();
        updateAlarm();

        favorites = prefs.favorites();
        repo.start(this);
        NotificationDotService.setListener(this);

        if (!prefs.askedDefault()) {
            prefs.setAskedDefault();
            requestDefaultLauncher();
        }
        if (prefs.askedDefault() && !prefs.askedDots() && !NotificationDotService.isEnabled(this)) {
            prefs.setAskedDots();
            root.postDelayed(new Runnable() {
                @Override public void run() {
                    showNotificationAccessSheet();
                }
            }, 1200);
        }
        if (reopenSettings) {
            reopenSettings = false;
            root.post(new Runnable() {
                @Override public void run() {
                    showSettings();
                }
            });
        }
    }

    @Override protected void onStart() {
        super.onStart();
        widgetBinder.startListening();
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
        widgetBinder.stopListening();
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
        if (awaitingNotificationAccess) onReturnFromNotificationSettings();
        else ensureListenerConnected();
        refreshDots();
        // Values like notification access or default launcher may have changed in system settings.
        if (settingsPage != null) buildSettings();
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
        widgetBinder.onActivityResult(requestCode, resultCode);
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
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.clockSizeSp());
        clockView = clock;
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
        clockContent = new LinearLayout(this);
        clockContent.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams headerLp = wrap();
        headerLp.topMargin = dp(12);
        clockContent.addView(header, headerLp);

        // World clocks.
        worldScroll = new HorizontalScrollView(this);
        worldScroll.setHorizontalScrollBarEnabled(false);
        worldScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        worldRow = new LinearLayout(this);
        worldRow.setOrientation(LinearLayout.HORIZONTAL);
        worldRow.setPadding(dp(8), 0, 0, 0);
        worldScroll.addView(worldRow);

        // Top slider: clock, widgets and app cards; swipe sideways, loops with 3+ cards.
        pager = new CardPager(this);
        LinearLayout.LayoutParams pagerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(prefs.cardHeightDp()));
        pagerLp.topMargin = dp(20);
        home.addView(pager, pagerLp);
        pagerDots = new LinearLayout(this);
        pagerDots.setOrientation(LinearLayout.HORIZONTAL);
        pagerDots.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(18));
        dotsLp.topMargin = dp(8);
        home.addView(pagerDots, dotsLp);

        // World clocks: their own row, close under the top cards with room to tap either.
        LinearLayout.LayoutParams worldLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        worldLp.topMargin = dp(14);
        home.addView(worldScroll, worldLp);

        home.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

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
        bar.setPadding(dp(8), dp(4), dp(8), dp(4));
        bar.addView(withGap(m3.tonalButton("Apps", R.drawable.ic_add, new View.OnClickListener() {
            @Override public void onClick(View v) {
                showFavoritesPicker();
            }
        })));
        bar.addView(withGap(m3.tonalButton("Top card", R.drawable.ic_view_carousel, new View.OnClickListener() {
            @Override public void onClick(View v) {
                topCards.showAddCard();
            }
        })));
        bar.addView(withGap(m3.tonalButton("Clock", R.drawable.ic_public, new View.OnClickListener() {
            @Override public void onClick(View v) {
                showWorldClocksPage();
            }
        })));
        bar.addView(withGap(m3.filledButton("Done", R.drawable.ic_check, new View.OnClickListener() {
            @Override public void onClick(View v) {
                setEditMode(false);
            }
        })));
        scroll.addView(bar);
        return scroll;
    }

    private View withGap(View v) {
        LinearLayout.LayoutParams lp = wrap();
        lp.rightMargin = dp(8);
        v.setLayoutParams(lp);
        return v;
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
                if (hitsChild(favoritesContainer, e) || hitsView(pager, e) || hitsChild(worldRow, e)
                        || hitsView(editBar, e)) return;
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
                    || (!drawerOpen && topCards.isWidgetCardShowing() && hitsView(pager, ev));
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
        topCards.render();
    }

    private void applyApps() {
        Set<String> hidden = prefs.hidden();
        visibleApps = new ArrayList<>();
        LinkedHashSet<String> sections = new LinkedHashSet<>();
        if (!favoriteApps().isEmpty()) sections.add(AlphabetScrollerView.STAR);
        for (AppInfo a : rawApps) {
            if (hidden.contains(a.key)) continue;
            visibleApps.add(a);
            sections.add(a.section);
        }
        adapter.setApps(visibleApps, favoriteApps(), hiddenApps());
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
        applyApps();
    }

    /** Home-screen favourites, in home order; these also make up the ★ section. */
    private List<AppInfo> favoriteApps() {
        List<AppInfo> out = new ArrayList<>();
        for (String k : favorites) {
            AppInfo a = byKey.get(k);
            if (a != null) out.add(a);
        }
        return out;
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
        r.texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.badge.setVisibility(View.GONE);
        r.preview.setVisibility(View.GONE);
        if (index > 0) row.addView(m3.iconButton(R.drawable.ic_arrow_upward, theme.textSecondary, "Move up",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Collections.swap(favorites, index, index - 1);
                        saveFavorites();
                    }
                }));
        if (index < favorites.size() - 1) row.addView(m3.iconButton(R.drawable.ic_arrow_downward, theme.textSecondary,
                "Move down", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Collections.swap(favorites, index, index + 1);
                        saveFavorites();
                    }
                }));
        row.addView(m3.iconButton(R.drawable.ic_close, theme.textSecondary, "Remove from home",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        favorites.remove(index);
                        saveFavorites();
                    }
                }));
    }

    private void setEditMode(boolean on) {
        editMode = on;
        editBar.setVisibility(on ? View.VISIBLE : View.GONE);
        renderFavorites();
        renderWorldClocks();
        if (on) Toast.makeText(this, "Long-press a top card or clock to change it", Toast.LENGTH_SHORT).show();
    }

    @Override public void onDotsChanged() {
        refreshDots();
    }

    private void refreshDots() {
        if (!appsLoaded) return;
        renderFavorites();
        adapter.notifyDataSetChanged();
        topCards.updateBadges();
        for (int i = 0; i < panelList.getChildCount(); i++) {
            Object tag = panelList.getChildAt(i).getTag();
            if (tag instanceof Row && ((Row) tag).app != null) {
                M3.setBadgeCount(((Row) tag).badge, NotificationDotService.count(((Row) tag).app.packageName()));
            }
        }
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
            item.setPadding(dp(8), dp(6), dp(12), dp(6));
            item.setMinimumHeight(dp(48));

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
                    showWorldClocksPage();
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

    private void showWorldClocksPage() {
        final Surfaces.Page page = new Surfaces.Page(this, m3, "World clocks");
        final LinearLayout column = page.scrollingBody();
        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = new Runnable() {
            @Override public void run() {
                column.removeAllViews();
                final List<String> zones = prefs.worldClocks();
                if (zones.isEmpty()) {
                    TextView empty = m3.text("No world clocks yet.\nAdd a city to see its time on your clock card.",
                            M3.BODY_LARGE, m3.onSurfaceVariant);
                    empty.setPadding(dp(24), dp(24), dp(24), dp(24));
                    column.addView(empty);
                    return;
                }
                column.addView(m3.subheader("On your clock card"));
                for (int i = 0; i < zones.size(); i++) {
                    final int index = i;
                    String zone = zones.get(i);
                    LinearLayout controls = new LinearLayout(MainActivity.this);
                    controls.setOrientation(LinearLayout.HORIZONTAL);
                    if (i > 0) controls.addView(m3.iconButton(R.drawable.ic_arrow_upward, m3.onSurfaceVariant, "Move up",
                            new View.OnClickListener() {
                                @Override public void onClick(View v) {
                                    Collections.swap(zones, index, index - 1);
                                    prefs.setWorldClocks(zones);
                                    renderWorldClocks();
                                    rebuild[0].run();
                                }
                            }));
                    controls.addView(m3.iconButton(R.drawable.ic_delete, m3.onSurfaceVariant, "Remove",
                            new View.OnClickListener() {
                                @Override public void onClick(View v) {
                                    zones.remove(index);
                                    prefs.setWorldClocks(zones);
                                    renderWorldClocks();
                                    rebuild[0].run();
                                }
                            }));
                    column.addView(m3.listItem(0, null, WorldClocks.city(zone),
                            WorldClocks.region(zone) + "  \u00B7  " + WorldClocks.gmtOffset(zone), controls, null));
                }
            }
        };
        page.action(m3.filledButton("Add city", R.drawable.ic_add, new View.OnClickListener() {
            @Override public void onClick(View v) {
                showZonePicker(rebuild[0]);
            }
        }));
        rebuild[0].run();
        page.show();
    }

    private void showZonePicker() {
        showZonePicker(null);
    }

    private void showZonePicker(final Runnable after) {
        final List<String> zones = WorldClocks.allZones();
        List<SearchablePicker.Item> items = new ArrayList<>();
        for (String z : zones) {
            items.add(new SearchablePicker.Item(WorldClocks.city(z),
                    WorldClocks.region(z) + "  \u00B7  " + WorldClocks.gmtOffset(z), null));
        }
        SearchablePicker.single(this, m3, "Add a city", items, "Search city, region or GMT",
                new SearchablePicker.OnPick() {
                    @Override public void onPick(int index) {
                        addWorldClock(zones.get(index));
                        if (after != null) after.run();
                    }
                });
    }

    private void addWorldClock(String zone) {
        List<String> current = prefs.worldClocks();
        if (!current.contains(zone)) current.add(zone);
        prefs.setWorldClocks(current);
        renderWorldClocks();
        Toast.makeText(this, "Added " + WorldClocks.city(zone), Toast.LENGTH_SHORT).show();
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
            boolean star = AlphabetScrollerView.STAR.equals(letter);
            header.setText(star ? "\u2605  Favourites" : letter);
            header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            header.setTypeface(Fonts.medium);
            header.setPadding(dp(8), 0, 0, dp(6));
            header.setTextColor(theme.accent);
            panelList.addView(header, wrap());
            List<AppInfo> source = star ? favoriteApps() : visibleApps;
            for (AppInfo a : source) {
                if (!star && !a.section.equals(letter)) continue;
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

    @Override public void launch(AppInfo app, View source) {
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
                    Toast.makeText(this, "Chinook is already your launcher", Toast.LENGTH_SHORT).show();
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
        if (!shortcuts.isEmpty()) menu.add(0, 9, 50, "Add shortcuts to a top card");
        final int favIndex = favorites.indexOf(app.key);
        if (favIndex >= 0) {
            menu.add(1, 1, 100, "Remove from home");
            menu.add(1, 2, 101, "Edit home screen");
        } else {
            menu.add(1, 4, 100, "Add to home");
        }
        menu.add(2, 5, 200, "App info");
        final boolean isHidden = prefs.hidden().contains(app.key);
        menu.add(2, 6, 201, isHidden ? "Unhide" : "Hide");
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
                    case 9:
                        hidePanel(false);
                        closeDrawer(false);
                        topCards.addShortcutsFrom(app, loadShortcuts(app, 0));
                        return true;
                    case 6:
                        hidePanel(false);
                        setAppHidden(app, !isHidden);
                        Toast.makeText(MainActivity.this, isHidden ? app.label + " is visible again"
                                : app.label + " hidden. Find it under Hidden apps at the end of the app list.",
                                Toast.LENGTH_LONG).show();
                        return true;
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
        return loadShortcuts(app, MAX_SHORTCUTS);
    }

    private List<ShortcutInfo> loadShortcuts(AppInfo app, int limit) {
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
                if (limit > 0 && result.size() >= limit) break;
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
        List<SearchablePicker.Item> items = new ArrayList<>();
        List<Integer> initial = new ArrayList<>();
        Map<String, Integer> indexByKey = new HashMap<>();
        for (int i = 0; i < apps.size(); i++) {
            items.add(new SearchablePicker.Item(apps.get(i).label, null, apps.get(i).icon));
            indexByKey.put(apps.get(i).key, i);
        }
        for (String k : favorites) {
            Integer i = indexByKey.get(k);
            if (i != null) initial.add(i);
        }
        SearchablePicker.multi(this, m3, "Home apps", items, initial, 0, "Search apps", "All apps",
                new SearchablePicker.OnSave() {
                    @Override public void onSave(List<Integer> selectedInOrder) {
                        List<String> next = new ArrayList<>();
                        for (int i : selectedInOrder) next.add(apps.get(i).key);
                        favorites = next;
                        saveFavorites();
                    }
                });
    }

    // -------------------------------------------------------- settings

    private void showHomeMenu() {
        new Surfaces.Sheet(this, m3, null)
                .item(R.drawable.ic_edit, "Edit home screen", "Reorder, add or remove home apps", new Runnable() {
                    @Override public void run() {
                        setEditMode(true);
                    }
                })
                .item(R.drawable.ic_view_carousel, "Add top card", "Widget, app shortcuts or clock", new Runnable() {
                    @Override public void run() {
                        topCards.showAddCard();
                    }
                })
                .item(R.drawable.ic_public, "World clocks", worldClocksSummary(), new Runnable() {
                    @Override public void run() {
                        showWorldClocksPage();
                    }
                })
                .item(R.drawable.ic_palette, "Theme", Theme.NAMES[theme.id], new Runnable() {
                    @Override public void run() {
                        showThemePicker();
                    }
                })
                .item(R.drawable.ic_notifications, "Notification badges",
                        !NotificationDotService.isEnabled(this) ? "Off \u00B7 tap to set up"
                                : NotificationDotService.isConnected() ? "Working" : "Not connected \u00B7 tap to fix",
                        new Runnable() {
                            @Override public void run() {
                                showNotificationAccessSheet();
                            }
                        })
                .item(R.drawable.ic_visibility_off, "Hidden apps", hiddenSummary(), new Runnable() {
                    @Override public void run() {
                        showHiddenApps();
                    }
                })
                .item(R.drawable.ic_lock, "Lock screen", "Minimal wallpaper designs", new Runnable() {
                    @Override public void run() {
                        new LockScreenDesigner(MainActivity.this, prefs, m3).show();
                    }
                })
                .item(R.drawable.ic_settings, "Settings", null, new Runnable() {
                    @Override public void run() {
                        showSettings();
                    }
                })
                .show();
    }

    private String worldClocksSummary() {
        List<String> zones = prefs.worldClocks();
        if (zones.isEmpty()) return "None added";
        List<String> cities = new ArrayList<>();
        for (String z : zones) cities.add(WorldClocks.city(z));
        return TextUtils.join(", ", cities);
    }

    private void showThemePicker() {
        final Surfaces.Sheet sheet = new Surfaces.Sheet(this, m3, "Theme");
        String[] supporting = {
                "Your wallpaper with Material You colours",
                "Dimmed wallpaper with muted greys",
                "Pure black, greyscale icons and widgets",
        };
        int[] swatch = {theme.id == Theme.COLORED ? m3.primary : 0xFF8AB4FF, 0xFF5F6368, 0xFFFFFFFF};
        for (int i = 0; i < Theme.NAMES.length; i++) {
            final int id = i;
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(i == Theme.MONO ? Color.BLACK : swatch[i]);
            if (i == Theme.MONO) dot.setStroke(dp(2), swatch[i]);
            sheet.add(m3.listItem(0, dot, Theme.NAMES[i], supporting[i], m3.radio(i == theme.id),
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            sheet.dismissThen(new Runnable() {
                                @Override public void run() {
                                    if (id == theme.id) return;
                                    prefs.setTheme(id);
                                    reopenSettings = settingsPage != null;
                                    recreate();
                                }
                            });
                        }
                    }));
        }
        sheet.show();
    }

    private void showFontPicker() {
        final Surfaces.Sheet sheet = new Surfaces.Sheet(this, m3, "Font");
        final boolean bundled = prefs.bundledFont();
        String[] names = {"Manrope", "System default"};
        String[] supporting = {"Clean geometric sans", "Your phone's own font"};
        for (int i = 0; i < 2; i++) {
            final boolean choice = i == 0;
            sheet.add(m3.listItem(R.drawable.ic_text_fields, null, names[i], supporting[i], m3.radio(choice == bundled),
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            sheet.dismissThen(new Runnable() {
                                @Override public void run() {
                                    if (choice == bundled) return;
                                    prefs.setBundledFont(choice);
                                    reopenSettings = settingsPage != null;
                                    recreate();
                                }
                            });
                        }
                    }));
        }
        sheet.show();
    }

    private static final int SIZE_LABEL = 0;
    private static final int SIZE_ICON = 1;
    private static final int SIZE_CLOCK = 2;
    private static final String[] SIZE_NAMES = {"Small", "Default", "Large", "Extra large"};
    private static final int[] LABEL_SIZES = {19, 23, 27, 31};
    private static final int[] ICON_SIZES = {24, 30, 38, 46};
    private static final int[] CLOCK_SIZES = {52, 68, 84, 100};

    private static String sizeName(int[] sizes, int value) {
        for (int i = 0; i < sizes.length; i++) if (sizes[i] == value) return SIZE_NAMES[i];
        return "Custom";
    }

    /** Size chooser: every option shows a live preview and its exact size. */
    private void showSizePicker(final int which) {
        final int[] sizes = which == SIZE_LABEL ? LABEL_SIZES : which == SIZE_ICON ? ICON_SIZES : CLOCK_SIZES;
        final int current = which == SIZE_LABEL ? prefs.labelSizeSp()
                : which == SIZE_ICON ? prefs.iconSizeDp() : prefs.clockSizeSp();
        String title = which == SIZE_LABEL ? "Text size" : which == SIZE_ICON ? "Icon size" : "Clock size";
        final Surfaces.Sheet sheet = new Surfaces.Sheet(this, m3, title);
        AppInfo sample = favoriteApps().isEmpty() ? (visibleApps.isEmpty() ? null : visibleApps.get(0))
                : favoriteApps().get(0);
        for (int i = 0; i < sizes.length; i++) {
            final int size = sizes[i];
            String unit = which == SIZE_ICON ? " dp" : " sp";
            LinearLayout row = m3.listItem(0, null, SIZE_NAMES[i], size + unit + (i == 1 ? " \u00B7 default" : ""),
                    m3.radio(size == current), new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            sheet.dismissThen(new Runnable() {
                                @Override public void run() {
                                    applySize(which, size);
                                }
                            });
                        }
                    });
            View preview;
            if (which == SIZE_ICON) {
                ImageView icon = new ImageView(this);
                icon.setImageDrawable(sample != null ? sample.icon : getDrawable(R.mipmap.ic_launcher));
                if (theme.grayscale) icon.setColorFilter(Theme.grayscaleFilter());
                icon.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
                preview = icon;
            } else {
                TextView t = new TextView(this);
                t.setText(which == SIZE_LABEL ? (sample != null ? sample.label : "Messages") : "9:41");
                t.setTextColor(m3.onSurface);
                t.setTypeface(which == SIZE_LABEL ? Fonts.medium : Fonts.light);
                // The clock is shown at half scale so every option fits on screen.
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, which == SIZE_CLOCK ? size / 2f : size);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setLayoutParams(new LinearLayout.LayoutParams(dp(which == SIZE_LABEL ? 150 : 110),
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                preview = t;
            }
            ((LinearLayout.LayoutParams) preview.getLayoutParams()).rightMargin = dp(16);
            ((LinearLayout.LayoutParams) preview.getLayoutParams()).gravity = Gravity.CENTER_VERTICAL;
            row.addView(preview, 0);
            sheet.add(row);
        }
        sheet.show();
    }

    private void applySize(int which, int size) {
        if (which == SIZE_LABEL) prefs.setLabelSizeSp(size);
        else if (which == SIZE_ICON) prefs.setIconSizeDp(size);
        else prefs.setClockSizeSp(size);
        clockView.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.clockSizeSp());
        renderFavorites();
        // Drop recycled rows so the drawer picks up the new sizes.
        list.setAdapter(adapter);
        if (settingsPage != null) buildSettings();
    }

    /**
     * Step-by-step guide to notification access. It's a special access (not a runtime permission),
     * so it never appears under App info → Permissions, and on Android 13+ apps installed from an APK
     * must first be allowed "restricted settings". The guide reopens on return until access is on.
     */
    private void showNotificationAccessSheet() {
        final Surfaces.Sheet sheet = new Surfaces.Sheet(this, m3, "Turn on notification badges");
        TextView intro = m3.text("Chinook needs notification access to show unread counts on your apps and the newest "
                + "message under home screen apps. Nothing leaves your phone.", M3.BODY_MEDIUM, m3.onSurfaceVariant);
        intro.setPadding(dp(24), 0, dp(24), dp(8));
        sheet.add(intro);

        // Live status, so it's clear which step is left (or which link is broken).
        boolean granted = NotificationDotService.isEnabled(this);
        boolean connected = granted && NotificationDotService.isConnected();
        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setBackground(m3.shape(connected ? m3.primaryContainer : m3.surfaceContainerHighest, 16));
        status.setPadding(dp(16), dp(12), dp(16), dp(12));
        android.widget.ImageView statusIcon = m3.iconView(connected ? R.drawable.ic_check : R.drawable.ic_info,
                connected ? m3.onPrimaryContainer : m3.onSurfaceVariant, 20);
        ((LinearLayout.LayoutParams) statusIcon.getLayoutParams()).rightMargin = dp(12);
        status.addView(statusIcon);
        int seen = NotificationDotService.seenNotifications();
        int apps = NotificationDotService.appsWithBadges();
        String working = seen == 0
                ? "Working. No notifications right now; badges appear as soon as one arrives."
                : "Working. Chinook can read " + seen + (seen == 1 ? " notification" : " notifications")
                        + ", with badges on " + apps + (apps == 1 ? " app." : " apps.");
        status.addView(m3.text(connected ? working : granted
                        ? "Access is on, but Android hasn't connected Chinook's notification reader yet. "
                                + "Tap Reconnect below."
                        : "Notification access is still off. Allowing restricted settings only unlocks the "
                                + "switch \u2014 you still need to turn Chinook on in step " + (Build.VERSION.SDK_INT >= 33 ? 2 : 1) + ".",
                M3.BODY_MEDIUM, connected ? m3.onPrimaryContainer : m3.onSurface),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = slp.rightMargin = dp(16);
        slp.bottomMargin = dp(8);
        status.setLayoutParams(slp);
        sheet.add(status);
        if (granted && !connected) {
            TextView reconnect = m3.filledButton("Reconnect", 0, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    sheet.dismissThen(new Runnable() {
                        @Override public void run() {
                            reconnectListener(true);
                        }
                    });
                }
            });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.leftMargin = dp(16);
            rlp.bottomMargin = dp(8);
            reconnect.setLayoutParams(rlp);
            sheet.add(reconnect);
        }

        int step = 1;
        if (Build.VERSION.SDK_INT >= 33) {
            sheet.add(m3.listItem(R.drawable.ic_lock, null, step++ + ". Allow restricted settings",
                    "Needed if Android shows \u201CRestricted setting\u201D. In App info, tap \u22EE in the top corner, "
                            + "then \u201CAllow restricted settings\u201D.",
                    m3.tonalButton("App info", 0, new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            sheet.dismissThen(new Runnable() {
                                @Override public void run() {
                                    awaitingNotificationAccess = true;
                                    safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", getPackageName(), null)));
                                }
                            });
                        }
                    }), null));
        }
        sheet.add(m3.listItem(R.drawable.ic_notifications, null, step + ". Turn on notification access",
                "Switch on Chinook, confirm, then come back here.",
                m3.filledButton("Open", 0, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        sheet.dismissThen(new Runnable() {
                            @Override public void run() {
                                openNotificationAccessSettings();
                            }
                        });
                    }
                }), null));

        TextView note = m3.text("This is a special access, so App info \u2192 Permissions will still say "
                + "\u201CNo permissions requested\u201D. That's normal. Check it in Settings \u2192 Apps \u2192 "
                + "Special app access \u2192 Notification access.\n\nThe \u201CAllow notifications\u201D switch under App "
                + "info \u2192 Notifications is different: it lets Chinook post its own notifications, which it never "
                + "does, so it can stay off.", M3.BODY_MEDIUM, m3.onSurfaceVariant);
        note.setPadding(dp(24), dp(12), dp(24), dp(4));
        sheet.add(note);

        sheet.action(m3.textButton("Not now", new View.OnClickListener() {
            @Override public void onClick(View v) {
                awaitingNotificationAccess = false;
                sheet.dismiss();
            }
        }));
        sheet.show();
    }

    /** Opens Chinook's own notification-access switch (Android 11+), or the full list. */
    private void openNotificationAccessSettings() {
        awaitingNotificationAccess = true;
        String component = new ComponentName(this, NotificationDotService.class).flattenToString();
        if (Build.VERSION.SDK_INT >= 30) {
            Intent detail = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
            detail.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component);
            if (safeStart(detail)) return;
        }
        safeStart(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }

    private long lastReconnectAttempt;

    /** If access is granted but the listener isn't running, reconnect it (at most once a minute). */
    private void ensureListenerConnected() {
        if (!NotificationDotService.isEnabled(this) || NotificationDotService.isConnected()) return;
        if (System.currentTimeMillis() - lastReconnectAttempt < 60_000) return;
        reconnectListener(false);
    }

    /**
     * Asks Android to bind the listener; if it still isn't connected shortly after, disables and
     * re-enables the service component, which forces a fresh bind (the same as switching access off
     * and on). Needed after installs/updates, when Android sometimes doesn't bind the listener.
     */
    private void reconnectListener(final boolean report) {
        lastReconnectAttempt = System.currentTimeMillis();
        final ComponentName component = new ComponentName(this, NotificationDotService.class);
        try {
            android.service.notification.NotificationListenerService.requestRebind(component);
        } catch (RuntimeException ignored) {
        }
        if (report) Toast.makeText(this, "Reconnecting\u2026", Toast.LENGTH_SHORT).show();
        root.postDelayed(new Runnable() {
            @Override public void run() {
                if (!NotificationDotService.isConnected()) {
                    PackageManager pm = getPackageManager();
                    try {
                        pm.setComponentEnabledSetting(component,
                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
                        pm.setComponentEnabledSetting(component,
                                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
                    } catch (RuntimeException ignored) {
                    }
                }
                root.postDelayed(new Runnable() {
                    @Override public void run() {
                        boolean ok = NotificationDotService.isConnected();
                        refreshDots();
                        if (settingsPage != null) buildSettings();
                        if (!report) return;
                        Toast.makeText(MainActivity.this, ok ? "Connected. Badges are working."
                                : "Still not connected. Try switching Chinook's notification access off and on, "
                                        + "or restart your phone.", Toast.LENGTH_LONG).show();
                    }
                }, 2500);
            }
        }, 2000);
    }

    private void onReturnFromNotificationSettings() {
        awaitingNotificationAccess = false;
        if (NotificationDotService.isEnabled(this)) {
            ensureListenerConnected();
            Toast.makeText(this, "Notification badges are on", Toast.LENGTH_SHORT).show();
            if (settingsPage != null) buildSettings();
        } else {
            // Not on yet (e.g. after allowing restricted settings): show the next step again.
            root.postDelayed(new Runnable() {
                @Override public void run() {
                    showNotificationAccessSheet();
                }
            }, 350);
        }
    }

    private interface Toggle {
        void set(boolean on);
    }

    private View switchItem(int icon, String title, String supporting, boolean checked, final Toggle toggle) {
        final android.widget.Switch sw = m3.switchView(checked);
        return m3.listItem(icon, null, title, supporting, sw, new View.OnClickListener() {
            @Override public void onClick(View v) {
                sw.setChecked(!sw.isChecked());
                toggle.set(sw.isChecked());
            }
        });
    }

    private static View.OnClickListener run(final Runnable r) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                r.run();
            }
        };
    }

    private void showSettings() {
        settingsPage = new Surfaces.Page(this, m3, "Settings");
        settingsColumn = settingsPage.scrollingBody();
        settingsPage.onClose(new Runnable() {
            @Override public void run() {
                settingsPage = null;
            }
        });
        buildSettings();
        settingsPage.show();
    }

    private void buildSettings() {
        LinearLayout c = settingsColumn;
        c.removeAllViews();

        c.addView(m3.subheader("Appearance"));
        c.addView(m3.listItem(R.drawable.ic_palette, null, "Theme", Theme.NAMES[theme.id], null, run(new Runnable() {
            @Override public void run() {
                showThemePicker();
            }
        })));
        c.addView(m3.listItem(R.drawable.ic_text_fields, null, "Font",
                prefs.bundledFont() ? "Manrope" : "System default", null, run(new Runnable() {
                    @Override public void run() {
                        showFontPicker();
                    }
                })));
        c.addView(m3.listItem(R.drawable.ic_text_fields, null, "Text size",
                sizeName(LABEL_SIZES, prefs.labelSizeSp()) + " \u00B7 " + prefs.labelSizeSp() + " sp", null,
                run(new Runnable() {
                    @Override public void run() {
                        showSizePicker(SIZE_LABEL);
                    }
                })));
        c.addView(m3.listItem(R.drawable.ic_apps, null, "Icon size",
                sizeName(ICON_SIZES, prefs.iconSizeDp()) + " \u00B7 " + prefs.iconSizeDp() + " dp", null,
                run(new Runnable() {
                    @Override public void run() {
                        showSizePicker(SIZE_ICON);
                    }
                })));
        c.addView(m3.listItem(R.drawable.ic_schedule, null, "Clock size",
                sizeName(CLOCK_SIZES, prefs.clockSizeSp()) + " \u00B7 " + prefs.clockSizeSp() + " sp", null,
                run(new Runnable() {
                    @Override public void run() {
                        showSizePicker(SIZE_CLOCK);
                    }
                })));
        c.addView(switchItem(R.drawable.ic_apps, "Icons on home screen", "Show app icons next to names",
                prefs.showHomeIcons(), new Toggle() {
                    @Override public void set(boolean on) {
                        prefs.setShowHomeIcons(on);
                        renderFavorites();
                    }
                }));

        c.addView(m3.subheader("Home screen"));
        c.addView(m3.listItem(R.drawable.ic_edit, null, "Edit home screen", "Reorder, add or remove home apps",
                null, run(new Runnable() {
                    @Override public void run() {
                        settingsPage.close();
                        setEditMode(true);
                    }
                })));
        c.addView(m3.listItem(R.drawable.ic_view_carousel, null, "Add top card", "Widget, app shortcuts or clock",
                null, run(new Runnable() {
                    @Override public void run() {
                        topCards.showAddCard();
                    }
                })));
        c.addView(m3.listItem(R.drawable.ic_height, null, "Top card height", prefs.cardHeightDp() + " dp",
                null, run(new Runnable() {
                    @Override public void run() {
                        topCards.showHeight(new Runnable() {
                            @Override public void run() {
                                if (settingsPage != null) buildSettings();
                            }
                        });
                    }
                })));
        c.addView(m3.listItem(R.drawable.ic_public, null, "World clocks", worldClocksSummary(), null, run(new Runnable() {
            @Override public void run() {
                showWorldClocksPage();
            }
        })));
        c.addView(switchItem(R.drawable.ic_alarm, "Next alarm", "Show your next alarm under the date",
                prefs.showAlarm(), new Toggle() {
                    @Override public void set(boolean on) {
                        prefs.setShowAlarm(on);
                        updateAlarm();
                    }
                }));

        c.addView(m3.subheader("Apps"));
        c.addView(m3.listItem(R.drawable.ic_visibility_off, null, "Hidden apps", hiddenSummary(), null, run(new Runnable() {
                    @Override public void run() {
                        showHiddenApps();
                    }
                })));
        c.addView(switchItem(R.drawable.ic_notifications, "Message previews",
                "Show the newest notification under home screen apps", prefs.messagePreviews(), new Toggle() {
                    @Override public void set(boolean on) {
                        prefs.setMessagePreviews(on);
                        renderFavorites();
                    }
                }));
        final boolean badgesOn = NotificationDotService.isEnabled(this);
        c.addView(m3.listItem(R.drawable.ic_notifications, null, "Notification badges",
                badgesOn ? (NotificationDotService.isConnected()
                        ? "Working \u00B7 reading " + NotificationDotService.seenNotifications() + " notifications"
                        : "Access on, not connected \u00B7 tap to fix") : "Off \u00B7 tap to set up", null,
                run(new Runnable() {
                    @Override public void run() {
                        showNotificationAccessSheet();
                    }
                })));

        c.addView(m3.subheader("Wallpaper & lock screen"));
        c.addView(m3.listItem(R.drawable.ic_lock, null, "Lock screen", "Minimal designs for Colored, Dark and Black & White",
                null, run(new Runnable() {
                    @Override public void run() {
                        new LockScreenDesigner(MainActivity.this, prefs, m3).show();
                    }
                })));
        c.addView(m3.listItem(R.drawable.ic_wallpaper, null, "Wallpaper", "Choose a photo or system wallpaper",
                null, run(new Runnable() {
                    @Override public void run() {
                        safeStart(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Choose wallpaper"));
                    }
                })));

        c.addView(m3.subheader("System"));
        c.addView(m3.listItem(R.drawable.ic_home, null, "Default home app",
                isDefaultLauncher() ? "Chinook is your home app" : "Tap to make Chinook your home app", null,
                run(new Runnable() {
                    @Override public void run() {
                        requestDefaultLauncher();
                    }
                })));
        c.addView(m3.listItem(R.drawable.ic_info, null, "About Chinook", "Version " + versionName(), null,
                run(new Runnable() {
                    @Override public void run() {
                        showAbout();
                    }
                })));
    }

    private boolean isDefaultLauncher() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager rm = getSystemService(RoleManager.class);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) return rm.isRoleHeld(RoleManager.ROLE_HOME);
        }
        ResolveInfo ri = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private List<AppInfo> hiddenApps() {
        Set<String> hidden = prefs.hidden();
        List<AppInfo> out = new ArrayList<>();
        for (AppInfo a : rawApps) if (hidden.contains(a.key)) out.add(a);
        return out;
    }

    private String hiddenSummary() {
        int n = hiddenApps().size();
        return n == 0 ? "None \u00B7 hide apps you rarely use" : n + (n == 1 ? " app" : " apps") + " \u00B7 tap to unhide";
    }

    private void setAppHidden(AppInfo app, boolean hide) {
        Set<String> hidden = prefs.hidden();
        if (hide) {
            hidden.add(app.key);
            favorites.remove(app.key);
            prefs.setFavorites(favorites);
        } else {
            hidden.remove(app.key);
        }
        prefs.setHidden(hidden);
        applyApps();
        if (settingsPage != null) buildSettings();
    }

    /** Lists hidden apps with an Unhide button each, plus a way to hide more. */
    private void showHiddenApps() {
        final Surfaces.Page page = new Surfaces.Page(this, m3, "Hidden apps");
        final LinearLayout column = page.scrollingBody();
        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = new Runnable() {
            @Override public void run() {
                column.removeAllViews();
                List<AppInfo> hidden = hiddenApps();
                if (hidden.isEmpty()) {
                    TextView empty = m3.text("No hidden apps.\n\nHidden apps are left out of the alphabet and the app "
                            + "list, but still turn up when you search for them by name.", M3.BODY_LARGE, m3.onSurfaceVariant);
                    empty.setPadding(dp(24), dp(24), dp(24), dp(24));
                    column.addView(empty);
                    return;
                }
                column.addView(m3.subheader(hidden.size() + (hidden.size() == 1 ? " hidden app" : " hidden apps")));
                for (final AppInfo a : hidden) {
                    TextView unhide = m3.tonalButton("Unhide", 0, new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            setAppHidden(a, false);
                            Toast.makeText(MainActivity.this, a.label + " is visible again", Toast.LENGTH_SHORT).show();
                            rebuild[0].run();
                        }
                    });
                    column.addView(m3.listItem(0, a.icon, a.label, null, unhide, null));
                }
                TextView note = m3.text("Hidden apps still turn up when you search for them by name.",
                        M3.BODY_MEDIUM, m3.onSurfaceVariant);
                note.setPadding(dp(16), dp(16), dp(16), 0);
                column.addView(note);
            }
        };
        page.action(m3.filledButton("Hide apps", R.drawable.ic_add, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickAppsToHide(rebuild[0]);
            }
        }));
        rebuild[0].run();
        page.show();
    }

    private void pickAppsToHide(final Runnable after) {
        final List<AppInfo> apps = new ArrayList<>(visibleApps);
        List<SearchablePicker.Item> items = new ArrayList<>();
        for (AppInfo a : apps) items.add(new SearchablePicker.Item(a.label, null, a.icon));
        SearchablePicker.multi(this, m3, "Hide apps", items, null, 0, "Search apps", "All apps",
                new SearchablePicker.OnSave() {
                    @Override public void onSave(List<Integer> selectedInOrder) {
                        if (selectedInOrder.isEmpty()) return;
                        Set<String> hidden = prefs.hidden();
                        for (int i : selectedInOrder) {
                            hidden.add(apps.get(i).key);
                            favorites.remove(apps.get(i).key);
                        }
                        prefs.setHidden(hidden);
                        prefs.setFavorites(favorites);
                        applyApps();
                        if (settingsPage != null) buildSettings();
                        after.run();
                    }
                });
    }

    private void showAbout() {
        Surfaces.Sheet sheet = new Surfaces.Sheet(this, m3, "Chinook Launcher");
        TextView body = m3.text("A minimal, open-source launcher.\n\n"
                + "•  Slide along the letters, then onto an app and let go to open it. ★ shows your home apps.\n"
                + "•  Swipe the top cards sideways for your clock, widgets and app shortcuts.\n"
                + "•  Swipe up to search, swipe down for notifications.\n"
                + "•  Long-press an app, a card or empty space for options.\n\n"
                + "Version " + versionName() + "  ·  Font: Manrope (SIL OFL)  ·  Icons: Material Icons (Apache 2.0)",
                M3.BODY_MEDIUM, m3.onSurfaceVariant);
        body.setPadding(dp(24), 0, dp(24), dp(8));
        sheet.add(body);
        sheet.show();
    }

    // ------------------------------------------------------- top cards

    @Override public AppInfo findApp(String key) {
        return byKey.get(key);
    }

    @Override public List<AppInfo> visibleApps() {
        return visibleApps;
    }

    @Override public void onAppLongPress(AppInfo app, View anchor) {
        showAppMenu(app, anchor);
    }

    @Override public View clockContent() {
        return clockContent;
    }

    // ------------------------------------------------------------ rows

    private static final class Row {
        ImageView icon;
        LinearLayout texts;
        TextView label;
        TextView preview;
        TextView badge;
        AppInfo app;
        String webQuery;
        Runnable action;
    }

    private final View.OnClickListener rowClick = new View.OnClickListener() {
        @Override public void onClick(View v) {
            if (editMode) return;
            Row r = (Row) v.getTag();
            if (r.action != null) r.action.run();
            else if (r.app != null) launch(r.app, v);
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

    private float labelSizeSp(int style) {
        int home = prefs.labelSizeSp();
        if (style == STYLE_PANEL) return home + 2;
        if (style == STYLE_DRAWER) return Math.max(15, Math.round(home * 0.78f));
        return home;
    }

    private int iconSizeDp(int style) {
        int home = prefs.iconSizeDp();
        if (style == STYLE_PANEL) return home + 4;
        if (style == STYLE_DRAWER) return home + 6;
        return home;
    }

    private View createRow(int style) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int vp = dp(style == STYLE_DRAWER ? 7 : style == STYLE_PANEL ? 8 : 9);
        row.setPadding(dp(8), vp, dp(14), vp);
        row.setBackground(ripple(dp(18)));

        Row r = new Row();
        r.icon = new ImageView(this);
        int size = dp(iconSizeDp(style));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(size, size);
        ip.rightMargin = dp(style == STYLE_DRAWER ? 16 : 18);
        row.addView(r.icon, ip);

        r.texts = new LinearLayout(this);
        r.texts.setOrientation(LinearLayout.VERTICAL);
        r.label = new TextView(this);
        r.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSizeSp(style));
        r.label.setTypeface(style == STYLE_DRAWER ? Fonts.regular : Fonts.medium);
        r.label.setSingleLine(true);
        r.label.setEllipsize(TextUtils.TruncateAt.END);
        if (style == STYLE_HOME) styleText(r.label, theme.text);
        else r.label.setTextColor(theme.text);
        r.texts.addView(r.label);
        r.preview = new TextView(this);
        r.preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(13, labelSizeSp(style) * 0.6f));
        r.preview.setTypeface(Fonts.regular);
        r.preview.setSingleLine(true);
        r.preview.setEllipsize(TextUtils.TruncateAt.END);
        r.preview.setMaxWidth(dp(260));
        if (style == STYLE_HOME) styleText(r.preview, theme.textSecondary);
        else r.preview.setTextColor(theme.textSecondary);
        r.preview.setVisibility(View.GONE);
        r.texts.addView(r.preview);
        row.addView(r.texts, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        r.badge = m3.badge();
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(16));
        bp.leftMargin = dp(10);
        bp.gravity = Gravity.CENTER_VERTICAL;
        row.addView(r.badge, bp);
        r.badge.setVisibility(View.GONE);

        row.setTag(r);
        row.setOnClickListener(rowClick);
        row.setOnLongClickListener(rowLongClick);
        return row;
    }

    private void bindRow(View v, AppInfo app, int style) {
        Row r = (Row) v.getTag();
        r.app = app;
        r.webQuery = null;
        r.action = null;
        r.icon.setImageDrawable(app.icon);
        r.icon.setImageTintList(null);
        r.icon.setColorFilter(theme.grayscale ? Theme.grayscaleFilter() : null);
        r.icon.setVisibility(style == STYLE_HOME && !prefs.showHomeIcons() ? View.GONE : View.VISIBLE);
        r.label.setText(app.label);
        r.label.setTextColor(theme.text);
        M3.setBadgeCount(r.badge, NotificationDotService.count(app.packageName()));
        // Home screen apps show the newest message under their name, like a quiet inbox.
        String preview = style == STYLE_HOME && prefs.messagePreviews() && !editMode
                ? NotificationDotService.preview(app.packageName()) : null;
        r.preview.setText(preview);
        r.preview.setVisibility(preview == null ? View.GONE : View.VISIBLE);
    }

    private void bindHiddenEntryRow(View v, int count) {
        Row r = (Row) v.getTag();
        r.app = null;
        r.webQuery = null;
        r.action = new Runnable() {
            @Override public void run() {
                showHiddenApps();
            }
        };
        r.icon.setImageDrawable(m3.icon(R.drawable.ic_visibility_off, theme.textSecondary));
        r.icon.setColorFilter(null);
        r.icon.setImageTintList(null);
        r.icon.setVisibility(View.VISIBLE);
        r.label.setText("Hidden apps");
        r.preview.setText(count + (count == 1 ? " app \u00B7 tap to unhide" : " apps \u00B7 tap to unhide"));
        r.preview.setVisibility(View.VISIBLE);
        r.badge.setVisibility(View.GONE);
    }

    private void bindWebRow(View v, String query) {
        Row r = (Row) v.getTag();
        r.app = null;
        r.webQuery = query;
        r.action = null;
        r.icon.setImageDrawable(getDrawable(android.R.drawable.ic_menu_search));
        r.icon.setColorFilter(null);
        r.icon.setImageTintList(ColorStateList.valueOf(theme.text));
        r.icon.setVisibility(View.VISIBLE);
        r.label.setText("Search the web for “" + query + "”");
        r.badge.setVisibility(View.GONE);
        r.preview.setVisibility(View.GONE);
    }

    // --------------------------------------------------------- adapter

    private final class DrawerAdapter extends BaseAdapter {
        private final List<Object> items = new ArrayList<>();
        private final Map<String, Integer> sectionPositions = new HashMap<>();
        private List<AppInfo> apps = new ArrayList<>();
        private List<AppInfo> favs = new ArrayList<>();
        private List<AppInfo> hidden = new ArrayList<>();
        private final Set<String> hiddenKeys = new HashSet<>();
        private String query = "";

        void setApps(List<AppInfo> apps, List<AppInfo> favs, List<AppInfo> hidden) {
            this.apps = apps;
            this.favs = favs;
            this.hidden = hidden;
            hiddenKeys.clear();
            for (AppInfo a : hidden) hiddenKeys.add(a.key);
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
                if (!favs.isEmpty()) {
                    sectionPositions.put(AlphabetScrollerView.STAR, 0);
                    items.add(AlphabetScrollerView.STAR + "  Favourites");
                    items.addAll(favs);
                }
                String last = null;
                for (AppInfo a : apps) {
                    if (!a.section.equals(last)) {
                        sectionPositions.put(a.section, items.size());
                        items.add(a.section);
                        last = a.section;
                    }
                    items.add(a);
                }
                // A way back to hidden apps, at the very end of the list.
                if (!hidden.isEmpty()) items.add(new HiddenEntry(hidden.size()));
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
                // Hidden apps still turn up when searched for by name, dimmed, under their own header.
                List<AppInfo> hiddenMatches = new ArrayList<>();
                for (AppInfo a : hidden) if (a.searchLabel.contains(q)) hiddenMatches.add(a);
                if (!hiddenMatches.isEmpty()) {
                    items.add("Hidden");
                    items.addAll(hiddenMatches);
                }
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
            if (o instanceof WebSearch || o instanceof HiddenEntry) return 2;
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
            if (o instanceof WebSearch) {
                bindWebRow(row, ((WebSearch) o).query);
                row.setAlpha(1f);
            } else if (o instanceof HiddenEntry) {
                bindHiddenEntryRow(row, ((HiddenEntry) o).count);
                row.setAlpha(1f);
            } else {
                bindRow(row, (AppInfo) o, STYLE_DRAWER);
                row.setAlpha(hiddenKeys.contains(((AppInfo) o).key) ? 0.5f : 1f);
            }
            return row;
        }
    }

    private static final class HiddenEntry {
        final int count;

        HiddenEntry(int count) {
            this.count = count;
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
