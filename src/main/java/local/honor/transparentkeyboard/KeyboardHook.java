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

/** Version-specific hooks; no keyboard text, clipboard or network access. */
public final class KeyboardHook implements IXposedHookLoadPackage {
    private static final String TAG = "HonorTransparentKeyboard";
    private static volatile int degree = 60, material = 0, blur = -1, keyTransparency = 45;
    private static Context context;
    private static ClassLoader keyboardLoader;
    private static Handler main;
    private static long restartToken;
    private static boolean configLoaded;
    private static int lastRenderedDegree = -1;
    private static final ThreadLocal<Object> toolbarBackground = new ThreadLocal<>();
    private static final ThreadLocal<int[]> toolbarDepth = new ThreadLocal<>();
    private static volatile int glass = 60;
    /** v1.7: 0 = hide Baidu handwriting-area white wash (0x38FFFFFF over the app), 1 = native. */
    private static volatile int hwWash = 0;
    private static final java.util.Set<String> seenRects = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    // Measured on PTP-AN10: key cell F equals the visible plate; plate corner radius ~20px at 3.5 density.
    private static final float KEY_RADIUS_DP = 5.8f;
    /** In glass mode the opaque native plate fades out so the glass body is translucent. */
    private static int effectiveTransparency() { int g = glass; return g > 0 ? Math.max(keyTransparency, Math.min(100, 30 + g)) : keyTransparency; }
    /** Top toolbar buttons (plate + icon) drawn at 80% around their own centre; touch areas unchanged. */
    static final float TOOLBAR_SCALE = 0.8f;
    private static final ThreadLocal<int[]> scaleDepth = new ThreadLocal<>();
    private static volatile boolean scaleErrorLogged;
    private static String lastPanelGeo = "", lastPanelRegion = "";
    private static void beginToolbarScale(XC_MethodHook.MethodHookParam p, android.graphics.Canvas c, float cx, float cy) {
        int[] d = scaleDepth.get(); if (d == null) { d = new int[1]; scaleDepth.set(d); }
        d[0]++;
        p.setObjectExtra("scaleOpened", Boolean.TRUE);
        if (d[0] != 1 || c == null) return; // only the outermost toolbar draw is scaled
        int save = c.save();
        c.scale(TOOLBAR_SCALE, TOOLBAR_SCALE, cx, cy);
        p.setObjectExtra("scaleSave", save);
    }
    private static void endToolbarScale(XC_MethodHook.MethodHookParam p, android.graphics.Canvas c) {
        if (p.getObjectExtra("scaleOpened") == null) return;
        int[] d = scaleDepth.get(); if (d != null && d[0] > 0) d[0]--;
        Object save = p.getObjectExtra("scaleSave");
        if (save != null && c != null) c.restoreToCount((Integer)save);
    }
    private static float dp() {
        try { return context.getResources().getDisplayMetrics().density; } catch (Throwable t) { return 3f; }
    }
    private static boolean isButtonShape(android.graphics.Rect r) {
        if (r == null || r.width() <= 0 || r.height() <= 0) return false;
        float ratio = r.width() / (float)r.height();
        return ratio > 0.6f && ratio < 1.7f && r.height() < 90 * dp();
    }
    private static void beginToolbarPlate(XC_MethodHook.MethodHookParam p, android.graphics.Canvas canvas, android.graphics.Rect rect) {
        int s = Math.min(rect.width(), rect.height());
        android.graphics.RectF plate = new android.graphics.RectF(rect.centerX() - s / 2f, rect.centerY() - s / 2f, rect.centerX() + s / 2f, rect.centerY() + s / 2f);
        if (glass > 0) { Glass.shadow(canvas, plate, s / 2f, glass, dp()); p.setObjectExtra("tbPlate", plate); }
        int t = effectiveTransparency();
        if (t == 0) return;
        if (t == 100) { p.setResult(p.method instanceof java.lang.reflect.Method && ((java.lang.reflect.Method)p.method).getReturnType() == boolean.class ? Boolean.TRUE : null); return; }
        android.graphics.RectF bounds = new android.graphics.RectF(rect);
        bounds.inset(-12, -12);
        p.setObjectExtra("toolbarLayer", canvas.saveLayerAlpha(bounds, Math.round(255 * (100 - t) / 100f)));
    }
    private static void endToolbarPlate(XC_MethodHook.MethodHookParam p, android.graphics.Canvas canvas) {
        Object save = p.getObjectExtra("toolbarLayer");
        if (save != null) canvas.restoreToCount((Integer)save);
        Object plate = p.getObjectExtra("tbPlate");
        if (plate != null) { android.graphics.RectF r = (android.graphics.RectF)plate; Glass.overlay(canvas, r, r.width() / 2f, glass, dp()); }
    }
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!"com.baidu.input_hihonor".equals(p.packageName)) return;
        XposedBridge.log(TAG + ": loading in " + p.processName);
        force(p.classLoader, "com.baidu.ym.m", "v"); // isDeviceSupportTransparency
        force(p.classLoader, "com.baidu.j40.n", "a"); // whitelist available
        force(p.classLoader, "com.baidu.j40.n", "b"); // transparency enabled
        force(p.classLoader, "com.baidu.j40.n", "c"); // every client package allowed
        force(p.classLoader, "com.baidu.j40.k", "N"); // settings entry enabled
        if (!"com.baidu.input_hihonor".equals(p.processName)) return;
        keyboardLoader = p.classLoader;
        FogProbe.install(p.classLoader); // v1.7 fog diagnostics
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
        } catch (Throwable t) { XposedBridge.log(TAG + ": handwriting wash hook failed " + t); }        Class<?> facadeState = XposedHelpers.findClass("com.baidu.input.ime.params.enumtype.FacadeState", p.classLoader);
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
                    if (isButtonShape(r)) beginToolbarScale(p, (android.graphics.Canvas)p.args[0], r.exactCenterX(), r.exactCenterY());
                }
                @Override protected void afterHookedMethod(MethodHookParam p) { endToolbarScale(p, (android.graphics.Canvas)p.args[0]); }
            });
            XposedBridge.hookAllMethods(sideButton, "E", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    int[] d = toolbarDepth.get(); if (d == null) { d = new int[1]; toolbarDepth.set(d); } d[0]++;
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    int[] d = toolbarDepth.get(); if (d != null && d[0] > 0) d[0]--;
                }
            });
        } catch (Throwable t) { XposedBridge.log(TAG + ": o20.d.E hook failed " + t); }
        try { // neutral frosted tint behind the keys so glass tiles read on pure white backgrounds
            final Class<?> panel = XposedHelpers.findClass("com.baidu.input.ime.transparency.BDHnTransFrameLayout", p.classLoader);
            XposedHelpers.findAndHookMethod(android.view.ViewGroup.class, "dispatchDraw", android.graphics.Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (glass <= 0 || !panel.isInstance(p.thisObject)) return;
                    Glass.night = (((android.view.View)p.thisObject).getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                    android.view.ViewGroup vg = (android.view.ViewGroup)p.thisObject;
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
                    int save = cv.save();
                    cv.clipRect(region);
                    cv.drawColor(Glass.panelTint(glass));
                    cv.restoreToCount(save);
                }
            });
        } catch (Throwable t) { XposedBridge.log(TAG + ": panel tint hook failed " + t); }
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
                    beginToolbarPlate(p, (android.graphics.Canvas)p.args[0], rect);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) { endToolbarPlate(p, (android.graphics.Canvas)p.args[0]); }
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
                        beginToolbarPlate(p, (android.graphics.Canvas)p.args[1], rect);
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) { endToolbarPlate(p, (android.graphics.Canvas)p.args[1]); }
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
                    if (glass > 0) {
                        Glass.shadow(canvas, plate, radius, glass, dp());
                        p.setObjectExtra("plate", plate);
                        p.setObjectExtra("radius", radius);
                    }
                    if (transparency == 0) return;
                    if (transparency == 100) { p.setResult(null); return; }
                    android.graphics.RectF bounds = new android.graphics.RectF(keyRect);
                    bounds.offset(offset, 0); bounds.inset(-12, -12);
                    int save = canvas.saveLayerAlpha(bounds, Math.round(255 * (100 - transparency) / 100f));
                    p.setObjectExtra("keycapLayer", save);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    android.graphics.Canvas canvas = (android.graphics.Canvas)p.args[1];
                    Object save = p.getObjectExtra("keycapLayer");
                    if (save != null) canvas.restoreToCount((Integer)save);
                    Object plate = p.getObjectExtra("plate");
                    if (plate != null) Glass.overlay(canvas, (android.graphics.RectF)plate, (Float)p.getObjectExtra("radius"), glass, dp());
                }
            });        XposedHelpers.findAndHookMethod("com.baidu.j40.k", p.classLoader, "A", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(material); }
        });
        XposedHelpers.findAndHookMethod("com.baidu.j40.k", p.classLoader, "z", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(degree); }
        });
        XposedHelpers.findAndHookMethod("com.baidu.input.ime.transparency.TransparencyStyleApplier", p.classLoader,
            "k", float.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { if (blur >= 0) p.setResult(Integer.valueOf(blur)); }
            });
        XposedHelpers.findAndHookMethod("com.baidu.input.ime.transparency.TransparencyMaterialParams", p.classLoader,
            "b", XposedHelpers.findClass("com.baidu.xp1.d", p.classLoader), java.util.List.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.args[0] == null) return;
                    int[] alphas = (int[])XposedHelpers.callMethod(p.args[0], "d");
                    if (alphas == null) return;
                    int[] adjusted = alphas.clone();
                    float scale = (100 - degree) / 40.0f; // native 60 keeps its original mask
                    if (glass > 0) scale *= 1f - 0.6f * glass / 100f; // v1.6: glass tiles carry the look; thin the milky native white mask over dark apps
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
                context.getContentResolver().registerContentObserver(SettingsProvider.URI, true,
                    new ContentObserver(main) { @Override public void onChange(boolean self) {
                        main.removeCallbacks(update); main.postDelayed(update, 80);
                    }});
                load(false);
            }
        });
    }
    private static XC_MethodHook toolbarScope(final boolean inner) {
        return new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                p.setObjectExtra("previousToolbar", toolbarBackground.get());
                int[] d = toolbarDepth.get(); if (d == null) { d = new int[1]; toolbarDepth.set(d); } d[0]++;
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
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                int[] d = toolbarDepth.get(); if (d != null && d[0] > 0) d[0]--;
                endToolbarScale(p, (android.graphics.Canvas)p.args[0]);
                Object previous = p.getObjectExtra("previousToolbar");
                if (previous == null) toolbarBackground.remove(); else toolbarBackground.set(previous);
            }
        };
    }
    private static final Runnable update = () -> load(true);
    private static void load(boolean apply) {
        if (context == null) return;
        try {
            Bundle b = context.getContentResolver().call(SettingsProvider.URI, "get", null, null);
            if (b == null) return;
            degree = Math.max(0, Math.min(100, b.getInt("degree", 60)));
            material = b.getInt("material", 0) == 1 ? 1 : 0;
            blur = Math.max(-1, Math.min(60, b.getInt("blur", -1)));
            keyTransparency = Math.max(0, Math.min(100, b.getInt("keys", 45)));
            glass = Math.max(0, Math.min(100, b.getInt("glass", 60)));
            hwWash = b.getInt("hwWash", 0) == 1 ? 1 : 0;
            long requestedRestart = b.getLong("restart", 0);
            boolean restarting = configLoaded && requestedRestart != restartToken;
            restartToken = requestedRestart;
            configLoaded = true;
            if (apply) {
                Class<?> cls = XposedHelpers.findClass("com.baidu.j40.k", keyboardLoader);
                Object manager = XposedHelpers.getStaticObjectField(cls, "a");
                XposedHelpers.callMethod(manager, "p0", degree);
                XposedHelpers.callMethod(manager, "q0", material);
                Object config = XposedHelpers.callMethod(manager, "r");
                XposedHelpers.callMethod(manager, "n0", config, true);
                XposedHelpers.callMethod(manager, "I"); // refresh cached key visuals as well
            }
            String msg = (apply ? "已应用" : "已连接") + " · 背景 " + degree + " · 按键 " + keyTransparency + " · 玻璃 " + glass + " · " + (material == 0 ? "液态" : "磨砂") + " · 模糊 " + (blur < 0 ? "自动" : blur) + " · PID " + android.os.Process.myPid();
            context.getContentResolver().call(SettingsProvider.URI, "report", msg, null);
            XposedBridge.log(TAG + ": config degree=" + degree + " material=" + material + " blur=" + blur + " hwWash=" + hwWash + " apply=" + apply);
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
