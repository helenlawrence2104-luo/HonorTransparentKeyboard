package local.honor.transparentkeyboard;

/**
 * Shared visual control formulas used by KeyboardHook and tests.
 * Does not write preferences and does not invent vendor refraction parameters.
 *
 * keys: underlying native plate fade only (0 opaque, 100 plate skipped, labels still draw).
 * glass: overlay / shadow / highlight / hairline / panel-tint strength only.
 * They do not raise or clamp each other. 186/188 used max(keys, min(100, 30+glass)),
 * which made keys=45 and glass=100 identical to keys=100. That curve is kept only
 * as v188PlateTransparency for compatibility tests. Saved store values are unchanged;
 * the same stored pair now fades the plate at the saved keys value.
 */
final class VisualControls {
    private VisualControls() {}

    /** 186/188 plate fade. Kept only to prove the controls-fix difference. */
    static int v188PlateTransparency(int keyTransparency, int glass) {
        int g = glass;
        return g > 0 ? Math.max(keyTransparency, Math.min(100, 30 + g)) : keyTransparency;
    }

    /** Plate fade follows the keys slider only. Glass is ignored. */
    static int plateTransparency(int keyTransparency) {
        return clamp(keyTransparency, 0, 100);
    }

    /**
     * @deprecated Use {@link #plateTransparency(int)}. The glass argument is ignored
     * so existing KeyboardHook call sites stay compile-safe during the switch.
     */
    static int effectiveTransparency(int keyTransparency, int glass) {
        return plateTransparency(keyTransparency);
    }

    /**
     * Alpha passed to Canvas.saveLayerAlpha for the native plate.
     * keys 0 = 255 (and the hook should not open a layer).
     * keys 100 = 0 (and the hook should skip the plate method, not the glyphs).
     */
    static int plateLayerAlpha(int keyTransparency) {
        int t = plateTransparency(keyTransparency);
        if (t <= 0) return 255;
        if (t >= 100) return 0;
        return Math.max(0, Math.min(255, Math.round(255f * (100 - t) / 100f)));
    }

    static boolean shouldOpenPlateLayer(int keyTransparency) {
        int t = plateTransparency(keyTransparency);
        return t > 0 && t < 100;
    }

    /** Native degree 60 keeps its original mask; glass may thin the milky white mask so tiles can read. This does not change plate fade. */
    static float maskScale(int degree, int glass) {
        float scale = (100 - degree) / 40.0f;
        if (glass > 0) scale *= 1f - 0.6f * glass / 100f;
        return scale;
    }

    static boolean blurOverrideApplies(int blur) {
        return blur >= 0;
    }

    /** Same rule Glass.effectiveClarity uses, without Android types. */
    static int effectiveClarity(int clarity, boolean night, boolean clarityInNight) {
        if (clarity <= 0) return 0;
        if (night && !clarityInNight) return 0;
        return clamp(clarity, 0, 100);
    }

    /**
     * n1.M draws the toolbar plate only. Labels go through n1.y / proven drawText.
     * Fade and overlay stay on; plate skip at keys 100 does not skip glyphs.
     */
    static final boolean N1_M_FADE_PLATE = true;
    static final boolean N1_M_APPLY_OVERLAY = true;

    /** d0 still draws labels; never skip the whole method at keys 100. Fade is g0.Y only. */
    static final boolean D0_SKIP_METHOD_AT_FULL_TRANSPARENCY = false;
    static final boolean Y_SKIP_PLATE_AT_FULL_TRANSPARENCY = true;

    static boolean shouldSkipPlateMethod(int transparency, boolean skipAtFull) {
        return skipAtFull && plateTransparency(transparency) >= 100;
    }

    static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    /**
     * Outputs the hook actually uses for one settings combination.
     * Plate fields depend only on keys. Material fields depend on glass, clarity, and night.
     */
    static final class HookDraw {
        final int keys;
        final int glass;
        final int clarity;
        final boolean night;
        final boolean clarityInNight;
        final int plateTransparency;
        final int plateLayerAlpha;
        final boolean openPlateLayer;
        final boolean skipPlateMethod;
        final boolean overlayApplies;
        final int effectiveClarity;
        final float fillWhite;
        final float veil;
        final float highlight;
        final float shadow;
        final float hairline;
        final float innerStroke;
        final float panelTint;

        HookDraw(int keys, int glass, int clarity, boolean night, boolean clarityInNight) {
            this.keys = keys;
            this.glass = glass;
            this.clarity = clarity;
            this.night = night;
            this.clarityInNight = clarityInNight;
            this.plateTransparency = plateTransparency(keys);
            this.plateLayerAlpha = plateLayerAlpha(keys);
            this.openPlateLayer = shouldOpenPlateLayer(keys);
            this.skipPlateMethod = shouldSkipPlateMethod(keys, true);
            this.overlayApplies = glass > 0;
            this.effectiveClarity = effectiveClarity(clarity, night, clarityInNight);
            this.fillWhite = GlassLook.fillWhiteAlpha(night, glass, this.effectiveClarity);
            this.veil = GlassLook.veilAlpha(night, glass, this.effectiveClarity);
            this.highlight = GlassLook.highlightAlpha(glass, this.effectiveClarity);
            this.shadow = GlassLook.shadowAlpha(glass, this.effectiveClarity);
            this.hairline = GlassLook.hairlineAlpha(glass, this.effectiveClarity);
            this.innerStroke = GlassLook.innerStrokeAlpha(glass, this.effectiveClarity);
            this.panelTint = GlassLook.panelTintAlpha(night, glass, this.effectiveClarity);
        }

        String materialKey() {
            return night + "/" + clarityInNight + "/" + glass + "/" + effectiveClarity
                    + "/" + fillWhite + "/" + veil + "/" + highlight + "/" + shadow
                    + "/" + hairline + "/" + innerStroke + "/" + panelTint + "/" + overlayApplies;
        }

        String plateKey() {
            return plateTransparency + "/" + plateLayerAlpha + "/" + openPlateLayer + "/" + skipPlateMethod;
        }
    }

    static HookDraw hookDraw(int keys, int glass, int clarity, boolean night, boolean clarityInNight) {
        return new HookDraw(keys, glass, clarity, night, clarityInNight);
    }

    /**
     * Public IME status line. Session tokens are request/lifecycle facts,
     * not compositor or refraction success. Only fixed labels and numeric/status tokens.
     */
    static String publicStatusLine(boolean apply, int degree, int keys, int glass, int clarity,
            int material, int blur, boolean hapticOn, boolean honorHooked, boolean hnOn,
            String sessionStatus, int pid, String diag) {
        String msg = (apply ? "已应用" : "已连接")
                + " · 背景 " + degree
                + " · 按键 " + keys
                + " · 玻璃 " + glass
                + " · 白底 " + clarity
                + " · " + (material == 0 ? "液态" : "磨砂")
                + " · 模糊 " + (blur < 0 ? "自动" : String.valueOf(blur))
                + " · 震动" + (hapticOn ? "开" : "关")
                + (honorHooked ? " · 荣耀路径已钩" : "")
                + " · 系统整窗玻璃测试" + (hnOn ? "开" : "关")
                + " · " + sessionStatus
                + " · PID " + pid;
        if (diag != null && !diag.isEmpty()) msg = msg + " · " + diag;
        return msg;
    }
}
