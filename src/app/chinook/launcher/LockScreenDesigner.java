package app.chinook.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates minimal wallpapers in Colored, Dark and Black & White styles and applies them to the
 * lock screen and/or home screen. Android draws the lock-screen clock itself; these designs leave
 * the top half calm so the system clock sits cleanly on them.
 */
final class LockScreenDesigner {
    private static final class Style {
        final String name;
        final int group;

        Style(String name, int group) {
            this.name = name;
            this.group = group;
        }
    }

    private static final Style[] STYLES = {
            new Style("Aurora", Theme.COLORED),
            new Style("Dusk", Theme.COLORED),
            new Style("Ocean", Theme.COLORED),
            new Style("Graphite", Theme.DARK),
            new Style("Midnight", Theme.DARK),
            new Style("Dark waves", Theme.DARK),
            new Style("Pure black", Theme.MONO),
            new Style("Mono waves", Theme.MONO),
            new Style("Horizon", Theme.MONO),
            new Style("Mono grain", Theme.MONO),
    };

    private final Activity activity;
    private final Prefs prefs;
    private final Theme theme;
    private final float density;
    private int selected;

    LockScreenDesigner(Activity activity, Prefs prefs, Theme theme) {
        this.activity = activity;
        this.prefs = prefs;
        this.theme = theme;
        this.density = activity.getResources().getDisplayMetrics().density;
    }

