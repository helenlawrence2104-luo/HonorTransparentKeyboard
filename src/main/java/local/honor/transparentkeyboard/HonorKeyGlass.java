package local.honor.transparentkeyboard;

import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.util.LruCache;
import android.view.View;
import java.lang.ref.WeakReference;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Uses the Honor window backdrop for the original keyboard plate draw calls. */
final class HonorKeyGlass {
    private static final String HONOR = "com.hihonor.android.graphics.material.";
    private static final String ANDROID = "android.graphics.material.";
    private static final String ATOMIC = "atomiceffect.";
    private static final LruCache<String, RenderNode> plates = new LruCache<>(176);
    private static final Paint contrastPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static WeakReference<Object> root = new WeakReference<>(null);
    private static WeakReference<View> visiblePanel = new WeakReference<>(null);
    // Keep the backdrop active with the near-zero radius used by Honor for clear tiles.
    private static final float BACKDROP_RADIUS = 0.1f;
    private static final float KEY_REFRACTION = 0.40f;
    private static final float KEY_DEPTH = 0.87f;
    private static final float KEY_THICKNESS = 0.90f;
    private static boolean failed;
    private static String lastPanel;
    private static String lastContrast;
    private static final java.util.Set<String> keySlots = new java.util.HashSet<>();
    private static final java.util.Set<String> toolbarSlots = new java.util.HashSet<>();

    private HonorKeyGlass() {}

    static void prepare(View panel, boolean enabled) {
        if (failed || !panel.isHardwareAccelerated()) return;
        try {
            Object current = XposedHelpers.callMethod(panel, "getViewRootImpl");
            if (current == null) return;
            Object previous = root.get();
            if (previous != null && (previous != current || !enabled)) {
                setRootMaterial(previous, null);
                root.clear();
            }
            if (!enabled) return;
            if (!panel.isShown()) return;
            if (root.get() == current) return;
            Object material = create(ANDROID + "LiquidGlassMaterial");
            Object blur = create(ANDROID + ATOMIC + "BlurParam");
            XposedHelpers.callMethod(blur, "setRadius", BACKDROP_RADIUS);
            XposedHelpers.callMethod(material, "setBlurParam", blur);
            setRootMaterial(current, material);
            if (previous != current) {
                plates.evictAll();
                keySlots.clear();
                toolbarSlots.clear();
            }
            root = new WeakReference<>(current);
            XposedBridge.log("HonorGlass: keyboard backdrop ready, blur=" + BACKDROP_RADIUS);
        } catch (Throwable error) {
            fail(error);
        }
    }

    static void redrawPanel() {
        View panel = visiblePanel.get();
        if (panel != null) panel.invalidate();
    }

    static boolean drawPanel(View panel, Canvas canvas, RectF bounds, float corner,
                             int transparency, boolean night) {
        if (failed || root.get() == null || !canvas.isHardwareAccelerated()
                || bounds.isEmpty()) return false;
        int left = Math.round(bounds.left);
        int top = Math.round(bounds.top);
        int width = Math.round(bounds.width());
        int height = Math.round(bounds.height());
        int alpha = Math.round(115 * (100 - transparency) / 100f);
        int color = (alpha << 24) | (night ? 0x151823 : 0xffffff);
        String key = "panel:" + left + ":" + top + ":" + width + ":" + height + ":" + color;
        try {
            RenderNode base = plates.get(key);
            if (base == null) {
                base = createPlate(left, top, width, height, Math.round(corner), color, true, false);
                plates.put(key, base);
            }
            canvas.drawRenderNode(base);
            HonorBackdropTone.observe(panel);
            visiblePanel = new WeakReference<>(panel);
            if (!key.equals(lastPanel)) {
                lastPanel = key;
                XposedBridge.log("HonorGlass: native panel " + width + "x" + height
                        + " transparency=" + transparency + " alpha=" + alpha);
            }
            return true;
        } catch (Throwable error) {
            fail(error);
            return false;
        }
    }

    static boolean draw(Canvas canvas, RectF bounds, float corner, int transparency,
                        boolean night, boolean pressed, boolean toolbar) {
        if (failed || root.get() == null || transparency <= 0 || bounds.isEmpty()
                || !canvas.isHardwareAccelerated()) return false;
        int width = Math.round(bounds.width());
        int height = Math.round(bounds.height());
        if (width <= 0 || height <= 0) return false;
        int shortSide = Math.min(width, height);
        int radius = Math.round(toolbar ? corner : Math.max(corner, shortSide * 0.32f));
        int alpha = Math.round(210 * (100 - transparency) / 100f);
        if (pressed) alpha = Math.min(250, alpha + 50);
        int color = (alpha << 24) | (night ? 0x141722 : 0xffffff);
        int left = Math.round(bounds.left);
        int top = Math.round(bounds.top);
        String slot = (toolbar ? "toolbar:" : "key:") + left + ":" + top + ":" + width + ":" + height;
        String key = slot + ":" + radius + ":" + color;
        try {
            RenderNode plate = plates.get(key);
            if (plate == null) {
                plate = createPlate(left, top, width, height, radius, color, false, !toolbar);
                plates.put(key, plate);
            }
            canvas.drawRenderNode(plate);
            drawContrast(canvas, bounds, radius, transparency, night, false);
            java.util.Set<String> slots = toolbar ? toolbarSlots : keySlots;
            if (slots.size() < 160 && slots.add(slot)
                    && (slots.size() == 1 || slots.size() == 7
                    || slots.size() == 10 || slots.size() == 26 || slots.size() == 37)) {
                XposedBridge.log("HonorGlass: native " + (toolbar ? "toolbar" : "key")
                        + " distinct slots=" + slots.size() + " last=" + slot + " alpha=" + alpha
                        + " corner=" + radius + " refraction=" + (toolbar ? 0.22f : KEY_REFRACTION));
            }
            return true;
        } catch (Throwable error) {
            fail(error);
            return false;
        }
    }

