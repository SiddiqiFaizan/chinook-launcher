package app.chinook.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * A dialog list with a search box, used for every "choose something to add" flow: apps, widgets,
 * cities. Single mode returns one pick; multi mode returns the selection in the order it was made.
 */
final class SearchablePicker {
    interface IconSource {
        Drawable load();
    }

    static final class Item {
        final String title;
        final String subtitle;
        final String searchText;
        private final IconSource iconSource;
        private Drawable icon;
        private boolean iconLoaded;

        Item(String title, String subtitle, Drawable icon) {
            this(title, subtitle, null, icon);
        }

        Item(String title, String subtitle, IconSource iconSource, Drawable icon) {
            this.title = title;
            this.subtitle = subtitle;
            this.iconSource = iconSource;
            this.icon = icon;
            this.iconLoaded = iconSource == null;
            this.searchText = AppInfo.normalize(title + " " + (subtitle == null ? "" : subtitle));
        }

        Drawable icon() {
            if (!iconLoaded) {
                iconLoaded = true;
                try {
                    icon = iconSource.load();
                } catch (RuntimeException ignored) {
                }
            }
            return icon;
        }

        boolean hasIcon() {
            return iconSource != null || icon != null;
        }
    }

    interface OnPick {
        void onPick(int index);
    }

    interface OnSave {
        void onSave(List<Integer> selectedInOrder);
    }

    private final Activity activity;
    private final Theme theme;
    private final String title;
    private final List<Item> items;
    private final String hint;
    private final boolean multi;
    private final int max;
    private final OnPick onPick;
    private final OnSave onSave;
    private final float density;

    private final LinkedHashSet<Integer> selected = new LinkedHashSet<>();
    private final List<Integer> baseOrder = new ArrayList<>();
    private final List<Integer> visible = new ArrayList<>();
    private BaseAdapter adapter;
    private AlertDialog dialog;

    private SearchablePicker(Activity activity, Theme theme, String title, List<Item> items, String hint,
                             boolean multi, List<Integer> initial, int max, OnPick onPick, OnSave onSave) {
        this.activity = activity;
        this.theme = theme;
        this.title = title;
        this.items = items;
        this.hint = hint;
        this.multi = multi;
        this.max = max;
        this.onPick = onPick;
        this.onSave = onSave;
        this.density = activity.getResources().getDisplayMetrics().density;
        if (initial != null) selected.addAll(initial);
        // In multi mode, what's already chosen is listed first so it's easy to review.
        if (multi) baseOrder.addAll(selected);
        for (int i = 0; i < items.size(); i++) if (!multi || !selected.contains(i)) baseOrder.add(i);
    }

    static void single(Activity activity, Theme theme, String title, List<Item> items, String hint, OnPick onPick) {
        new SearchablePicker(activity, theme, title, items, hint, false, null, 0, onPick, null).show();
    }

    /** max <= 0 means unlimited. */
    static void multi(Activity activity, Theme theme, String title, List<Item> items, List<Integer> initial,
                      int max, String hint, OnSave onSave) {
        new SearchablePicker(activity, theme, title, items, hint, true, initial, max, null, onSave).show();
    }

