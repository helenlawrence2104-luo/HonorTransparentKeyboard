package local.honor.transparentkeyboard;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Version-specific hooks; no keyboard text, clipboard or network access.
 * Label contrast only mutates Paint color for proven IME key/toolbar glyph draws; text arguments are not read or logged. */
public final class KeyboardHook implements IXposedHookLoadPackage {
    private static final String TAG = "HonorTransparentKeyboard";
    private static volatile int degree = PreviewSettings.DEFAULT_DEGREE;
    private static volatile int material = PreviewSettings.DEFAULT_MATERIAL;
    private static volatile boolean followSystem;
    private static volatile boolean systemTransparencyEnabled = true;
    private static volatile int blur = PreviewSettings.DEFAULT_BLUR;
    private static volatile int keyTransparency = PreviewSettings.DEFAULT_KEYS;
    private static Context context;
    private static ClassLoader keyboardLoader;
    private static Handler main;
    private static long restartToken;
    private static boolean configLoaded;
    private static int lastRenderedDegree = -1;
    private static final ThreadLocal<Object> toolbarBackground = new ThreadLocal<>();
    private static final ThreadLocal<int[]> toolbarDepth = new ThreadLocal<>();
    private static volatile int glass = PreviewSettings.DEFAULT_GLASS;
    /** v1.7: 0 = hide Baidu handwriting-area white wash (0x38FFFFFF over the app), 1 = native. */
    private static volatile int hwWash = 0;
    private static final java.util.Set<String> seenRects = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    // Measured on PTP-AN10: key cell F equals the visible plate; plate corner radius ~20px at 3.5 density.
    private static final float KEY_RADIUS_DP = 5.8f;
    /** In glass mode the opaque native plate fades out so the glass body is translucent. */
    private static int effectiveTransparency() { return VisualControls.plateTransparency(keyTransparency); }
    private static boolean useNativeGlass() {
        return glass > 0 && material == 0 && (!followSystem || systemTransparencyEnabled);
    }
    /** Top toolbar buttons (plate + icon) drawn at 80% around their own centre; touch areas unchanged. */
    static final float TOOLBAR_SCALE = 0.8f;
    private static final ThreadLocal<int[]> scaleDepth = new ThreadLocal<>();
    private static volatile boolean scaleErrorLogged;
    private static String lastPanelGeo = "", lastPanelRegion = "";
    private static final LabelDrawIsolation.CanvasOps HOOK_CANVAS_OPS = new LabelDrawIsolation.CanvasOps() {
        @Override public void restoreToCount(Object canvas, int saveId) {
            if (canvas instanceof android.graphics.Canvas) {
                ((android.graphics.Canvas) canvas).restoreToCount(saveId);
            }
        }
        @Override public void setPaintColor(Object paint, int argb) {
            if (paint instanceof android.graphics.Paint) {
                ((android.graphics.Paint) paint).setColor(argb);
            }
        }
        @Override public int save(Object canvas) {
            return ((android.graphics.Canvas) canvas).save();
        }
        @Override public void scale(Object canvas, float sx, float sy, float px, float py) {
            ((android.graphics.Canvas) canvas).scale(sx, sy, px, py);
        }
    };
    private static final LabelDrawIsolation.ObjectSlot TOOLBAR_BG_SLOT = new LabelDrawIsolation.ObjectSlot() {
        @Override public void set(Object value) {
            if (value == null) toolbarBackground.remove();
            else toolbarBackground.set(value);
        }
    };
    private static int[] scaleDepthArray() {
        int[] d = scaleDepth.get();
        if (d == null) { d = new int[1]; scaleDepth.set(d); }
        return d;
    }
    private static int[] toolbarDepthArray() {
        int[] d = toolbarDepth.get();
        if (d == null) { d = new int[1]; toolbarDepth.set(d); }
        return d;
    }
    private static LabelDrawIsolation.CallResources callOf(XC_MethodHook.MethodHookParam p) {
        Object o = p.getObjectExtra(LabelDrawIsolation.CallResources.EXTRA_KEY);
        if (o instanceof LabelDrawIsolation.CallResources) return (LabelDrawIsolation.CallResources) o;
        LabelDrawIsolation.CallResources c = new LabelDrawIsolation.CallResources();
        p.setObjectExtra(LabelDrawIsolation.CallResources.EXTRA_KEY, c);
        return c;
    }
    private static LabelDrawIsolation.CallResources callPeek(XC_MethodHook.MethodHookParam p) {
        Object o = p.getObjectExtra(LabelDrawIsolation.CallResources.EXTRA_KEY);
        return o instanceof LabelDrawIsolation.CallResources ? (LabelDrawIsolation.CallResources) o : null;
    }
    /** Before-compensation and after share this one-shot cleanup. Does not assume after is skipped. */
    private static void cleanupCall(XC_MethodHook.MethodHookParam p, Runnable afterSavesBeforeLeave) {
        LabelDrawIsolation.CallResources st = callPeek(p);
        if (st == null) return;
        st.cleanup(scope(), HOOK_CANVAS_OPS, scaleDepth.get(), toolbarDepth.get(), TOOLBAR_BG_SLOT, afterSavesBeforeLeave);
    }
    private static void beginToolbarScale(XC_MethodHook.MethodHookParam p, android.graphics.Canvas c, float cx, float cy) {
        // save() return is registered before scale(); if scale throws, cleanup still has the bound save.
        LabelDrawIsolation.CallResources.applyOutermostScale(
            callOf(p), scaleDepthArray(), c, HOOK_CANVAS_OPS, TOOLBAR_SCALE, cx, cy);
    }
    private static void endToolbarScale(XC_MethodHook.MethodHookParam p, android.graphics.Canvas ignored) {
        // Restore uses the canvas bound at registration, not this argument.
        cleanupCall(p, null);
    }
    private static float dp() {
        try { return context.getResources().getDisplayMetrics().density; } catch (Throwable t) { return 3f; }
    }
    private static boolean isButtonShape(android.graphics.Rect r) {
        if (r == null || r.width() <= 0 || r.height() <= 0) return false;
        float ratio = r.width() / (float)r.height();
        return ratio > 0.6f && ratio < 1.7f && r.height() < 90 * dp();
    }
    /* Label-draw isolation: stacked frames, per-call paint/save, canvas identity. */
    private static final ThreadLocal<LabelDrawIsolation.Stack> labelScope = new ThreadLocal<>();
    private static LabelDrawIsolation.Stack scope() {
        LabelDrawIsolation.Stack s = labelScope.get();
        if (s == null) { s = new LabelDrawIsolation.Stack(); labelScope.set(s); }
        return s;
    }
    private static boolean inLabelDraw() {
        LabelDrawIsolation.Frame f = scope().current();
        return f != null && f.entered;
    }
    private static android.graphics.Canvas currentScopeCanvas() {
        LabelDrawIsolation.Frame f = scope().current();
        return f == null ? null : (android.graphics.Canvas) f.canvas;
    }
    private static void rememberPlate(android.graphics.RectF plate, float radius) {
        scope().rememberPlate(plate, radius);
    }
    private static void applyOverlayIfNeeded(android.graphics.Canvas canvas) {
        if (canvas == null || glass <= 0) return;
        if (!LabelDrawIsolation.canvasMatches(currentScopeCanvas(), canvas)) return;
        LabelDrawIsolation.Frame f = scope().current();
        if (f == null || !f.entered || f.overlayApplied || !(f.plate instanceof android.graphics.RectF)) return;
        android.graphics.RectF plate = (android.graphics.RectF) f.plate;
        if (!useNativeGlass() || !HonorKeyGlass.drawContrast(canvas, plate, f.radius,
                effectiveTransparency(), Glass.night, true)) {
            Glass.overlay(canvas, plate, f.radius, glass, dp());
        }
        f.overlayApplied = true;
    }
    private static android.graphics.Paint paintArg(Object[] args, int index) {
        if (args == null || index < 0 || index >= args.length) return null;
        Object last = args[index];
        return last instanceof android.graphics.Paint ? (android.graphics.Paint) last : null;
    }
    private static android.graphics.Paint n1TextPaint(Object n1) {
        try {
            Object e = XposedHelpers.getObjectField(n1, "E");
            return e instanceof android.graphics.Paint ? (android.graphics.Paint) e : null;
        } catch (Throwable ignored) { return null; }
    }
    private static void tintProvenLabelText(XC_MethodHook.MethodHookParam p, android.graphics.Canvas canvas, android.graphics.Paint paint) {
        if (!inLabelDraw()) return;
        HonorBackdropTone.Tone tone = HonorBackdropTone.current();
        boolean lightInk = tone == HonorBackdropTone.Tone.DARK
                || (tone != HonorBackdropTone.Tone.LIGHT && Glass.night);
        boolean darkInk = tone == HonorBackdropTone.Tone.LIGHT && Glass.night;
        if (!LabelDrawIsolation.shouldTint(true,
                LabelDrawIsolation.canvasMatches(currentScopeCanvas(), canvas), lightInk || darkInk, glass)) return;
        if (paint == null) return;
        LabelDrawIsolation.CallResources st = callOf(p);
        try {
            st.savePaint(paint, paint.getColor());
            int rgb = darkInk ? 0x141823 : KeyLabelLook.LIGHT_INK_RGB;
            int alpha = tone == HonorBackdropTone.Tone.UNKNOWN || tone == HonorBackdropTone.Tone.MIXED
                    ? 0xff000000 : paint.getColor() & 0xff000000;
            paint.setColor(alpha | rgb);
        } catch (Throwable t) {
            cleanupCall(p, null);
            throw t;
        }
    }
    private static void restoreProvenLabelText(XC_MethodHook.MethodHookParam p) {
        cleanupCall(p, null);
    }
    /** Narrow proven IME signature only: drawText(String,float,float,Paint). Never read the String. */
    private static final XC_MethodHook provenImeDrawTextHook = new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam p) {
            android.graphics.Canvas canvas = (android.graphics.Canvas) p.thisObject;
            tintProvenLabelText(p, canvas, paintArg(p.args, 3));
        }
        @Override protected void afterHookedMethod(MethodHookParam p) {
            restoreProvenLabelText(p);
        }
    };
    private static void hookProvenImeDrawText() {
        for (String name : LabelDrawIsolation.drawTextHostClasses()) {
            try {
                Class<?> cls = LabelDrawIsolation.SOFTWARE_CANVAS_CLASS.equals(name)
                    ? android.graphics.Canvas.class
                    : XposedHelpers.findClass(name, android.graphics.Canvas.class.getClassLoader());
                XposedHelpers.findAndHookMethod(cls, LabelDrawIsolation.PROVEN_DRAW_TEXT_NAME,
                    String.class, float.class, float.class, android.graphics.Paint.class, provenImeDrawTextHook);
                XposedBridge.log(TAG + ": label ink hook on " + name + ".drawText(String,float,float,Paint)");
            } catch (Throwable t) { XposedBridge.log(TAG + ": label ink hook skipped for " + name + " " + t); }
        }
    }
    private static void beginToolbarPlate(XC_MethodHook.MethodHookParam p, android.graphics.Canvas canvas, android.graphics.Rect rect, boolean fadePlate) {
        LabelDrawIsolation.CallResources st = callOf(p);
        LabelDrawIsolation.CallResources.enterCall(scope(), st, canvas);
        try {
            int s = Math.min(rect.width(), rect.height());
            android.graphics.RectF plate = new android.graphics.RectF(rect.centerX() - s / 2f, rect.centerY() - s / 2f, rect.centerX() + s / 2f, rect.centerY() + s / 2f);
            if (fadePlate && HonorKeyGlass.draw(canvas, plate, s / 2f,
                    effectiveTransparency(), Glass.night,
                    p.args[3] instanceof Enum<?> && ((Enum<?>) p.args[3]).name().equals("PRESSED")
                            || p.args[4] == Boolean.TRUE, true)) {
                p.setResult(((java.lang.reflect.Method) p.method).getReturnType() == boolean.class
                        ? Boolean.TRUE : null);
                return;
            }
            if (glass > 0) {
                Glass.shadow(canvas, plate, s / 2f, glass, dp());
                rememberPlate(plate, s / 2f);
            }
            if (!fadePlate) return;
            int t = effectiveTransparency();
            if (t <= 0) return;
            if (VisualControls.shouldSkipPlateMethod(t, true)) {
                /* Skip the opaque plate only; labels still draw at full alpha. */
                if (p.method instanceof java.lang.reflect.Method && ((java.lang.reflect.Method)p.method).getReturnType() == boolean.class) {
                    p.setResult(Boolean.TRUE);
                } else {
                    p.setResult(null);
                }
                return;
            }
            android.graphics.RectF bounds = new android.graphics.RectF(rect);
            bounds.inset(-12, -12);
            // Hold the saveLayer id in a local first; register immediately after
            // a successful return and before any later work. setObjectExtra is not
            // the source of truth. There is no mandatory draw after registration.
            int layer = canvas.saveLayerAlpha(bounds, VisualControls.plateLayerAlpha(t));
            st.registerCanvasSave(canvas, layer);
        } catch (Throwable t) {
            cleanupCall(p, null);
            throw t;
        }
    }
    private static void endToolbarPlate(XC_MethodHook.MethodHookParam p, android.graphics.Canvas canvas, boolean afterPlate) {
        if (callPeek(p) == null || !callPeek(p).entered()) return;
        cleanupCall(p, afterPlate ? () -> applyOverlayIfNeeded(canvas) : null);
    }
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!"com.baidu.input_hihonor".equals(p.packageName)) return;
        XposedBridge.log(TAG + ": loading in " + p.processName);
        force(p.classLoader, "com.baidu.ym.m", "v"); // isDeviceSupportTransparency
        force(p.classLoader, "com.baidu.j40.n", "a"); // whitelist available
        XposedHelpers.findAndHookMethod("com.baidu.j40.n", p.classLoader, "b", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(!followSystem || systemTransparencyEnabled);
            }
        });
        force(p.classLoader, "com.baidu.j40.n", "c"); // every client package allowed
        force(p.classLoader, "com.baidu.j40.k", "N"); // settings entry enabled
        if (!"com.baidu.input_hihonor".equals(p.processName)) return;
        keyboardLoader = p.classLoader;
        FogProbe.install(p.classLoader); // v1.7 fog diagnostics
        KeyHaptics.installViewHook();
        KeyHaptics.installHonorHooks(p.classLoader);
        try { // v1.7 fog fix: Baidu's handwriting view (az.c, full-screen PopupWindow) paints 0x38FFFFFF over the whole app while L is set
            XposedHelpers.findAndHookMethod("com.baidu.az.c", p.classLoader, "draw", android.graphics.Canvas.class, new XC_MethodHook() {
                private boolean logged;
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (hwWash != 0 || !XposedHelpers.getBooleanField(p.thisObject, "L")) return;
                    XposedHelpers.setBooleanField(p.thisObject, "L", false);
                    p.setObjectExtra("washL", Boolean.TRUE);
                    if (!logged) { logged = true; XposedBridge.log(TAG + ": handwriting wash suppressed"); }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.getObjectExtra("washL") != null) XposedHelpers.setBooleanField(p.thisObject, "L", true);
                }
            });
        } catch (Throwable t) { XposedBridge.log(TAG + ": handwriting wash hook failed " + t); }
        Class<?> facadeState = XposedHelpers.findClass("com.baidu.input.ime.params.enumtype.FacadeState", p.classLoader);
        installBackdropToneHooks(p.classLoader, facadeState);
        try {
            KeyboardInsets.install(p.classLoader, KeyboardHook::useNativeGlass);
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": insets experiment unavailable " + error);
        }
        XposedHelpers.findAndHookMethod("com.baidu.fw.g", p.classLoader, "k1", android.graphics.Canvas.class,
            int.class, int.class, XposedHelpers.findClass("com.baidu.j10.t1", p.classLoader), byte.class,
            toolbarScope(false));
        XposedHelpers.findAndHookMethod("com.baidu.fw.g$g", p.classLoader, "a", android.graphics.Canvas.class,
            android.graphics.Rect.class, boolean.class, android.graphics.Bitmap.class, android.graphics.Paint.class,
            toolbarScope(true));
        try { // toolbar end buttons (menu grid, collapse chevron) are drawn by o20.d.E
            Class<?> sideButton = XposedHelpers.findClass("com.baidu.o20.d", p.classLoader);
            XposedHelpers.findAndHookMethod(sideButton, "G", android.graphics.Canvas.class, android.graphics.Paint.class,
                android.graphics.Paint.class, android.graphics.Rect.class, XposedHelpers.findClass("com.baidu.j10.x", p.classLoader),
                XposedHelpers.findClass("com.baidu.input.ime.params.enumtype.FacadeState", p.classLoader), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    android.graphics.Rect r = (android.graphics.Rect)p.args[3];
                    try {
                    if (isButtonShape(r)) beginToolbarScale(p, (android.graphics.Canvas)p.args[0], r.exactCenterX(), r.exactCenterY());
                    } catch (Throwable t) {
                        endToolbarScale(p, (android.graphics.Canvas)p.args[0]);
                        throw t;
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) { endToolbarScale(p, (android.graphics.Canvas)p.args[0]); }
            });
            XposedBridge.hookAllMethods(sideButton, "E", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    toolbarDepthArray()[0]++;
                    callOf(p).markToolbarDepthHeld();
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    cleanupCall(p, null);
                }
            });
        } catch (Throwable t) { XposedBridge.log(TAG + ": o20.d.E hook failed " + t); }
        try { // neutral frosted tint behind the keys so glass tiles read on pure white backgrounds
            final Class<?> panel = XposedHelpers.findClass("com.baidu.input.ime.transparency.BDHnTransFrameLayout", p.classLoader);
            XposedHelpers.findAndHookMethod(android.view.ViewGroup.class, "dispatchDraw", android.graphics.Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!panel.isInstance(p.thisObject)) return;
                    Glass.night = (((android.view.View)p.thisObject).getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                    android.view.ViewGroup vg = (android.view.ViewGroup)p.thisObject;
                    boolean nativeAppearance = useNativeGlass();
                    HonorKeyGlass.prepare(vg, nativeAppearance);
                    if (!nativeAppearance) HonorBackdropTone.detach(vg);
                    if (glass <= 0) return;
                    String geo = vg.getWidth() + "x" + vg.getHeight();
                    if (!geo.equals(lastPanelGeo)) {
                        lastPanelGeo = geo;
                        int[] loc = new int[2]; vg.getLocationOnScreen(loc);
                        StringBuilder sb = new StringBuilder(TAG + ": panel " + vg.getClass().getName() + " at " + loc[0] + "," + loc[1] + " size " + geo);
                        for (int ci = 0; ci < vg.getChildCount(); ci++) {
                            android.view.View ch = vg.getChildAt(ci);
                            sb.append(" | ").append(ch.getClass().getName()).append(" [").append(ch.getLeft()).append(",").append(ch.getTop()).append(",").append(ch.getRight()).append(",").append(ch.getBottom()).append("] vis=").append(ch.getVisibility());
                        }
                        android.view.ViewParent par = vg.getParent(); int depthUp = 0;
                        while (par instanceof android.view.View && depthUp++ < 6) { android.view.View pv = (android.view.View)par; int[] pl = new int[2]; pv.getLocationOnScreen(pl); sb.append(" ^ ").append(pv.getClass().getName()).append(" ").append(pl[1]).append(" h=").append(pv.getHeight()); par = pv.getParent(); }
                        XposedBridge.log(sb.toString());
                    }
                    // v1.6: the frame is taller than the keyboard (input-bar offset area above it). Only tint the
                    // native material region (AbsViewStyle.SharedArgs.d); never the transparent area above.
                    android.graphics.Rect region = null;
                    try {
                        Object style = XposedHelpers.callMethod(vg, "getViewStyle");
                        Object shared = style == null ? null : XposedHelpers.callMethod(style, "getSharedArgs");
                        Object r = shared == null ? null : XposedHelpers.getObjectField(shared, "d");
                        if (r instanceof android.graphics.Rect && !((android.graphics.Rect)r).isEmpty()) region = (android.graphics.Rect)r;
                    } catch (Throwable ignored) {}
                    if (!String.valueOf(region).equals(lastPanelRegion)) { lastPanelRegion = String.valueOf(region); XposedBridge.log(TAG + ": panel tint region=" + region + " view=" + geo); }
                    if (region == null) return; // unknown bounds: draw nothing rather than fog the app
                    android.graphics.Canvas cv = (android.graphics.Canvas)p.args[0];
                    android.graphics.RectF base = new android.graphics.RectF(region);
                    if (nativeAppearance && base.intersect(0, 0, vg.getWidth(), vg.getHeight())
                            && base.height() >= 24 * dp()
                            && HonorKeyGlass.drawPanel(vg, cv, base,
                                    Math.min(18 * dp(), base.height() / 2), degree, Glass.night)) return;
                    int save = cv.save();
                    try {
                    cv.clipRect(region);
                    cv.drawColor(Glass.panelTint(glass));
                    } finally {
                    cv.restoreToCount(save);
                    }
                }
            });
        } catch (Throwable t) { XposedBridge.log(TAG + ": panel tint hook failed " + t); }

        try {
            final Class<?> liquidPanel = XposedHelpers.findClass("com.baidu.input.ime.transparency.BDHnTransFrameLayout", p.classLoader);
            XposedHelpers.findAndHookMethod(liquidPanel, "onAttachedToWindow", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    HonorLiquidGlass.attach((android.view.View)p.thisObject);
                }
            });
            XposedHelpers.findAndHookMethod(liquidPanel, "onWindowVisibilityChanged", int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    HonorLiquidGlass.onVisibility((android.view.View)p.thisObject, (Integer)p.args[0]);
                }
            });
            XposedHelpers.findAndHookMethod(liquidPanel, "onDetachedFromWindow", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    HonorBackdropTone.detach((android.view.View)p.thisObject);
                    HonorLiquidGlass.onDetached((android.view.View)p.thisObject);
                }
            });
            XposedBridge.log(TAG + ": hnLiquid lifecycle hooks installed");
        } catch (Throwable t) { XposedBridge.log(TAG + ": hnLiquid lifecycle hook failed " + t); }
        XposedHelpers.findAndHookMethod("com.baidu.j10.n1", p.classLoader, "M", android.graphics.Canvas.class,
            android.graphics.Paint.class, android.graphics.Rect.class, facadeState, byte.class, new XC_MethodHook() {
                private int lastLogged = -1;
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    android.graphics.Rect rect = (android.graphics.Rect)p.args[2];
                    boolean own = toolbarBackground.get() == p.thisObject;
                    int[] depth = toolbarDepth.get();
                    boolean inToolbar = depth != null && depth[0] > 0 && isButtonShape(rect);
                    if (!own && !inToolbar) return;
                    if (lastLogged != keyTransparency * 1000 + glass) {
                        lastLogged = keyTransparency * 1000 + glass;
                        XposedBridge.log(TAG + ": toolbar plate own=" + own + " rect=" + rect + " transparency=" + keyTransparency + " glass=" + glass);
                    }
                    beginToolbarPlate(p, (android.graphics.Canvas)p.args[0], rect, VisualControls.N1_M_FADE_PLATE);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) { endToolbarPlate(p, (android.graphics.Canvas)p.args[0], VisualControls.N1_M_APPLY_OVERLAY); }
            });
        try {
            XposedHelpers.findAndHookMethod("com.baidu.j10.j0", p.classLoader, "k1", XposedHelpers.findClass("com.baidu.j10.t1", p.classLoader),
                android.graphics.Canvas.class, android.graphics.Paint.class, android.graphics.Rect.class, boolean.class,
                XposedHelpers.findClass("com.baidu.j10.n1", p.classLoader), new XC_MethodHook() {
                    private boolean logged;
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        android.graphics.Rect rect = (android.graphics.Rect)p.args[3];
                        if (!isButtonShape(rect)) return;
                        if (!logged) { logged = true; XposedBridge.log(TAG + ": toolbar shape plate rect=" + rect); }
                        beginToolbarPlate(p, (android.graphics.Canvas)p.args[1], rect, true);
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) { endToolbarPlate(p, (android.graphics.Canvas)p.args[1], true); }
                });
        } catch (Throwable t) { XposedBridge.log(TAG + ": j0.k1 hook failed " + t); }
        XposedHelpers.findAndHookMethod("com.baidu.j10.g0", p.classLoader, "d0", byte.class,
            android.graphics.Canvas.class, android.graphics.Paint.class,
            XposedHelpers.findClass("com.baidu.input.ime.params.enumtype.FacadeState", p.classLoader),
            int.class, android.graphics.Rect.class, new XC_MethodHook() {
                private int lastLogged = -1;
                private int rectLogs;
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    int transparency = effectiveTransparency();
                    if (lastLogged != transparency * 1000 + glass) {
                        lastLogged = transparency * 1000 + glass;
                        XposedBridge.log(TAG + ": keycap background transparency=" + transparency + " glass=" + glass);
                    }
                    android.graphics.Canvas canvas = (android.graphics.Canvas)p.args[1];
                    android.graphics.Rect keyRect = (android.graphics.Rect)XposedHelpers.getObjectField(p.thisObject, "F");
                    int offset = (Integer)p.args[4];
                    android.graphics.RectF plate = new android.graphics.RectF(keyRect);
                    plate.offset(offset, 0);
                    if (rectLogs < 2) { rectLogs++; XposedBridge.log(TAG + ": key F=" + keyRect + " dp=" + dp()); }
                    float radius = Math.min(KEY_RADIUS_DP * dp(), Math.min(plate.width(), plate.height()) / 2f);
                    LabelDrawIsolation.CallResources st = callOf(p);
                    LabelDrawIsolation.CallResources.enterCall(scope(), st, canvas);
                    try {
                    scope().current().pressed = p.args[3] instanceof Enum<?>
                            && ((Enum<?>) p.args[3]).name().equals("PRESSED");
                    if (glass > 0) rememberPlate(plate, radius);
                    } catch (Throwable t) {
                        cleanupCall(p, null);
                        throw t;
                    }
                    /* Do not saveLayer the whole key method: native glyphs (Canvas.drawText
                     * in com.baidu.j10.n1) would be faded and then covered by Glass.overlay.
                     * Plate fade happens only around g0.Y; labels stay full-alpha on top. */
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (callPeek(p) == null || !callPeek(p).entered()) return;
                    android.graphics.Canvas canvas = (android.graphics.Canvas)p.args[1];
                    cleanupCall(p, () -> applyOverlayIfNeeded(canvas));
                }
            });
        try {
            XposedHelpers.findAndHookMethod("com.baidu.j10.g0", p.classLoader, "Y",
                android.graphics.Canvas.class, android.graphics.Paint.class, android.graphics.Rect.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!inLabelDraw()) return;
                        android.graphics.Canvas canvas = (android.graphics.Canvas) p.args[0];
                        if (!LabelDrawIsolation.canvasMatches(currentScopeCanvas(), canvas)) return;
                        int t = effectiveTransparency();
                        LabelDrawIsolation.Frame frame = scope().current();
                        if (frame.plate instanceof android.graphics.RectF) {
                            android.graphics.RectF plate = (android.graphics.RectF) frame.plate;
                            if (HonorKeyGlass.draw(canvas, plate, frame.radius, t,
                                    Glass.night, frame.pressed, false)) {
                                frame.overlayApplied = true;
                                frame.nativePlateDrawn = true;
                                p.setResult(null);
                                return;
                            }
                            Glass.shadow(canvas, plate, frame.radius, glass, dp());
                        }
                        if (t <= 0) return;
                        if (VisualControls.shouldSkipPlateMethod(t, VisualControls.Y_SKIP_PLATE_AT_FULL_TRANSPARENCY)) { p.setResult(null); return; }
                        android.graphics.Rect rect = (android.graphics.Rect) p.args[2];
                        if (rect == null) return;
                        android.graphics.RectF bounds = new android.graphics.RectF(rect);
                        bounds.inset(-12, -12);
                        try {
                            int layer = canvas.saveLayerAlpha(bounds, VisualControls.plateLayerAlpha(t));
                            callOf(p).registerCanvasSave(canvas, layer);
                        } catch (Throwable t2) {
                            cleanupCall(p, null);
                            throw t2;
                        }
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        android.graphics.Canvas canvas = (android.graphics.Canvas) p.args[0];
                        cleanupCall(p, () -> { if (inLabelDraw()) applyOverlayIfNeeded(canvas); });
                    }
                });
        } catch (Throwable t) { XposedBridge.log(TAG + ": key plate fade hook failed " + t); }
        try {
            XposedHelpers.findAndHookMethod("com.baidu.j10.n1", p.classLoader, "N",
                android.graphics.Canvas.class, android.graphics.Paint.class, android.graphics.Rect.class,
                facadeState, byte.class, boolean.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!inLabelDraw()) return;
                        android.graphics.Canvas canvas = (android.graphics.Canvas) p.args[0];
                        if (!LabelDrawIsolation.canvasMatches(currentScopeCanvas(), canvas)) return;
                        if (scope().current().nativePlateDrawn && (Boolean) p.args[5]) {
                            p.setResult(null);
                            return;
                        }
                        applyOverlayIfNeeded(canvas);
                    }
                });
            XposedHelpers.findAndHookMethod("com.baidu.j10.n1", p.classLoader, "y",
                android.graphics.Canvas.class, android.graphics.Rect.class, String.class, float.class, float.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        /* Paint field E only. Never read the String argument. */
                        tintProvenLabelText(p, (android.graphics.Canvas) p.args[0], n1TextPaint(p.thisObject));
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        restoreProvenLabelText(p);
                    }
                });
        } catch (Throwable t) { XposedBridge.log(TAG + ": key label overlay hook failed " + t); }
        hookProvenImeDrawText();
        XposedHelpers.findAndHookMethod("com.baidu.j40.k", p.classLoader, "A", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(material); }
        });
        XposedHelpers.findAndHookMethod("com.baidu.j40.k", p.classLoader, "z", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(degree); }
        });
        XposedHelpers.findAndHookMethod("com.baidu.input.ime.transparency.TransparencyStyleApplier", p.classLoader,
            "k", float.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (useNativeGlass() && (Integer) p.args[1] == 0) p.setResult(0);
                    else if (VisualControls.blurOverrideApplies(blur)) p.setResult(blur);
                }
            });
        XposedHelpers.findAndHookMethod("com.baidu.input.ime.transparency.TransparencyMaterialParams", p.classLoader,
            "b", XposedHelpers.findClass("com.baidu.xp1.d", p.classLoader), java.util.List.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.args[0] == null) return;
                    int[] alphas = (int[])XposedHelpers.callMethod(p.args[0], "d");
                    if (alphas == null) return;
                    int[] adjusted = alphas.clone();
                    float scale = followSystem ? 1f : VisualControls.maskScale(degree, glass);
                    for (int i = 0; i < adjusted.length; i++) adjusted[i] = Math.min(255, Math.round(adjusted[i] * scale));
                    XposedHelpers.callMethod(p.args[0], "n", adjusted);
                    if (lastRenderedDegree != degree) {
                        lastRenderedDegree = degree;
                        XposedBridge.log(TAG + ": rendered mask degree=" + degree + " alphas=" + java.util.Arrays.toString(adjusted));
                    }
                }
            });
        XposedHelpers.findAndHookMethod("com.baidu.j40.k", p.classLoader, "S", String.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (context == null) return;
                try {
                    context.startActivity(new Intent().setClassName("local.honor.transparentkeyboard", "local.honor.transparentkeyboard.PreviewActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    p.setResult(null);
                } catch (Throwable t) { XposedBridge.log(TAG + ": panel " + t); }
            }
        });
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (context != null) return;
                context = ((Context)p.args[0]).getApplicationContext();
                if (context == null) context = (Context)p.args[0];
                main = new Handler(context.getMainLooper());
                ContentObserver settingsObserver = new ContentObserver(main) {
                    @Override public void onChange(boolean self) {
                        main.removeCallbacks(update); main.postDelayed(update, 80);
                    }
                };
                context.getContentResolver().registerContentObserver(SettingsProvider.URI, true, settingsObserver);
                HonorSystemAppearance.observe(context.getContentResolver(), settingsObserver);
                HonorLiquidGlass.setPublicStatusHook(new Runnable() {
                    @Override public void run() { publishPublicStatus(false); }
                });
                load(false);
            }
        });
    }
    private static void installBackdropToneHooks(ClassLoader loader, Class<?> facadeState) {
        try {
            Class<?> service = XposedHelpers.findClass("com.baidu.input.modular.ObservableImeService", loader);
            XposedHelpers.findAndHookMethod(service, "onStartInputView",
                    android.view.inputmethod.EditorInfo.class, boolean.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            HonorBackdropTone.onEditorChanged(p.args[0] != null);
                        }
                    });
            XposedHelpers.findAndHookMethod(service, "onWindowHidden", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    HonorBackdropTone.onEditorChanged(false);
                }
            });
            XposedHelpers.findAndHookMethod("com.baidu.j10.g0", loader, "f0", byte.class,
                    android.graphics.Canvas.class, android.graphics.Paint.class, android.graphics.Rect.class,
                    facadeState, XposedHelpers.findClass("com.baidu.j10.n1", loader), int.class,
                    backdropLabelScope(1));
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": backdrop contrast hooks unavailable " + error);
        }
    }

    private static XC_MethodHook backdropLabelScope(int canvasIndex) {
        return new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                HonorBackdropTone.Tone tone = HonorBackdropTone.current();
                if (tone == HonorBackdropTone.Tone.DARK || tone == HonorBackdropTone.Tone.LIGHT) {
                    LabelDrawIsolation.CallResources.enterCall(scope(), callOf(p), p.args[canvasIndex]);
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                cleanupCall(p, null);
            }
        };
    }

    private static XC_MethodHook toolbarScope(final boolean inner) {
        return new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                LabelDrawIsolation.CallResources st = callOf(p);
                st.holdToolbarBackground(toolbarBackground.get());
                toolbarDepthArray()[0]++;
                st.markToolbarDepthHeld();
                try {
                    try {
                        android.graphics.Canvas cv = (android.graphics.Canvas)p.args[0];
                        if (inner) {
                            android.graphics.Rect r = (android.graphics.Rect)p.args[1];
                            if (r != null) beginToolbarScale(p, cv, r.exactCenterX(), r.exactCenterY());
                        } else {
                            android.graphics.Rect cell = (android.graphics.Rect)XposedHelpers.getStaticObjectField(XposedHelpers.findClass("com.baidu.fw.g", keyboardLoader), "S1");
                            beginToolbarScale(p, cv, (Integer)p.args[1] + cell.width() / 2f, (Integer)p.args[2] + cell.height() / 2f);
                        }
                    } catch (Throwable t) { if (!scaleErrorLogged) { scaleErrorLogged = true; XposedBridge.log(TAG + ": toolbar scale " + t); } }
                    try {
                        Object owner = inner ? XposedHelpers.getSurroundingThis(p.thisObject) : p.thisObject;
                        Object panel = XposedHelpers.getObjectField(owner, "g");
                        Object theme = XposedHelpers.callMethod(panel, "l");
                        Object options = XposedHelpers.getObjectField(theme, "g");
                        Object backgroundResource = XposedHelpers.getObjectField(options, "o");
                        Object facade = XposedHelpers.callStaticMethod(XposedHelpers.findClass("com.baidu.c20.n", keyboardLoader), "p", backgroundResource);
                        toolbarBackground.set(facade);
                    } catch (Throwable t) { toolbarBackground.remove(); }
                } catch (Throwable t) {
                    cleanupCall(p, null);
                    throw t;
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                // Scale restore, background restore, and depth release are
                // independent finally steps inside CallResources.cleanup.
                cleanupCall(p, null);
            }
        };
    }
    /** Public status only. Tokens are request/lifecycle facts, not compositor success. Report never writes settings. */
    static final PublicStatusDispatch publicStatus = new PublicStatusDispatch();
    private static void publishPublicStatus(boolean apply) {
        if (context == null) return;
        String msg = VisualControls.publicStatusLine(apply, degree, keyTransparency, glass, Glass.clarity,
            material, blur, KeyHaptics.enabled, KeyHaptics.honorHooked == 1, HonorLiquidGlass.requested(),
            HonorLiquidGlass.status(), android.os.Process.myPid(), HonorLiquidGlass.diagHint());
        publicStatus.publish(new PublicStatusDispatch.ReportSink() {
            @Override public void report(String line) {
                context.getContentResolver().call(SettingsProvider.URI, "report", line, null);
            }
        }, msg);
    }
    private static final Runnable update = () -> load(true);
    private static void load(boolean apply) {
        if (context == null) return;
        try {
            Bundle b = context.getContentResolver().call(SettingsProvider.URI, "get", null, null);
            if (b == null) return;
            HookSettings h = HookSettings.from(new HookSettings.Source() {
                @Override public int getInt(String key, int def) { return b.getInt(key, def); }
                @Override public long getLong(String key, long def) { return b.getLong(key, def); }
            });
            degree = h.degree;
            material = h.material;
            blur = h.blur;
            followSystem = b.getInt("followSystem", 1) == 1;
            if (followSystem) {
                HonorSystemAppearance appearance = HonorSystemAppearance.read(
                        context.getContentResolver(), material, degree);
                degree = appearance.degree;
                material = appearance.material;
                blur = -1;
                systemTransparencyEnabled = appearance.enabled;
            }
            keyTransparency = h.keys;
            glass = h.glass;
            Glass.clarity = h.clarity;
            Glass.clarityInNight = h.clarityInNight == 1;
            KeyHaptics.enabled = h.haptic == 1;
            KeyHaptics.intensity = h.hapticIntensity;
            KeyHaptics.durationMs = h.hapticDuration;
            KeyHaptics.rhythm = h.hapticRhythm;
            KeyHaptics.intervalMs = h.hapticInterval;
            hwWash = h.hwWash;
            HonorLiquidGlass.setRequested(h.hnLiquidTest == 1);
            KeyHaptics.attach(context);
            long requestedRestart = h.restart;
            boolean restarting = configLoaded && requestedRestart != restartToken;
            restartToken = requestedRestart;
            configLoaded = true;
            if (apply) {
                HonorKeyGlass.redrawPanel();
                Class<?> cls = XposedHelpers.findClass("com.baidu.j40.k", keyboardLoader);
                Object manager = XposedHelpers.getStaticObjectField(cls, "a");
                XposedHelpers.callMethod(manager, "p0", degree);
                XposedHelpers.callMethod(manager, "q0", material);
                Object config = XposedHelpers.callMethod(manager, "r");
                XposedHelpers.callMethod(manager, "n0", config, true);
                XposedHelpers.callMethod(manager, "I"); // refresh cached key visuals as well
            }
            publishPublicStatus(apply);
            String diag = HonorLiquidGlass.diagHint();
            XposedBridge.log(TAG + ": config degree=" + degree + " material=" + material + " blur=" + blur + " clarity=" + Glass.clarity + " haptic=" + KeyHaptics.enabled + " honorHooked=" + KeyHaptics.honorHooked + " hwWash=" + hwWash + " hnLiquidTest=" + HonorLiquidGlass.requested() + " hnLiquid=" + HonorLiquidGlass.status() + " apply=" + apply + (diag != null && !diag.isEmpty() ? " " + diag : ""));
            if (restarting) {
                context.getContentResolver().call(SettingsProvider.URI, "report", "正在重启输入法…", null);
                XposedBridge.log(TAG + ": restart requested pid=" + android.os.Process.myPid());
                main.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 250);
            }
        } catch (Throwable t) { XposedBridge.log(TAG + ": config error " + t); }
    }
    private static void force(ClassLoader loader, String cls, String name) {
        try {
            Class<?> target = XposedHelpers.findClass(cls, loader);
            int count = XposedBridge.hookAllMethods(target, name, new XC_MethodHook() {
                private boolean reported;
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.setResult(true);
                    if (!reported) {
                        reported = true;
                        XposedBridge.log(TAG + ": active " + cls + "." + name);
                    }
                }
            }).size();
            XposedBridge.log(TAG + ": hooked " + cls + "." + name + " count=" + count);
        } catch (Throwable t) { XposedBridge.log(TAG + ": FAILED " + cls + "." + name + " " + t); }
    }
}