    static boolean drawContrast(Canvas canvas, RectF bounds, float corner, int transparency,
                                boolean night, boolean fallback) {
        if (transparency <= 0 || bounds.isEmpty()) return false;
        HonorBackdropTone.Tone tone = HonorBackdropTone.current();
        float savedRadius = Math.min(corner, Math.min(bounds.width(), bounds.height()) / 2);
        boolean shadeGlass = fallback || tone == HonorBackdropTone.Tone.LIGHT
                || tone == HonorBackdropTone.Tone.DARK;
        String layer;
        if (shadeGlass) {
            boolean dark = tone == HonorBackdropTone.Tone.DARK
                    || (tone != HonorBackdropTone.Tone.LIGHT && night);
            contrastPaint.setColor(Color.WHITE);
            layer = Glass.drawSolidGlass(canvas, bounds, savedRadius,
                    transparency / 100f, dark, contrastPaint) ? "bevel" : "gradient";
        } else {
            int alpha = Math.round(16 * transparency / 100f);
            if (alpha == 0) return false;
            contrastPaint.setColor((alpha << 24) | (night ? 0xf0f5fd : 0x263247));
            canvas.drawRoundRect(bounds, savedRadius, savedRadius, contrastPaint);
            layer = "minimal tint";
        }
        String state = tone + " layer=" + layer + " fallback=" + fallback;
        if (!state.equals(lastContrast)) {
            lastContrast = state;
            XposedBridge.log("HonorGlass: key contrast drawn " + state);
        }
        return true;
    }

    private static RenderNode createPlate(int left, int top, int width, int height,
                                          int radius, int color, boolean panel, boolean keycap)
            throws Exception {
        RenderNode node = new RenderNode("Honor keyboard glass");
        node.setPosition(left, top, left + width, top + height);
        node.beginRecording().drawColor(Color.TRANSPARENT);
        node.endRecording();

        Object material = XposedHelpers.callStaticMethod(type(HONOR + "HnMaterialFactory"),
                "createMaterial", 2);
        Object blur = create(HONOR + ATOMIC + "BlurParamEx");
        XposedHelpers.callMethod(blur, "setRadius", panel || keycap ? BACKDROP_RADIUS : 0.4f);
        XposedHelpers.callMethod(blur, "setMultiMaskColors", new int[]{color},
                new BlendMode[]{BlendMode.SRC_OVER});
        XposedHelpers.callMethod(material, "setBlurParamEx", blur);

        Object glass = create(HONOR + ATOMIC + "GlassParamEx");
        XposedHelpers.callMethod(glass, "setRefraction", keycap ? KEY_REFRACTION : panel ? 0.15f : 0.22f);
        XposedHelpers.callMethod(glass, "setDepth", keycap ? KEY_DEPTH : panel ? 0.54f : 0.72f);
        XposedHelpers.callMethod(glass, "setDispersion", panel ? 0.18f : 0.25f);
        XposedHelpers.callMethod(glass, "setThickness", keycap ? KEY_THICKNESS : panel ? 0.44f : 0.68f);
        XposedHelpers.callMethod(material, "setGlassParamEx", glass);

        String shapeName = HONOR + ATOMIC + "ShapeParamEx";
        Object rounded = type(shapeName + "$Shape").getField("RRect").get(null);
        Object shape = XposedHelpers.newInstance(type(shapeName), rounded, (float) radius);
        XposedHelpers.callMethod(shape, "setEdgeEffectsPadding", 2.0f);
        XposedHelpers.callMethod(material, "setShapeParamEx", shape);
        Object edge = XposedHelpers.newInstance(type(HONOR + ATOMIC + "EdgeLightParamEx"), 250, 6.0f);
        XposedHelpers.callMethod(edge, "setColor", panel ? 0x82ffffff : 0xb0ffffff);
        XposedHelpers.callMethod(material, "setEdgeLightParamEx", edge);

        Object options = create(HONOR + "HnMaterialOpt");
        XposedHelpers.callMethod(options, "setLayerBackdrop", true);
        XposedHelpers.callStaticMethod(type(HONOR + "HnMaterialManager"), "setHnMaterial",
                node, material, options);
        return node;
    }

    private static void fail(Throwable error) {
        failed = true;
        XposedBridge.log("HonorGlass: native plates disabled " + error);
        Object current = root.get();
        root.clear();
        if (current == null) return;
        try {
            setRootMaterial(current, null);
        } catch (Throwable cleanupError) {
            XposedBridge.log("HonorGlass: failed to release backdrop " + cleanupError);
        }
    }

    private static void setRootMaterial(Object viewRoot, Object material) throws Exception {
        XposedHelpers.callMethod(viewRoot, "setHnMaterial",
                new Class<?>[]{type(ANDROID + "Material"), type(ANDROID + "MaterialOpt")},
                material, null);
    }

    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name);
    }

    private static Object create(String name) throws Exception {
        return type(name).getConstructor().newInstance();
    }
}
