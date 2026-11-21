package local.honor.transparentkeyboard;

/**
 * Testable default/solid-color glass plate recipe for IME keys and toolbar.
 * No Android types. Does not change saved preferences or haptics.
 * 1.8.9 reduces dirty gray veils, double strokes and heavy emboss used in 1.8.8;
 * it does not invent vendor refraction parameters or colored rims.
 */
final class GlassLook {
    /** Kyant Highlight default width; 1.8.8 used 1.0dp which read as a thick white rim. */
    static final float HIGHLIGHT_WIDTH_DP = 0.5f;
    static final float HIGHLIGHT_ALPHA = 0.32f;
    static final float HIGHLIGHT_ANGLE_DEG = 75f;
    static final float HIGHLIGHT_FALLOFF = 2f;
    static final float SHADOW_RADIUS_DP = 5f;
    static final float SHADOW_ALPHA = 0.10f;
    static final float SHADOW_STRENGTH = 1.0f;
    static final float SHADOW_CLARITY_EXTRA = 0.25f;
    static final float SURFACE_LIGHT = 0.16f;
    static final float SURFACE_DARK = 0.14f;
    static final float PANEL_LIGHT = 0.04f;
    static final float PANEL_DARK = 0.10f;
    static final int VEIL_RGB = 0x20242A;

    private GlassLook() {}

    static float unit(int v) {
        if (v <= 0) return 0f;
        if (v >= 100) return 1f;
        return v / 100f;
    }

    /** White frost only. No dark veil (that mix read as dirty gray on solid colors). */
    static float fillWhiteAlpha(boolean night, int glass, int effectiveClarity) {
        float f = unit(glass);
        float boost = unit(effectiveClarity);
        if (night) return SURFACE_DARK * f;
        return SURFACE_LIGHT * f * (1f - 0.50f * boost);
    }

    static float veilAlpha(boolean night, int glass, int effectiveClarity) {
        return 0f;
    }

    static float highlightAlpha(int glass, int effectiveClarity) {
        float f = unit(glass);
        float boost = unit(effectiveClarity);
        return HIGHLIGHT_ALPHA * f * (1f - 0.55f * boost);
    }

    static float shadowAlpha(int glass, int effectiveClarity) {
        float f = unit(glass);
        float boost = unit(effectiveClarity);
        return SHADOW_ALPHA * (SHADOW_STRENGTH + SHADOW_CLARITY_EXTRA * boost) * f;
    }

    static float hairlineAlpha(int glass, int effectiveClarity) {
        float f = unit(glass);
        float boost = unit(effectiveClarity);
        return (0.08f + 0.10f * boost) * f;
    }

    /** 1.8.8 drew a second inner black stroke when clarity > 0. Disabled in 1.8.9. */
    static float innerStrokeAlpha(int glass, int effectiveClarity) {
        return 0f;
    }

    static boolean innerStrokeEnabled() {
        return false;
    }

    static float panelTintAlpha(boolean night, int glass, int effectiveClarity) {
        float f = unit(glass);
        float boost = unit(effectiveClarity);
        if (night) return (PANEL_DARK + 0.02f * boost) * f;
        return (PANEL_LIGHT + 0.04f * boost) * f;
    }

    static float shadowOffsetFactor() {
        return 1f / 8f;
    }

    /** sRGB channel 0-1 to relative luminance contribution. */
    static float linearize(float c) {
        if (c <= 0.04045f) return c / 12.92f;
        return (float) Math.pow((c + 0.055f) / 1.055f, 2.4);
    }

    static float lumaRgb(float r, float g, float b) {
        return 0.2126f * linearize(r) + 0.7152f * linearize(g) + 0.0722f * linearize(b);
    }

    static float contrastRatio(float l1, float l2) {
        float hi = Math.max(l1, l2);
        float lo = Math.min(l1, l2);
        return (hi + 0.05f) / (lo + 0.05f);
    }

    /**
     * Composite a source-over fill of given alpha and RGB over an opaque backdrop luma,
     * then contrast against black ink. Used to document letter contrast, not to invent rims.
     */
    static float blackInkContrastOnFill(float fillAlpha, int fillRgb, float backdropLuma) {
        float a = fillAlpha < 0f ? 0f : (fillAlpha > 1f ? 1f : fillAlpha);
        float fr = ((fillRgb >> 16) & 0xFF) / 255f;
        float fg = ((fillRgb >> 8) & 0xFF) / 255f;
        float fb = (fillRgb & 0xFF) / 255f;
        float br = backdropLuma, bg = backdropLuma, bb = backdropLuma;
        float r = fr * a + br * (1f - a);
        float g = fg * a + bg * (1f - a);
        float b = fb * a + bb * (1f - a);
        return contrastRatio(lumaRgb(r, g, b), 0f);
    }

    // --- 1.8.8 formulas kept only so tests can prove 1.8.9 is weaker on dirt/emboss ---
    static float v188FillWhiteAlpha(boolean night, int glass, int effectiveClarity) {
        float f = unit(glass);
        float boost = unit(effectiveClarity);
        if (boost > 0f && !night) return 0.22f * f * (1f - 0.50f * boost);
        if (boost > 0f && night) return 0.10f * f;
        return (night ? 0.10f : 0.22f) * f;
    }

    static float v188VeilAlpha(boolean night, int glass, int effectiveClarity) {
        float f = unit(glass);
        float boost = unit(effectiveClarity);
        if (boost <= 0f) return 0f;
        return (night ? 0.10f : 0.12f) * f * boost;
    }

    static float v188HighlightAlpha(int glass, int effectiveClarity) {
        return 0.60f * unit(glass) * (1f - 0.55f * unit(effectiveClarity));
    }

    static float v188ShadowAlpha(int glass, int effectiveClarity) {
        return 0.10f * (1.6f + 0.9f * unit(effectiveClarity)) * unit(glass);
    }

    static float v188HairlineAlpha(int glass, int effectiveClarity) {
        return (0.10f + 0.32f * unit(effectiveClarity)) * unit(glass);
    }

    static float v188InnerStrokeAlpha(int glass, int effectiveClarity) {
        float boost = unit(effectiveClarity);
        if (boost <= 0f) return 0f;
        return 0.16f * unit(glass) * boost;
    }
}
