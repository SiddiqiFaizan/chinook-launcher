package app.chinook.launcher;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
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
 * Full-screen Material search page used for every "choose something to add" flow: apps, widgets,
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
    private final M3 m;
    private final String title;
    private final List<Item> items;
    private final String hint;
    private final String allLabel;
    private final boolean multi;
    private final int max;
    private final OnPick onPick;
    private final OnSave onSave;

    private final LinkedHashSet<Integer> selected = new LinkedHashSet<>();
    private final List<Integer> initiallySelected = new ArrayList<>();
    private final List<Integer> rest = new ArrayList<>();
    /** Rows: a String is a subheader, an Integer is an item index. */
    private final List<Object> rows = new ArrayList<>();
    private BaseAdapter adapter;
    private Surfaces.Page page;
    private TextView saveButton;

    private SearchablePicker(Activity activity, M3 m, String title, List<Item> items, String hint, String allLabel,
                             boolean multi, List<Integer> initial, int max, OnPick onPick, OnSave onSave) {
        this.activity = activity;
        this.m = m;
        this.title = title;
        this.items = items;
        this.hint = hint;
        this.allLabel = allLabel;
        this.multi = multi;
        this.max = max;
        this.onPick = onPick;
        this.onSave = onSave;
        if (initial != null) {
            selected.addAll(initial);
            initiallySelected.addAll(selected);
        }
        for (int i = 0; i < items.size(); i++) if (!selected.contains(i)) rest.add(i);
    }

    static void single(Activity activity, M3 m, String title, List<Item> items, String hint, OnPick onPick) {
        new SearchablePicker(activity, m, title, items, hint, null, false, null, 0, onPick, null).show();
    }

    /** max <= 0 means unlimited. allLabel names the unselected section, e.g. "All apps". */
    static void multi(Activity activity, M3 m, String title, List<Item> items, List<Integer> initial,
                      int max, String hint, String allLabel, OnSave onSave) {
        new SearchablePicker(activity, m, title, items, hint, allLabel, true, initial, max, null, onSave).show();
    }

    private void show() {
        page = new Surfaces.Page(activity, m, title);
        if (multi) {
            saveButton = m.filledButton("Save", 0, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    page.close();
                    onSave.onSave(new ArrayList<>(selected));
                }
            });
            page.action(saveButton);
        }

        EditText search = new EditText(activity);
        LinearLayout bar = m.searchBar(search, hint);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.leftMargin = barLp.rightMargin = m.dp(16);
        barLp.topMargin = m.dp(4);
        barLp.bottomMargin = m.dp(8);
        page.body.addView(bar, barLp);

        if (multi && max > 0) {
            TextView note = m.text("Choose up to " + max, M3.BODY_MEDIUM, m.onSurfaceVariant);
            note.setPadding(m.dp(32), 0, m.dp(16), m.dp(4));
            page.body.addView(note);
        }

        final ListView list = new ListView(activity);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, m.dp(16));
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        page.body.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final TextView empty = m.text("No matches", M3.BODY_LARGE, m.onSurfaceVariant);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, m.dp(48), 0, m.dp(48));
        empty.setVisibility(View.GONE);
        page.body.addView(empty, 1 + (multi && max > 0 ? 2 : 1));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                filter(s.toString());
                empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                list.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
                list.setSelection(0);
            }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Object row = rows.get(position);
                if (!(row instanceof Integer)) return;
                int index = (Integer) row;
                if (!multi) {
                    page.close();
                    onPick.onPick(index);
                    return;
                }
                if (selected.contains(index)) {
                    selected.remove(index);
                } else if (max > 0 && selected.size() >= max) {
                    Toast.makeText(activity, "You can choose up to " + max, Toast.LENGTH_SHORT).show();
                    return;
                } else {
                    selected.add(index);
                }
                adapter.notifyDataSetChanged();
                updateSaveLabel();
            }
        });

        filter("");
        updateSaveLabel();
        page.show();
    }

    private void filter(String query) {
        String q = AppInfo.normalize(query.trim());
        rows.clear();
        if (q.isEmpty()) {
            if (multi && !initiallySelected.isEmpty()) {
                rows.add("Selected");
                rows.addAll(initiallySelected);
                if (!rest.isEmpty()) rows.add(allLabel != null ? allLabel : "All");
            }
            rows.addAll(rest);
        } else {
            List<Integer> starts = new ArrayList<>();
            List<Integer> contains = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                String s = items.get(i).searchText;
                if (s.startsWith(q) || s.contains(" " + q)) starts.add(i);
                else if (s.contains(q)) contains.add(i);
            }
            rows.addAll(starts);
            rows.addAll(contains);
        }
        adapter.notifyDataSetChanged();
    }

    private void updateSaveLabel() {
        if (saveButton == null) return;
        saveButton.setText(selected.isEmpty() ? "Save" : "Save (" + selected.size() + ")");
    }

    private final class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) { return rows.get(position) instanceof String ? 0 : 1; }
        @Override public boolean isEnabled(int position) { return rows.get(position) instanceof Integer; }

        @Override public View getView(int position, View convert, ViewGroup parent) {
            Object row = rows.get(position);
            if (row instanceof String) {
                TextView h = convert instanceof TextView ? (TextView) convert : m.subheader("");
                h.setText((String) row);
                return h;
            }
            LinearLayout v = (LinearLayout) convert;
            if (v == null) {
                v = new LinearLayout(activity);
                v.setOrientation(LinearLayout.HORIZONTAL);
                v.setGravity(Gravity.CENTER_VERTICAL);
                v.setPadding(m.dp(16), m.dp(8), m.dp(multi ? 12 : 24), m.dp(8));
                v.setBackground(m.ripple(m.onSurface, null, 0));

                ImageView icon = new ImageView(activity);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(m.dp(40), m.dp(40));
                ip.rightMargin = m.dp(16);
                v.addView(icon, ip);

                LinearLayout texts = new LinearLayout(activity);
                texts.setOrientation(LinearLayout.VERTICAL);
                TextView t = m.text("", M3.BODY_LARGE, m.onSurface);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                TextView s = m.text("", M3.BODY_MEDIUM, m.onSurfaceVariant);
                s.setSingleLine(true);
                s.setEllipsize(TextUtils.TruncateAt.END);
                s.setPadding(0, m.dp(2), 0, 0);
                texts.addView(t);
                texts.addView(s);
                v.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                CheckBox check = m.checkBox();
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cp.leftMargin = m.dp(16);
                v.addView(check, cp);
            }
            Item item = items.get((Integer) row);
            ImageView icon = (ImageView) v.getChildAt(0);
            LinearLayout texts = (LinearLayout) v.getChildAt(1);
            CheckBox check = (CheckBox) v.getChildAt(2);
            if (item.hasIcon()) {
                icon.setVisibility(View.VISIBLE);
                icon.setImageDrawable(item.icon());
                icon.setColorFilter(m.theme.grayscale ? Theme.grayscaleFilter() : null);
            } else {
                icon.setVisibility(View.GONE);
            }
            ((TextView) texts.getChildAt(0)).setText(item.title);
            TextView sub = (TextView) texts.getChildAt(1);
            sub.setText(item.subtitle);
            sub.setVisibility(TextUtils.isEmpty(item.subtitle) ? View.GONE : View.VISIBLE);
            v.setMinimumHeight(m.dp(TextUtils.isEmpty(item.subtitle) ? 56 : 72));
            check.setVisibility(multi ? View.VISIBLE : View.GONE);
            check.setChecked(selected.contains(row));
            return v;
        }
    }
}
