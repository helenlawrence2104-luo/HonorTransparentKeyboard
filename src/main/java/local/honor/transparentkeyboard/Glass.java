package local.honor.transparentkeyboard;

import android.graphics.BlendMode;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;

/**
 * iOS 26 "Liquid Glass" style tiles for keys and toolbar buttons.
 *
 * Parameters and the highlight AGSL shader are ported from Kyant0/AndroidLiquidGlass ("backdrop"),
 * Copyright 2025 Kyant, licensed under the Apache License, Version 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0). Ported pieces (modified: one-sided top/bottom weighting, angle 75deg, falloff 2): RoundedRectSDF + DefaultHighlightShader
 * (internal/Shaders.kt), Highlight defaults (white 50% alpha, BlendMode.PLUS, width 0.5dp, blur width/2,
 * angle 45deg), Shadow defaults (black 10%, offset radius/6), InnerShadow defaults (black 15%, offset = radius),
 * and surface colours from the catalog (LiquidButton surface white 30%, ControlCenter container black 5%,
 * bottom tabs container #FAFAFA/#121212 at 40%). Modified: drawn with android.graphics on the IME canvas,
 * radii scaled down for keyboard-sized tiles. The lens/refraction effect is NOT ported because the IME
 * cannot sample the other app's pixels behind its window (the system window blur already frosts them).
 */
final class Glass {
    private static final String SDF =
        "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {\n" +
        "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n" +
        "    float outside = length(max(cornerCoord, 0.0)) - radius;\n" +
        "    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n" +
        "    return outside + inside;\n" +
        "}\n" +
        "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n" +
        "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n" +
        "    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {\n" +
        "        return sign(coord) * normalize(max(cornerCoord, 0.0));\n" +
        "    } else {\n" +
        "        float gradX = step(cornerCoord.y, cornerCoord.x);\n" +
        "        return sign(coord) * float2(gradX, 1.0 - gradX);\n" +
        "    }\n" +
        "}\n";
    private static final String HIGHLIGHT = SDF +
        "uniform float2 size;\n" +
        "uniform float radius;\n" +
        "layout(color) uniform half4 color;\n" +
        "uniform float angle;\n" +
        "uniform float falloff;\n" +
        "half4 main(float2 coord) {\n" +
        "    float2 halfSize = size * 0.5;\n" +
        "    float2 centeredCoord = coord - halfSize;\n" +
        "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n" +
        "    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);\n" +
        "    float2 normal = float2(cos(angle), sin(angle));\n" +
        "    float d = dot(grad, normal);\n" +
        "    float intensity = pow(abs(d), falloff) * (d < 0.0 ? 1.0 : 0.55);\n" +
        "    return color * intensity;\n" +
        "}\n";

    // ---- tuned values (Kyant backdrop defaults, scaled for ~30-45dp keys) ----
    static final float HIGHLIGHT_WIDTH_DP = 1.0f;     // default 0.5dp; 1dp reads better at key size
    static final float HIGHLIGHT_ALPHA = 0.6f;        // HighlightStyle.Default colour alpha
    static final float HIGHLIGHT_ANGLE_DEG = 75f;     // Kyant default 45deg; 75deg = light from above (top edge brightest)
    static final float HIGHLIGHT_FALLOFF = 2f;        // falloff 2 as in the catalog ControlCenter tiles
    static final float SHADOW_RADIUS_DP = 8f;         // Shadow default 24dp, scaled for small tiles
    static final float SHADOW_ALPHA = 0.10f;          // Shadow default black 10%
    static final float INNER_SHADOW_RADIUS_DP = 8f;   // InnerShadow(radius = 8dp) as used for small catalog items
    static final float INNER_SHADOW_ALPHA = 0f;       // InnerShadow disabled: read as an inset frame at key size
    static final float SURFACE_LIGHT = 0.22f;         // LiquidButton surface White 30%, lowered so dark backdrops stay dark
    static final float SURFACE_DARK = 0.10f;          // bottom-tab indicator White 10% (dark)
    static final float PANEL_LIGHT = 0.05f;           // ControlCenter container Black 5%
    static final float PANEL_DARK = 0.20f;            // v1.6: black 20% in dark mode (#121212 lifted OLED black = fog)

    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path PATH = new Path();
    private static final Path HOLE = new Path();
    private static final Matrix M = new Matrix();
    private static RuntimeShader highlightShader;
    private static boolean shaderFailed;
    private static BlurMaskFilter shadowBlur, innerBlur, rimBlur;
    private static float shadowBlurR = -1, innerBlurR = -1, rimBlurR = -1;
    static volatile boolean night;
    private Glass() {}

    private static int a(float v) { return Math.max(0, Math.min(255, Math.round(v * 255f))); }
    private static int argb(float alpha, int rgb) { return (a(alpha) << 24) | (rgb & 0xFFFFFF); }
    private static BlurMaskFilter blur(int which, float r, BlurMaskFilter.Blur style) {
        if (which == 0) { if (shadowBlurR != r) { shadowBlur = new BlurMaskFilter(r, style); shadowBlurR = r; } return shadowBlur; }
        if (which == 1) { if (innerBlurR != r) { innerBlur = new BlurMaskFilter(r, style); innerBlurR = r; } return innerBlur; }
        if (rimBlurR != r) { rimBlur = new BlurMaskFilter(r, style); rimBlurR = r; } return rimBlur;
    }