    void show() {
        // Styles that match the current theme come first.
        final List<Integer> order = new ArrayList<>();
        for (int i = 0; i < STYLES.length; i++) if (STYLES[i].group == theme.id) order.add(i);
        for (int i = 0; i < STYLES.length; i++) if (STYLES[i].group != theme.id) order.add(i);
        selected = order.get(0);

        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) activity.getSystemService(Activity.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        final int screenW = dm.widthPixels;
        final int screenH = dm.heightPixels;
        int thumbW = dp(92);
        int thumbH = Math.round(thumbW * (float) screenH / screenW);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);

        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(activity);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(strip);
        final List<View> frames = new ArrayList<>();
        for (final int styleIndex : order) {
            LinearLayout item = new LinearLayout(activity);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(dp(5), dp(5), dp(5), dp(5));
            ImageView img = new ImageView(activity);
            img.setImageBitmap(render(styleIndex, thumbW, thumbH, "", true));
            img.setPadding(dp(3), dp(3), dp(3), dp(3));
            img.setClipToOutline(true);
            frames.add(img);
            item.addView(img, new LinearLayout.LayoutParams(thumbW + dp(6), thumbH + dp(6)));
            TextView label = new TextView(activity);
            label.setText(STYLES[styleIndex].name);
            label.setTextColor(Color.WHITE);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            label.setTypeface(Fonts.medium);
            label.setPadding(0, dp(6), 0, 0);
            item.addView(label);
            TextView group = new TextView(activity);
            group.setText(Theme.NAMES[STYLES[styleIndex].group]);
            group.setTextColor(0x88FFFFFF);
            group.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            item.addView(group);
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    selected = styleIndex;
                    highlight(frames, order);
                }
            });
            strip.addView(item);
        }
        box.addView(scroll);
        highlight(frames, order);

        final EditText note = new EditText(activity);
        note.setHint("Optional note, e.g. your name or a contact number");
        note.setText(prefs.lockNote());
        note.setSingleLine(true);
        note.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        noteLp.topMargin = dp(12);
        box.addView(note, noteLp);

        TextView info = new TextView(activity);
        info.setText("Android draws the lock-screen clock and shortcuts itself. Change the clock style in "
                + "your phone's Settings → Wallpaper & style.");
        info.setTextColor(0x88FFFFFF);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        info.setPadding(dp(4), dp(8), dp(4), 0);
        box.addView(info);

        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Lock screen")
                .setView(box)
                .setPositiveButton("Lock screen", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        apply(note.getText().toString().trim(), screenW, screenH, WallpaperManager.FLAG_LOCK);
                    }
                })
                .setNeutralButton("Both", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        apply(note.getText().toString().trim(), screenW, screenH,
                                WallpaperManager.FLAG_LOCK | WallpaperManager.FLAG_SYSTEM);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void highlight(List<View> frames, List<Integer> order) {
        for (int i = 0; i < frames.size(); i++) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(12));
            g.setColor(Color.TRANSPARENT);
            if (order.get(i) == selected) g.setStroke(dp(2), theme.accent);
            frames.get(i).setBackground(g);
        }
    }

    private void apply(final String note, final int w, final int h, final int which) {
        prefs.setLockNote(note);
        final int style = selected;
        Toast.makeText(activity, "Applying…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                String result;
                try {
                    Bitmap bmp = render(style, w, h, note, false);
                    WallpaperManager.getInstance(activity).setBitmap(bmp, null, true, which);
                    bmp.recycle();
                    result = "Lock screen updated";
                } catch (IOException | RuntimeException e) {
                    result = "Couldn't set wallpaper";
                }
                final String msg = result;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------ drawing

    static Bitmap render(int style, int w, int h, String note, boolean thumbnail) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float u = w / 400f; // one design unit, so thumbnails and full size match
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        switch (style) {
            case 0: // Aurora
                c.drawColor(0xFF0B1026);
                glow(c, p, 0.18f * w, 0.78f * h, 0.85f * w, 0x993D6BFF);
                glow(c, p, 0.90f * w, 0.58f * h, 0.70f * w, 0x889B5CFF);
                glow(c, p, 0.50f * w, 1.02f * h, 0.75f * w, 0x772EC5CE);
                grain(c, w, h, 10);
                break;
            case 1: // Dusk
                vertical(c, p, w, h, new int[]{0xFF140C2C, 0xFF4B1F5E, 0xFFB4466A, 0xFFF29A62},
                        new float[]{0f, 0.45f, 0.78f, 1f});
                glow(c, p, 0.5f * w, 0.86f * h, 0.42f * w, 0xAAFFD6A5);
                grain(c, w, h, 9);
                break;
            case 2: // Ocean
                vertical(c, p, w, h, new int[]{0xFF02121F, 0xFF06344A, 0xFF0A6C85}, new float[]{0f, 0.6f, 1f});
                waves(c, w, h, 0.66f, 9, 0x3354E3F5, 1.2f * u, u);
                grain(c, w, h, 8);
                break;
            case 3: // Graphite
                vertical(c, p, w, h, new int[]{0xFF1C1D21, 0xFF0B0B0D}, new float[]{0f, 1f});
                grain(c, w, h, 16);
                break;
            case 4: // Midnight
                c.drawColor(0xFF05070D);
                glow(c, p, 0.5f * w, 1.05f * h, 0.9f * w, 0x66223355);
                grain(c, w, h, 10);
                break;
            case 5: // Dark waves
                c.drawColor(0xFF0D0E11);
                waves(c, w, h, 0.62f, 12, 0xFF26282E, 1.4f * u, u);
                break;
            case 6: // Pure black
                c.drawColor(Color.BLACK);
                break;
            case 7: // Mono waves
                c.drawColor(Color.BLACK);
                waves(c, w, h, 0.6f, 14, 0x59FFFFFF, 1.1f * u, u);
                break;
            case 8: { // Horizon
                c.drawColor(Color.BLACK);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.4f * u);
                p.setColor(0xCCFFFFFF);
                float r = 1.4f * w;
                c.drawCircle(w / 2f, 0.80f * h + r, r, p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.WHITE);
                c.drawCircle(0.72f * w, 0.70f * h, 7 * u, p);
                break;
            }
            default: // Mono grain
                vertical(c, p, w, h, new int[]{0xFF000000, 0xFF1A1A1A}, new float[]{0f, 1f});
                grain(c, w, h, 18);
                break;
        }
        if (note != null && !note.isEmpty()) {
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setColor(0xB3FFFFFF);
            t.setTextSize(15 * u);
            t.setTypeface(Fonts.medium);
            t.setTextAlign(Paint.Align.CENTER);
            t.setLetterSpacing(0.04f);
            c.drawText(note, w / 2f, 0.80f * h, t);
        }
        return bmp;
    }

    private static void glow(Canvas c, Paint p, float x, float y, float r, int color) {
        p.setShader(new RadialGradient(x, y, r, color, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(x, y, r, p);
        p.setShader(null);
    }

    private static void vertical(Canvas c, Paint p, int w, int h, int[] colors, float[] stops) {
        p.setShader(new LinearGradient(0, 0, 0, h, colors, stops, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
    }

    /** Thin sine lines stacked in the lower part of the screen. */
    private static void waves(Canvas c, int w, int h, float startFraction, int lines, int color,
                              float stroke, float u) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setColor(color);
        float top = startFraction * h;
        float gap = (h - top) / (lines + 1);
        for (int k = 0; k < lines; k++) {
            Path path = new Path();
            float base = top + gap * (k + 1);
            float amp = (10 + k * 2.2f) * u;
            float phase = k * 0.55f;
            for (int x = 0; x <= w; x += Math.max(2, w / 200)) {
                float y = base + (float) Math.sin(x * 2 * Math.PI / w * 1.3 + phase) * amp;
                if (x == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            c.drawPath(path, p);
        }
    }

    /** Fine film grain from a small random tile repeated across the canvas. */
    private static void grain(Canvas c, int w, int h, int strength) {
        int size = 128;
        int[] px = new int[size * size];
        Random r = new Random(7);
        for (int i = 0; i < px.length; i++) {
            int a = r.nextInt(strength + 1);
            px[i] = r.nextBoolean() ? Color.argb(a, 255, 255, 255) : Color.argb(a, 0, 0, 0);
        }
        Bitmap tile = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        Paint p = new Paint();
        p.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        c.drawRect(0, 0, w, h, p);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
