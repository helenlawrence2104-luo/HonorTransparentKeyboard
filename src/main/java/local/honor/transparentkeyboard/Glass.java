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
 *
 * v1.8: optional light-backdrop clarity (clarity 0 = v1.7 look).
 * v1.8.9: default/solid-color plates drop the dark veil and second inner stroke, and use a thinner
 * highlight/shadow so keys and toolbar read less like thick gray plastic. Glyph color is unchanged.
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
    private static final String SOLID_GLASS = SDF +
        "uniform float2 size;\n" +
        "uniform float radius;\n" +
        "uniform float strength;\n" +
        "uniform float darkBackdrop;\n" +
        "\n" +
        "half4 main(float2 coord) {\n" +
        "    float2 halfSize = size * 0.5;\n" +
        "    float2 point = coord - halfSize;\n" +
        "    float distanceInside = max(0.0, -sdRoundedRect(point, halfSize, radius));\n" +
        "    float band = max(2.0, min(size.x, size.y) * 0.15);\n" +
        "    float bevel = 1.0 - smoothstep(0.0, band, distanceInside);\n" +
        "    float edge = 1.0 - smoothstep(0.0, band * 0.28, distanceInside);\n" +
        "    float2 normal = gradSdRoundedRect(point, halfSize, radius);\n" +
        "    float top = max(0.0, dot(normal, float2(-0.42, -0.91)));\n" +
        "    float bottom = max(0.0, dot(normal, float2(0.42, 0.91)));\n" +
        "    float shade = (0.036 + bevel * (0.045 + 0.093 * bottom) + edge * 0.016) * strength;\n" +
        "    float shine = (0.038 + bevel * (0.023 + 0.15 * top) + edge * 0.031) * strength;\n" +
        "    float alpha = mix(shade, shine, darkBackdrop);\n" +
        "    half3 rgb = half3(mix(float3(0.15, 0.20, 0.29), float3(0.93, 0.96, 1.0), darkBackdrop));\n" +
        "    return half4(rgb * half(alpha), half(alpha));\n" +
        "}\n";

    // Visual constants live in GlassLook so default/solid-color recipes can be unit-tested.
    // 1.8.9: thinner rim, no dirty veil, no second inner stroke, lighter shadow. Same Kyant shader.
    static final float HIGHLIGHT_WIDTH_DP = GlassLook.HIGHLIGHT_WIDTH_DP;
    static final float HIGHLIGHT_ALPHA = GlassLook.HIGHLIGHT_ALPHA;
    static final float HIGHLIGHT_ANGLE_DEG = GlassLook.HIGHLIGHT_ANGLE_DEG;
    static final float HIGHLIGHT_FALLOFF = GlassLook.HIGHLIGHT_FALLOFF;
    static final float SHADOW_RADIUS_DP = GlassLook.SHADOW_RADIUS_DP;
    static final float INNER_SHADOW_RADIUS_DP = 8f;
    static final float INNER_SHADOW_ALPHA = 0f;

    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path PATH = new Path();
    private static final Path HOLE = new Path();
    private static final Matrix M = new Matrix();
    private static RuntimeShader highlightShader;
    private static RuntimeShader solidGlassShader;
    private static boolean solidGlassUnavailable;
    private static boolean shaderFailed;
    private static BlurMaskFilter shadowBlur, innerBlur, rimBlur;
    private static float shadowBlurR = -1, innerBlurR = -1, rimBlurR = -1;
    static volatile boolean night;
    /** 0 = v1.7 appearance; 1-100 strengthens layer edges and light-backdrop contrast without going opaque. */
    static volatile int clarity = PreviewSettings.DEFAULT_CLARITY;
    /** When true, also apply the clarity recipe in system night mode (e.g. dark system, light app). */
    static volatile boolean clarityInNight = false;
    private Glass() {}

    static int effectiveClarity() {
        if (clarity <= 0) return 0;
        if (night && !clarityInNight) return 0;
        return Math.max(0, Math.min(100, clarity));
    }

    private static int a(float v) { return Math.max(0, Math.min(255, Math.round(v * 255f))); }
    private static int argb(float alpha, int rgb) { return (a(alpha) << 24) | (rgb & 0xFFFFFF); }

    static boolean drawSolidGlass(Canvas canvas, RectF bounds, float radius, float strength,
                                  boolean darkBackdrop, Paint paint) {
        boolean gradientFallback = !canvas.isHardwareAccelerated() || solidGlassUnavailable;
        if (!gradientFallback) {
            try {
                if (solidGlassShader == null) solidGlassShader = new RuntimeShader(SOLID_GLASS);
                solidGlassShader.setFloatUniform("size", bounds.width(), bounds.height());
                solidGlassShader.setFloatUniform("radius", radius);
                solidGlassShader.setFloatUniform("strength", strength);
                solidGlassShader.setFloatUniform("darkBackdrop", darkBackdrop ? 1 : 0);
                M.setTranslate(bounds.left, bounds.top);
                solidGlassShader.setLocalMatrix(M);
                paint.setShader(solidGlassShader);
                canvas.drawRoundRect(bounds, radius, radius, paint);
                return true;
            } catch (IllegalArgumentException | UnsupportedOperationException error) {
                solidGlassUnavailable = true;
            } finally {
                paint.setShader(null);
            }
        }

        int rgb = darkBackdrop ? 0xf0f5fd : 0x263247;
        int[] colors = {
            argb((darkBackdrop ? 0.22f : 0.10f) * strength, rgb),
            argb(0.04f * strength, rgb),
            argb(0.04f * strength, rgb),
            argb((darkBackdrop ? 0.10f : 0.19f) * strength, rgb)
        };
        paint.setShader(new LinearGradient(bounds.left, bounds.top, bounds.left, bounds.bottom,
                colors, new float[]{0, 0.18f, 0.82f, 1}, Shader.TileMode.CLAMP));
        try {
            canvas.drawRoundRect(bounds, radius, radius, paint);
        } finally {
            paint.setShader(null);
        }
        return false;
    }

    private static BlurMaskFilter blur(int which, float r, BlurMaskFilter.Blur style) {
        if (which == 0) { if (shadowBlurR != r) { shadowBlur = new BlurMaskFilter(r, style); shadowBlurR = r; } return shadowBlur; }
        if (which == 1) { if (innerBlurR != r) { innerBlur = new BlurMaskFilter(r, style); innerBlurR = r; } return innerBlur; }
        if (rimBlurR != r) { rimBlur = new BlurMaskFilter(r, style); rimBlurR = r; } return rimBlur;
    }

    /** Panel tint drawn behind all keys. */
    static int panelTint(int glass) {
        return argb(GlassLook.panelTintAlpha(night, glass, effectiveClarity()), 0x000000);
    }

    /** Drop shadow (Kyant Shadow): drawn BEFORE the plate, outside the shape only. */
    static void shadow(Canvas c, RectF r, float radius, int glass, float dp) {
        if (glass <= 0 || r.width() <= 0 || r.height() <= 0) return;
        float br = SHADOW_RADIUS_DP * dp;
        PATH.reset(); PATH.addRoundRect(r, radius, radius, Path.Direction.CW);
        int save = c.save();
        try {
            try { c.clipOutPath(PATH); } catch (Throwable t) { return; }
            P.reset(); P.setAntiAlias(true); P.setStyle(Paint.Style.FILL);
            P.setMaskFilter(blur(0, br / 2f, BlurMaskFilter.Blur.NORMAL));
            P.setColor(argb(GlassLook.shadowAlpha(glass, effectiveClarity()), 0x000000));
            RectF s = new RectF(r); s.offset(0, br * GlassLook.shadowOffsetFactor());
            c.drawRoundRect(s, radius, radius, P);
            P.setMaskFilter(null);
        } finally {
            c.restoreToCount(save);
        }
    }

    /** Surface, inner shadow and specular rim; drawn AFTER the (faded) original plate, under the glyph. */
    static void overlay(Canvas c, RectF r, float radius, int glass, float dp) {
        if (glass <= 0 || r.width() <= 0 || r.height() <= 0) return;
        PATH.reset(); PATH.addRoundRect(r, radius, radius, Path.Direction.CW);
        int save = c.save();
        try {
        c.clipPath(PATH);
        P.reset(); P.setAntiAlias(true); P.setStyle(Paint.Style.FILL);
        int clarityNow = effectiveClarity();
        float fill = GlassLook.fillWhiteAlpha(night, glass, clarityNow);
        if (fill > 0f) {
            P.setColor(argb(fill, 0xFFFFFF));
            c.drawRect(r, P);
        }
        float veil = GlassLook.veilAlpha(night, glass, clarityNow);
        if (veil > 0f) {
            P.setColor(argb(veil, GlassLook.VEIL_RGB));
            c.drawRect(r, P);
        }
        if (INNER_SHADOW_ALPHA > 0) {
        // inner shadow (Kyant InnerShadow): shape offset down by its radius, area outside it shaded, blurred
        float ir = INNER_SHADOW_RADIUS_DP * dp;
        HOLE.reset(); HOLE.setFillType(Path.FillType.EVEN_ODD);
        RectF big = new RectF(r); big.inset(-ir * 3, -ir * 3);
        HOLE.addRect(big, Path.Direction.CW);
        RectF moved = new RectF(r); moved.offset(0, ir * 0.5f);
        HOLE.addRoundRect(moved, radius, radius, Path.Direction.CW);
        P.setMaskFilter(blur(1, ir / 2f, BlurMaskFilter.Blur.NORMAL));
        P.setColor(argb(INNER_SHADOW_ALPHA * (glass / 100f), 0x000000));
        c.drawPath(HOLE, P);
        P.setMaskFilter(null);
        }
        } finally {
        c.restoreToCount(save);
        }
        // specular highlight rim (Kyant Highlight.Default + DefaultHighlightShader), additive
        float w = Math.max(1f, HIGHLIGHT_WIDTH_DP * dp);
        float hl = GlassLook.highlightAlpha(glass, effectiveClarity());
        P.reset(); P.setAntiAlias(true); P.setStyle(Paint.Style.STROKE); P.setStrokeWidth(w);
        P.setMaskFilter(blur(2, w / 2f, BlurMaskFilter.Blur.NORMAL));
        Shader shader = c.isHardwareAccelerated() ? highlight(r, radius, hl) : null;
        if (shader != null) P.setShader(shader);
        else P.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
            new int[]{argb(hl, 0xFFFFFF), 0, argb(hl, 0xFFFFFF)}, null, Shader.TileMode.CLAMP));
        try { P.setBlendMode(BlendMode.PLUS); } catch (Throwable ignored) {}
        RectF i = new RectF(r); i.inset(w / 2f, w / 2f);
        c.drawRoundRect(i, Math.max(0, radius - w / 2f), Math.max(0, radius - w / 2f), P);
        try { P.setBlendMode(null); } catch (Throwable ignored) {}
        P.setMaskFilter(null); P.setShader(null);
        // hairline outer edge so tiles keep a silhouette on pure white (PLUS highlight is invisible there)
        float hair = Math.max(1f, 0.5f * dp);
        P.setStrokeWidth(hair); P.setColor(argb(GlassLook.hairlineAlpha(glass, effectiveClarity()), 0x000000));
        RectF o = new RectF(r); o.inset(-hair / 2f, -hair / 2f);
        c.drawRoundRect(o, radius + hair / 2f, radius + hair / 2f, P);
        float innerA = GlassLook.innerStrokeAlpha(glass, effectiveClarity());
        if (GlassLook.innerStrokeEnabled() && innerA > 0f) {
            float inner = Math.max(1f, 0.7f * dp);
            P.setStrokeWidth(inner);
            P.setColor(argb(innerA, 0x000000));
            RectF ir = new RectF(r); ir.inset(inner, inner);
            float rr = Math.max(0, radius - inner);
            c.drawRoundRect(ir, rr, rr, P);
        }
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