    /** Panel tint drawn behind all keys. */
    static int panelTint(int glass) {
        float f = glass / 100f;
        return night ? argb(PANEL_DARK * f, 0x000000) : argb(PANEL_LIGHT * f, 0x000000);
    }

    /** Drop shadow (Kyant Shadow): drawn BEFORE the plate, outside the shape only. */
    static void shadow(Canvas c, RectF r, float radius, int glass, float dp) {
        if (glass <= 0 || r.width() <= 0 || r.height() <= 0) return;
        float f = glass / 100f;
        float br = SHADOW_RADIUS_DP * dp;
        PATH.reset(); PATH.addRoundRect(r, radius, radius, Path.Direction.CW);
        int save = c.save();
        try { c.clipOutPath(PATH); } catch (Throwable t) { c.restoreToCount(save); return; }
        P.reset(); P.setAntiAlias(true); P.setStyle(Paint.Style.FILL);
        P.setMaskFilter(blur(0, br / 2f, BlurMaskFilter.Blur.NORMAL));
        P.setColor(argb(SHADOW_ALPHA * 1.6f * f, 0x000000));
        RectF s = new RectF(r); s.offset(0, br / 6f);
        c.drawRoundRect(s, radius, radius, P);
        P.setMaskFilter(null);
        c.restoreToCount(save);
    }

    /** Surface, inner shadow and specular rim; drawn AFTER the (faded) original plate, under the glyph. */
    static void overlay(Canvas c, RectF r, float radius, int glass, float dp) {
        if (glass <= 0 || r.width() <= 0 || r.height() <= 0) return;
        float f = glass / 100f;
        PATH.reset(); PATH.addRoundRect(r, radius, radius, Path.Direction.CW);
        int save = c.save();
        c.clipPath(PATH);
        P.reset(); P.setAntiAlias(true); P.setStyle(Paint.Style.FILL);
        // surface colour
        P.setColor(argb((night ? SURFACE_DARK : SURFACE_LIGHT) * f, 0xFFFFFF));
        c.drawRect(r, P);
        if (INNER_SHADOW_ALPHA > 0) {
        // inner shadow (Kyant InnerShadow): shape offset down by its radius, area outside it shaded, blurred
        float ir = INNER_SHADOW_RADIUS_DP * dp;
        HOLE.reset(); HOLE.setFillType(Path.FillType.EVEN_ODD);
        RectF big = new RectF(r); big.inset(-ir * 3, -ir * 3);
        HOLE.addRect(big, Path.Direction.CW);
        RectF moved = new RectF(r); moved.offset(0, ir * 0.5f);
        HOLE.addRoundRect(moved, radius, radius, Path.Direction.CW);
        P.setMaskFilter(blur(1, ir / 2f, BlurMaskFilter.Blur.NORMAL));
        P.setColor(argb(INNER_SHADOW_ALPHA * f, 0x000000));
        c.drawPath(HOLE, P);
        P.setMaskFilter(null);
        }
        c.restoreToCount(save);
        // specular highlight rim (Kyant Highlight.Default + DefaultHighlightShader), additive
        float w = Math.max(1f, HIGHLIGHT_WIDTH_DP * dp);
        P.reset(); P.setAntiAlias(true); P.setStyle(Paint.Style.STROKE); P.setStrokeWidth(w);
        P.setMaskFilter(blur(2, w / 2f, BlurMaskFilter.Blur.NORMAL));
        Shader shader = highlight(r, radius, HIGHLIGHT_ALPHA * f);
        if (shader != null) P.setShader(shader);
        else P.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
            new int[]{argb(HIGHLIGHT_ALPHA * f, 0xFFFFFF), 0, argb(HIGHLIGHT_ALPHA * f, 0xFFFFFF)}, null, Shader.TileMode.CLAMP));
        try { P.setBlendMode(BlendMode.PLUS); } catch (Throwable ignored) {}
        RectF i = new RectF(r); i.inset(w / 2f, w / 2f);
        c.drawRoundRect(i, Math.max(0, radius - w / 2f), Math.max(0, radius - w / 2f), P);
        try { P.setBlendMode(null); } catch (Throwable ignored) {}
        P.setMaskFilter(null); P.setShader(null);
        // hairline outer edge so tiles keep a silhouette on pure white (PLUS highlight is invisible there)
        float hair = Math.max(1f, 0.5f * dp);
        P.setStrokeWidth(hair); P.setColor(argb(0.10f * f, 0x000000));
        RectF o = new RectF(r); o.inset(-hair / 2f, -hair / 2f);
        c.drawRoundRect(o, radius + hair / 2f, radius + hair / 2f, P);
        P.setStyle(Paint.Style.FILL);
    }

    private static Shader highlight(RectF r, float radius, float alpha) {
        if (shaderFailed || Build.VERSION.SDK_INT < 33) return null;
        try {
            if (highlightShader == null) highlightShader = new RuntimeShader(HIGHLIGHT);
            highlightShader.setFloatUniform("size", r.width(), r.height());
            highlightShader.setFloatUniform("radius", radius);
            highlightShader.setColorUniform("color", argb(alpha, 0xFFFFFF));
            highlightShader.setFloatUniform("angle", (float)Math.toRadians(HIGHLIGHT_ANGLE_DEG));
            highlightShader.setFloatUniform("falloff", HIGHLIGHT_FALLOFF);
            M.setTranslate(r.left, r.top);
            highlightShader.setLocalMatrix(M);
            return highlightShader;
        } catch (Throwable t) { shaderFailed = true; return null; }
    }
}