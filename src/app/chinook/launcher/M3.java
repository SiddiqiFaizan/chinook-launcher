package app.chinook.launcher;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Material 3 colour roles, type scale and component builders for Chinook's menus, sheets and pages.
 * Colored follows the phone's Material You palette on Android 12+, Dark is a neutral grey scheme and
 * Black & White is pure greyscale.
 */
final class M3 {
    // Type scale roles (size sp, weight via font).
    static final int HEADLINE_SMALL = 0;
    static final int TITLE_LARGE = 1;
    static final int TITLE_MEDIUM = 2;
    static final int TITLE_SMALL = 3;
    static final int BODY_LARGE = 4;
    static final int BODY_MEDIUM = 5;
    static final int LABEL_LARGE = 6;
    static final int LABEL_MEDIUM = 7;

    final Theme theme;
    final int primary;
    final int onPrimary;
    final int primaryContainer;
    final int onPrimaryContainer;
    final int secondaryContainer;
    final int onSecondaryContainer;
    final int surface;
    final int surfaceContainerLow;
    final int surfaceContainer;
    final int surfaceContainerHigh;
    final int surfaceContainerHighest;
    final int onSurface;
    final int onSurfaceVariant;
    final int outline;
    final int outlineVariant;

    private final Context context;
    private final float density;

    private M3(Context context, Theme theme, int[] c) {
        this.context = context;
        this.theme = theme;
        this.density = context.getResources().getDisplayMetrics().density;
        primary = c[0];
        onPrimary = c[1];
        primaryContainer = c[2];
        onPrimaryContainer = c[3];
        secondaryContainer = c[4];
        onSecondaryContainer = c[5];
        surface = c[6];
        surfaceContainerLow = c[7];
        surfaceContainer = c[8];
        surfaceContainerHigh = c[9];
        surfaceContainerHighest = c[10];
        onSurface = c[11];
        onSurfaceVariant = c[12];
        outline = c[13];
        outlineVariant = c[14];
    }

    static M3 of(Context context, Theme theme) {
        switch (theme.id) {
            case Theme.MONO:
                return new M3(context, theme, new int[]{
                        0xFFFFFFFF, 0xFF000000, 0xFF2A2A2A, 0xFFFFFFFF, 0xFF262626, 0xFFFFFFFF,
                        0xFF000000, 0xFF0B0B0B, 0xFF121212, 0xFF1C1C1C, 0xFF262626,
                        0xFFFFFFFF, 0xFFB3B3B3, 0xFF808080, 0xFF333333});
            case Theme.DARK:
                return new M3(context, theme, new int[]{
                        0xFFD0D4DC, 0xFF20242B, 0xFF3A3F47, 0xFFE0E4EC, 0xFF34363A, 0xFFE2E2E6,
                        0xFF121314, 0xFF1A1B1D, 0xFF1E1F21, 0xFF28292B, 0xFF333436,
                        0xFFE4E4E6, 0xFFC5C6CA, 0xFF8F9095, 0xFF45464A});
            default:
                if (Build.VERSION.SDK_INT >= 31) return dynamic(context, theme);
                return new M3(context, theme, new int[]{
                        0xFFA8C7FA, 0xFF062E6F, 0xFF0842A0, 0xFFD3E3FD, 0xFF3E4759, 0xFFDAE2F9,
                        0xFF111318, 0xFF191C20, 0xFF1D2024, 0xFF282A2F, 0xFF33353A,
                        0xFFE2E2E9, 0xFFC4C6D0, 0xFF8E9099, 0xFF44474F});
        }
    }

    /** Material You: the system palette generated from the wallpaper (Android 12+). */
    private static M3 dynamic(Context ctx, Theme theme) {
        int n1900 = color(ctx, android.R.color.system_neutral1_900);
        int n1800 = color(ctx, android.R.color.system_neutral1_800);
        return new M3(ctx, theme, new int[]{
                color(ctx, android.R.color.system_accent1_200),
                color(ctx, android.R.color.system_accent1_800),
                color(ctx, android.R.color.system_accent1_700),
                color(ctx, android.R.color.system_accent1_100),
                color(ctx, android.R.color.system_accent2_700),
                color(ctx, android.R.color.system_accent2_100),
                blend(n1900, Color.BLACK, 0.45f),
                n1900,
                blend(n1900, n1800, 0.25f),
                blend(n1900, n1800, 0.6f),
                n1800,
                color(ctx, android.R.color.system_neutral1_100),
                color(ctx, android.R.color.system_neutral2_200),
                color(ctx, android.R.color.system_neutral2_400),
                color(ctx, android.R.color.system_neutral2_700),
        });
    }

    private static int color(Context ctx, int res) {
        return ctx.getColor(res);
    }

