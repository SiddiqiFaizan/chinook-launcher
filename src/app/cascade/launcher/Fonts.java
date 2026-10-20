package app.cascade.launcher;

import android.content.Context;
import android.graphics.Typeface;

/** Bundled Manrope (variable weight) or the system font. */
final class Fonts {
    static Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    static Typeface regular = Typeface.DEFAULT;
    static Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private Fonts() {}

    static void init(Context context, boolean useBundled) {
        light = Typeface.create("sans-serif-light", Typeface.NORMAL);
        regular = Typeface.DEFAULT;
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        if (!useBundled) return;
        try {
            light = weight(context, 300);
            regular = weight(context, 450);
            medium = weight(context, 600);
        } catch (RuntimeException ignored) {
        }
    }

    private static Typeface weight(Context context, int wght) {
        Typeface t = new Typeface.Builder(context.getAssets(), "fonts/Manrope.ttf")
                .setFontVariationSettings("'wght' " + wght)
                .build();
        if (t == null) throw new IllegalStateException("font");
        return t;
    }
}
