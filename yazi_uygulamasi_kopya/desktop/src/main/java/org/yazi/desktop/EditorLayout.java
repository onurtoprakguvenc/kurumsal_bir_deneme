package org.yazi.desktop;

/**
 * Geometry of the writing column. Pure and FX-free.
 *
 * <p>Long lines are hard to read, so on a wide window the text keeps a comfortable measure (about 70 characters,
 * {@link #MEASURE_EM} em) centred on the paper, and the extra width becomes margin. On a narrow window the margin
 * shrinks to {@link #MIN_SIDE}. The measure is in em, so it scales with zoom.</p>
 */
final class EditorLayout {

    private EditorLayout() {}

    static final double MEASURE_EM = 38;
    static final double MIN_SIDE = 32;
    static final double TOP = 36;
    static final double BOTTOM = 120;   // room to type the last line above the bottom edge

    /** Left and right padding for a viewport {@code width} wide at {@code fontSize} px. */
    static double sidePadding(double width, double fontSize) {
        if (!(width > 0) || !(fontSize > 0)) {
            return MIN_SIDE;
        }
        double measure = MEASURE_EM * fontSize;
        return Math.max(MIN_SIDE, Math.floor((width - measure) / 2));
    }

    /** One zoom step: about 10 %, at least 1 px, clamped to the allowed range. */
    static double zoom(double size, int direction) {
        double step = Math.max(1, Math.round(size * 0.1));
        return UiSettings.clampFont(size + Math.signum(direction) * step);
    }
}