    static int blend(int a, int b, float t) {
        return Color.argb(
                Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    static int withAlpha(int color, float alpha) {
        return (Math.round(alpha * 255) << 24) | (color & 0x00FFFFFF);
    }

    int dp(float v) {
        return Math.round(v * density);
    }

    // ------------------------------------------------------------- text

    TextView text(CharSequence s, int role, int color) {
        TextView t = new TextView(context);
        t.setText(s);
        t.setTextColor(color);
        style(t, role);
        return t;
    }

    void style(TextView t, int role) {
        float size;
        Typeface face;
        float tracking = 0f;
        switch (role) {
            case HEADLINE_SMALL: size = 24; face = Fonts.regular; break;
            case TITLE_LARGE: size = 22; face = Fonts.regular; break;
            case TITLE_MEDIUM: size = 16; face = Fonts.medium; tracking = 0.15f; break;
            case TITLE_SMALL: size = 14; face = Fonts.medium; tracking = 0.1f; break;
            case BODY_LARGE: size = 16; face = Fonts.regular; tracking = 0.5f; break;
            case BODY_MEDIUM: size = 14; face = Fonts.regular; tracking = 0.25f; break;
            case LABEL_MEDIUM: size = 12; face = Fonts.medium; tracking = 0.5f; break;
            default: size = 14; face = Fonts.medium; tracking = 0.1f; break;
        }
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTypeface(face);
        t.setLetterSpacing(tracking / size);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(size * 0.25f * density, 1f);
    }

    // ------------------------------------------------------- drawables

    GradientDrawable shape(int fill, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    /** Pressed-state layer in the content colour, clipped to the given corner radius. */
    RippleDrawable ripple(int contentColor, Drawable background, float radiusDp) {
        GradientDrawable mask = shape(Color.WHITE, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(withAlpha(contentColor, 0.12f)), background, mask);
    }

    Drawable icon(int res, int color) {
        Drawable d = context.getDrawable(res);
        if (d == null) return null;
        d = d.mutate();
        d.setTint(color);
        return d;
    }

    ImageView iconView(int res, int color, int sizeDp) {
        ImageView v = new ImageView(context);
        v.setImageDrawable(icon(res, color));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    // --------------------------------------------------------- buttons

    TextView filledButton(String label, int iconRes, View.OnClickListener click) {
        return button(label, iconRes, primary, onPrimary, 0, click);
    }

    TextView tonalButton(String label, int iconRes, View.OnClickListener click) {
        return button(label, iconRes, secondaryContainer, onSecondaryContainer, 0, click);
    }

    TextView outlinedButton(String label, int iconRes, View.OnClickListener click) {
        return button(label, iconRes, Color.TRANSPARENT, primary, outline, click);
    }

    TextView textButton(String label, View.OnClickListener click) {
        TextView b = button(label, 0, Color.TRANSPARENT, primary, 0, click);
        b.setPadding(dp(12), 0, dp(12), 0);
        return b;
    }

    private TextView button(String label, int iconRes, int fill, int content, int stroke, View.OnClickListener click) {
        TextView b = text(label, LABEL_LARGE, content);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(40));
        b.setMinimumHeight(dp(40));
        b.setPadding(dp(iconRes != 0 ? 16 : 24), 0, dp(24), 0);
        GradientDrawable bg = shape(fill, 20);
        if (stroke != 0) bg.setStroke(dp(1), stroke);
        b.setBackground(ripple(content, bg, 20));
        if (iconRes != 0) {
            Drawable d = icon(iconRes, content);
            if (d != null) d.setBounds(0, 0, dp(18), dp(18));
            b.setCompoundDrawablesRelative(d, null, null, null);
            b.setCompoundDrawablePadding(dp(8));
        }
        b.setOnClickListener(click);
        return b;
    }

    /** 48dp touch target with a 40dp circular state layer. */
    FrameLayout iconButton(int iconRes, int color, String description, View.OnClickListener click) {
        FrameLayout f = new FrameLayout(context);
        ImageView v = new ImageView(context);
        v.setImageDrawable(icon(iconRes, color));
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setBackground(ripple(color, null, 20));
        v.setContentDescription(description);
        v.setOnClickListener(click);
        f.addView(v, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return f;
    }

    // ----------------------------------------------------------- lists

    /**
     * Material list item: leading icon (24dp) or image (40dp), headline + optional supporting
     * text, optional trailing control. 56dp one-line, 72dp two-line.
     */
    LinearLayout listItem(int iconRes, Drawable image, String headline, String supporting, View trailing,
                          View.OnClickListener click) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        boolean twoLine = !TextUtils.isEmpty(supporting);
        row.setMinimumHeight(dp(twoLine ? 72 : 56));
        row.setPadding(dp(16), dp(8), dp(trailing != null ? 16 : 24), dp(8));
        if (click != null) {
            row.setBackground(ripple(onSurface, null, 0));
            row.setOnClickListener(click);
        }
        if (iconRes != 0) {
            ImageView icon = iconView(iconRes, onSurfaceVariant, 24);
            ((LinearLayout.LayoutParams) icon.getLayoutParams()).rightMargin = dp(16);
            row.addView(icon);
        } else if (image != null) {
            ImageView icon = new ImageView(context);
            icon.setImageDrawable(image);
            if (theme.grayscale) icon.setColorFilter(Theme.grayscaleFilter());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
            lp.rightMargin = dp(16);
            row.addView(icon, lp);
        }
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView h = text(headline, BODY_LARGE, onSurface);
        h.setMaxLines(2);
        h.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(h);
        if (twoLine) {
            TextView s = text(supporting, BODY_MEDIUM, onSurfaceVariant);
            s.setMaxLines(2);
            s.setEllipsize(TextUtils.TruncateAt.END);
            s.setPadding(0, dp(2), 0, 0);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (trailing != null) {
            if (trailing.getLayoutParams() == null) {
                trailing.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            ((LinearLayout.LayoutParams) trailing.getLayoutParams()).leftMargin = dp(16);
            row.addView(trailing);
        }
        return row;
    }

    TextView subheader(String label) {
        TextView t = text(label, TITLE_SMALL, primary);
        t.setPadding(dp(16), dp(20), dp(16), dp(8));
        return t;
    }

    View divider() {
        View v = new View(context);
        v.setBackgroundColor(outlineVariant);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.topMargin = lp.bottomMargin = dp(8);
        v.setLayoutParams(lp);
        return v;
    }

    // -------------------------------------------------------- controls

    Switch switchView(boolean checked) {
        Switch s = new Switch(context);
        s.setChecked(checked);
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[]{onPrimary, outline}));
        s.setTrackTintList(new ColorStateList(states, new int[]{primary, surfaceContainerHighest}));
        s.setTrackTintMode(android.graphics.PorterDuff.Mode.SRC_IN);
        s.setClickable(false);
        s.setFocusable(false);
        return s;
    }

    CheckBox checkBox() {
        CheckBox c = new CheckBox(context);
        tint(c);
        c.setClickable(false);
        c.setFocusable(false);
        return c;
    }

    RadioButton radio(boolean checked) {
        RadioButton r = new RadioButton(context);
        tint(r);
        r.setChecked(checked);
        r.setClickable(false);
        r.setFocusable(false);
        return r;
    }

    private void tint(CompoundButton b) {
        int[][] states = {{android.R.attr.state_checked}, {}};
        b.setButtonTintList(new ColorStateList(states, new int[]{primary, onSurfaceVariant}));
    }

    void tint(SeekBar s) {
        s.setThumbTintList(ColorStateList.valueOf(primary));
        s.setProgressTintList(ColorStateList.valueOf(primary));
        s.setProgressBackgroundTintList(ColorStateList.valueOf(secondaryContainer));
    }

    /** Pill-shaped 56dp search bar: leading search icon, text field, trailing clear button. */
    LinearLayout searchBar(final EditText field, String hint) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(shape(surfaceContainerHigh, 28));
        bar.setPadding(dp(16), 0, dp(4), 0);
        bar.setMinimumHeight(dp(56));
        ImageView lead = iconView(R.drawable.ic_search, onSurfaceVariant, 24);
        ((LinearLayout.LayoutParams) lead.getLayoutParams()).rightMargin = dp(16);
        bar.addView(lead);
        field.setHint(hint);
        field.setTextColor(onSurface);
        field.setHintTextColor(onSurfaceVariant);
        style(field, BODY_LARGE);
        field.setSingleLine(true);
        field.setBackground(null);
        field.setPadding(0, 0, 0, 0);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        bar.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final FrameLayout clear = iconButton(R.drawable.ic_close, onSurfaceVariant, "Clear", new View.OnClickListener() {
            @Override public void onClick(View v) {
                field.setText("");
            }
        });
        clear.setVisibility(View.INVISIBLE);
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                clear.setVisibility(s.length() > 0 ? View.VISIBLE : View.INVISIBLE);
            }
        });
        bar.addView(clear);
        return bar;
    }

    /** Outlined text field with a label above it. */
    LinearLayout outlinedField(EditText field, String label) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView l = text(label, BODY_MEDIUM, onSurfaceVariant);
        l.setPadding(dp(4), 0, 0, dp(6));
        box.addView(l);
        field.setTextColor(onSurface);
        field.setHintTextColor(onSurfaceVariant);
        style(field, BODY_LARGE);
        field.setSingleLine(true);
        GradientDrawable bg = shape(Color.TRANSPARENT, 4);
        bg.setStroke(dp(1), outline);
        field.setBackground(bg);
        field.setPadding(dp(16), dp(16), dp(16), dp(16));
        box.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }
}
