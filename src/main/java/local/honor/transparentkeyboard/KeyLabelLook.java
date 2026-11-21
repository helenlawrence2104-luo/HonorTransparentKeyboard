package local.honor.transparentkeyboard;

/**
 * IME key/toolbar label ink policy. No Android types and no character content.
 * The IME view night flag may choose a light foreground when glass plates are on.
 * That flag is not the real backdrop behind the keys (white, black, or texture).
 * This is not a refraction claim and does not sample other apps or the screen.
 */
final class KeyLabelLook {
    /**
     * Numeric floors copied from WCAG 2.2 SC 1.4.3 (4.5 / 3.0) for formula
     * regressions on assumed opaque black or white fills only. They are not a
     * WCAG compliance claim. White, black, and textured real backgrounds are
     * unverified. Keyboard labels are compared against both floors in tests.
     */
    static final float FORMULA_RATIO_NORMAL = 4.5f;
    static final float FORMULA_RATIO_LARGE = 3.0f;

    static final String BACKDROP_DISCLOSURE =
        "Glass.night is the IME uiMode night flag, not the real backdrop. "
        + "Contrast figures are formula regressions on assumed opaque black or white fills only. "
        + "White, black, and textured real backgrounds are unverified. "
        + "This is not a WCAG compliance claim.";

    /** Light ink used only when the IME night flag is set and glass plates are on. */
    static final int LIGHT_INK_RGB = 0xF3F5F7;
    static final int LIGHT_INK_ARGB = 0xFFF3F5F7;

    private KeyLabelLook() {}

    static boolean useLightInk(boolean night, int glass) {
        return night && glass > 0;
    }

    /** Relative luminance of the light ink on a fully opaque color. */
    static float lightInkLuma() {
        int rgb = LIGHT_INK_RGB;
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8) & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;
        return GlassLook.lumaRgb(r, g, b);
    }

    static float lightInkContrastOnDarkPlate(int glass, int effectiveClarity) {
        float fill = GlassLook.fillWhiteAlpha(true, glass, effectiveClarity);
        float plate = compositeFillLuma(fill, 0xFFFFFF, 0f);
        return GlassLook.contrastRatio(lightInkLuma(), plate);
    }

    static float blackInkContrastOnLightSolid(int glass, int effectiveClarity) {
        float fill = GlassLook.fillWhiteAlpha(false, glass, effectiveClarity);
        return GlassLook.blackInkContrastOnFill(fill, 0xFFFFFF, 1f);
    }

    static float blackInkContrastOnDarkPlate(int glass, int effectiveClarity) {
        float fill = GlassLook.fillWhiteAlpha(true, glass, effectiveClarity);
        return GlassLook.blackInkContrastOnFill(fill, 0xFFFFFF, 0f);
    }

    static float compositeFillLuma(float fillAlpha, int fillRgb, float backdropLuma) {
        float a = fillAlpha < 0f ? 0f : (fillAlpha > 1f ? 1f : fillAlpha);
        float fr = ((fillRgb >> 16) & 0xFF) / 255f;
        float fg = ((fillRgb >> 8) & 0xFF) / 255f;
        float fb = (fillRgb & 0xFF) / 255f;
        float r = fr * a + backdropLuma * (1f - a);
        float g = fg * a + backdropLuma * (1f - a);
        float b = fb * a + backdropLuma * (1f - a);
        return GlassLook.lumaRgb(r, g, b);
    }
}
