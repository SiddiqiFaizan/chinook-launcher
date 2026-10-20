package app.cascade.launcher;

import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

/** The three looks: Colored (wallpaper + accent), Dark (dimmed, muted), Black & White (AMOLED, grayscale). */
final class Theme {
    static final int COLORED = 0;
    static final int DARK = 1;
    static final int MONO = 2;

    static final String[] NAMES = {"Colored", "Dark", "Black & White"};

    final int id;
    final int accent;
    final int text;
    final int textSecondary;
    final int scrimTop;
    final int scrimBottom;
    /** Opaque background that hides the wallpaper, or 0 to show it. */
    final int solidBackground;
    final int drawerBackground;
    final int panelBackground;
    final int cardBackground;
    final int searchBackground;
    final boolean grayscale;

    private Theme(int id, int accent, int text, int textSecondary, int scrimTop, int scrimBottom,
                  int solidBackground, int drawerBackground, int panelBackground, int cardBackground,
                  int searchBackground, boolean grayscale) {
        this.id = id;
        this.accent = accent;
        this.text = text;
        this.textSecondary = textSecondary;
        this.scrimTop = scrimTop;
        this.scrimBottom = scrimBottom;
        this.solidBackground = solidBackground;
        this.drawerBackground = drawerBackground;
        this.panelBackground = panelBackground;
        this.cardBackground = cardBackground;
        this.searchBackground = searchBackground;
        this.grayscale = grayscale;
    }

    static Theme forId(int id) {
        switch (id) {
            case DARK:
                return new Theme(DARK, 0xFFC4CAD6, 0xFFF1F1F3, 0x99FFFFFF, 0xB3000000, 0xD9000000,
                        0, 0xF5111214, 0xE6000000, 0xFF1A1B1E, 0x1FFFFFFF, false);
            case MONO:
                return new Theme(MONO, 0xFFFFFFFF, 0xFFFFFFFF, 0x8CFFFFFF, 0, 0,
                        0xFF000000, 0xFF000000, 0xF7000000, 0xFF101010, 0x1AFFFFFF, true);
            default:
                return new Theme(COLORED, 0xFF8AB4FF, 0xFFFFFFFF, 0xB3FFFFFF, 0x47000000, 0x66000000,
                        0, 0xE60E1015, 0xB3000000, 0x40000000, 0x24FFFFFF, false);
        }
    }

    private static ColorMatrixColorFilter grayFilter;

    static ColorMatrixColorFilter grayscaleFilter() {
        if (grayFilter == null) {
            ColorMatrix m = new ColorMatrix();
            m.setSaturation(0f);
            grayFilter = new ColorMatrixColorFilter(m);
        }
        return grayFilter;
    }

    /** Paint for a hardware layer that renders a whole view (e.g. a widget) in grayscale. */
    static Paint grayscaleLayerPaint() {
        Paint p = new Paint();
        p.setColorFilter(grayscaleFilter());
        return p;
    }
}