    private void show() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);

        final EditText search = new EditText(activity);
        search.setHint(hint);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(0x99FFFFFF);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        search.setTypeface(Fonts.regular);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x1FFFFFFF);
        bg.setCornerRadius(dp(24));
        search.setBackground(bg);
        search.setPadding(dp(18), dp(11), dp(18), dp(11));
        box.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ListView list = new ListView(activity);
        list.setDivider(null);
        list.setFastScrollEnabled(false);
        adapter = new PickerAdapter();
        list.setAdapter(adapter);
        int screenH = activity.getResources().getDisplayMetrics().heightPixels;
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.min(dp(440), (int) (screenH * 0.55f)));
        listLp.topMargin = dp(8);
        box.addView(list, listLp);

        final TextView empty = new TextView(activity);
        empty.setText("No matches");
        empty.setTextColor(0x99FFFFFF);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(24), 0, dp(24));
        empty.setVisibility(View.GONE);
        box.addView(empty);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                filter(s.toString());
                empty.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
            }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                int index = visible.get(position);
                if (!multi) {
                    dialog.dismiss();
                    onPick.onPick(index);
                    return;
                }
                if (selected.contains(index)) {
                    selected.remove(index);
                } else if (max > 0 && selected.size() >= max) {
                    Toast.makeText(activity, "Up to " + max + " items", Toast.LENGTH_SHORT).show();
                    return;
                } else {
                    selected.add(index);
                }
                adapter.notifyDataSetChanged();
                updateSaveLabel();
            }
        });

        AlertDialog.Builder b = new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setView(box)
                .setNegativeButton("Cancel", null);
        if (multi) {
            b.setPositiveButton("Save", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    onSave.onSave(new ArrayList<>(selected));
                }
            });
        }
        dialog = b.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }
        filter("");
        dialog.show();
        updateSaveLabel();
    }

    private void filter(String query) {
        String q = AppInfo.normalize(query.trim());
        visible.clear();
        if (q.isEmpty()) {
            visible.addAll(baseOrder);
        } else {
            List<Integer> contains = new ArrayList<>();
            for (int i : baseOrder) {
                String s = items.get(i).searchText;
                if (s.startsWith(q) || s.contains(" " + q)) visible.add(i);
                else if (s.contains(q)) contains.add(i);
            }
            visible.addAll(contains);
        }
        adapter.notifyDataSetChanged();
    }

    private void updateSaveLabel() {
        if (!multi || dialog == null) return;
        Button save = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (save == null) return;
        String count = max > 0 ? selected.size() + "/" + max : String.valueOf(selected.size());
        save.setText("Save (" + count + ")");
    }

    private final class PickerAdapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int position) { return items.get(visible.get(position)); }
        @Override public long getItemId(int position) { return visible.get(position); }

        @Override public View getView(int position, View convert, ViewGroup parent) {
            LinearLayout row = (LinearLayout) convert;
            if (row == null) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(8), dp(9), dp(4), dp(9));
                row.setMinimumHeight(dp(52));

                ImageView icon = new ImageView(activity);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(36), dp(36));
                ip.rightMargin = dp(14);
                row.addView(icon, ip);

                LinearLayout texts = new LinearLayout(activity);
                texts.setOrientation(LinearLayout.VERTICAL);
                TextView t = new TextView(activity);
                t.setTextColor(Color.WHITE);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                t.setTypeface(Fonts.medium);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                TextView s = new TextView(activity);
                s.setTextColor(0x99FFFFFF);
                s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                s.setTypeface(Fonts.regular);
                s.setSingleLine(true);
                s.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(t);
                texts.addView(s);
                row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                CheckBox check = new CheckBox(activity);
                check.setClickable(false);
                check.setFocusable(false);
                row.addView(check);
            }
            Item item = items.get(visible.get(position));
            ImageView icon = (ImageView) row.getChildAt(0);
            LinearLayout texts = (LinearLayout) row.getChildAt(1);
            CheckBox check = (CheckBox) row.getChildAt(2);
            if (item.hasIcon()) {
                icon.setVisibility(View.VISIBLE);
                icon.setImageDrawable(item.icon());
                icon.setColorFilter(theme.grayscale ? Theme.grayscaleFilter() : null);
            } else {
                icon.setVisibility(View.GONE);
            }
            ((TextView) texts.getChildAt(0)).setText(item.title);
            TextView sub = (TextView) texts.getChildAt(1);
            sub.setText(item.subtitle);
            sub.setVisibility(TextUtils.isEmpty(item.subtitle) ? View.GONE : View.VISIBLE);
            check.setVisibility(multi ? View.VISIBLE : View.GONE);
            check.setChecked(selected.contains(visible.get(position)));
            return row;
        }
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
