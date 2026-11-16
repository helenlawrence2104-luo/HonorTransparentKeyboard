package local.honor.transparentkeyboard;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** v1.7 fog diagnostics: which IME-owned window draws what over the app (no text/content is logged, only geometry and colours). */
final class FogProbe {
    private static final String TAG = "HonorTransparentKeyboard: fog";
    private static final List<WeakReference<View>> roots = new ArrayList<>();
    private static int probes;

    static void install(ClassLoader cl) {
        try {
            XposedBridge.hookAllMethods(XposedHelpers.findClass("android.view.WindowManagerGlobal", null), "addView", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        View v = (View) p.args[0];
                        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) p.args[1];
                        XposedBridge.log(TAG + " addView " + v.getClass().getName() + " title=" + lp.getTitle() + " type=" + lp.type
                            + " w=" + lp.width + " h=" + lp.height + " x=" + lp.x + " y=" + lp.y + " flags=0x" + Integer.toHexString(lp.flags)
                            + " fmt=" + lp.format + " dim=" + lp.dimAmount + " blurBehind=" + (android.os.Build.VERSION.SDK_INT >= 31 ? lp.getBlurBehindRadius() : -1)
                            + " bg=" + describe(v.getBackground()));
                        synchronized (roots) { roots.add(new WeakReference<>(v)); }
                    } catch (Throwable t) { XposedBridge.log(TAG + " addView log " + t); }
                }
            });
        } catch (Throwable t) { XposedBridge.log(TAG + " addView hook failed " + t); }
        try {
            XC_MethodHook show = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        android.widget.PopupWindow w = (android.widget.PopupWindow) p.thisObject;
                        View c = w.getContentView();
                        StringBuilder sb = new StringBuilder(TAG + " popup." + p.method.getName() + " content=" + (c == null ? null : c.getClass().getName())
                            + " w=" + w.getWidth() + " h=" + w.getHeight() + " bg=" + describe(w.getBackground()) + " from");
                        int n = 0;
                        for (StackTraceElement e : new Throwable().getStackTrace()) {
                            String cn = e.getClassName();
                            if (cn.startsWith("com.baidu") || cn.startsWith("com.hihonor")) { sb.append(' ').append(cn).append('.').append(e.getMethodName()); if (++n >= 5) break; }
                        }
                        XposedBridge.log(sb.toString());
                    } catch (Throwable t) { XposedBridge.log(TAG + " popup log " + t); }
                }
            };
            XposedBridge.hookAllMethods(android.widget.PopupWindow.class, "showAtLocation", show);
            XposedBridge.hookAllMethods(android.widget.PopupWindow.class, "showAsDropDown", show);
        } catch (Throwable t) { XposedBridge.log(TAG + " popup hook failed " + t); }
        try {
            XposedHelpers.findAndHookMethod("android.inputmethodservice.InputMethodService", cl, "onWindowShown", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (probes >= 8) return;
                    final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                    h.postDelayed(FogProbe::probeAll, 900);
                }
            });
        } catch (Throwable t) { XposedBridge.log(TAG + " onWindowShown hook failed " + t); }
    }

    static String describe(Drawable d) {
        if (d == null) return "null";
        if (d instanceof ColorDrawable) return "Color#" + Integer.toHexString(((ColorDrawable) d).getColor()) + " a=" + d.getAlpha();
        return d.getClass().getName() + " a=" + d.getAlpha() + " op=" + d.getOpacity();
    }

    static void probeAll() {
        if (probes++ >= 8) return;
        List<View> live = new ArrayList<>();
        synchronized (roots) {
            for (int i = roots.size() - 1; i >= 0; i--) { View v = roots.get(i).get(); if (v == null || !v.isAttachedToWindow()) roots.remove(i); else live.add(v); }
        }
        XposedBridge.log(TAG + " probe#" + probes + " roots=" + live.size());
        for (View v : live) probe(v);
    }

    private static void probe(View root) {
        int[] loc = new int[2]; root.getLocationOnScreen(loc);
        XposedBridge.log(TAG + " root " + root.getClass().getName() + " screen=" + loc[0] + "," + loc[1] + " size=" + root.getWidth() + "x" + root.getHeight() + " vis=" + root.getVisibility() + " shown=" + root.isShown());
        StringBuilder sb = new StringBuilder();
        dump(root, 0, sb, new int[]{0});
        for (String line : sb.toString().split("\n")) if (!line.isEmpty()) XposedBridge.log(TAG + "   " + line);
        if (root.getWidth() <= 0 || root.getHeight() <= 0 || !root.isShown()) return;
        try { // software render at 1/8 scale; average colour in 4 horizontal bands (screen coordinates)
            int s = 8, bw = Math.max(1, root.getWidth() / s), bh = Math.max(1, root.getHeight() / s);
            Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b); c.scale(1f / s, 1f / s);
            root.draw(c);
            StringBuilder r = new StringBuilder(TAG + " render " + root.getClass().getName());
            for (int band = 0; band < 6; band++) {
                int y0 = bh * band / 6, y1 = bh * (band + 1) / 6; long A = 0, R = 0, G = 0, B = 0, n = 0; int maxA = 0;
                for (int y = y0; y < y1; y++) for (int x = 0; x < bw; x += 2) { int px = b.getPixel(x, y); int a = px >>> 24; A += a; R += (px >> 16) & 255; G += (px >> 8) & 255; B += px & 255; n++; if (a > maxA) maxA = a; }
                if (n == 0) continue;
                r.append(" | y").append(loc[1] + y0 * s).append("-").append(loc[1] + y1 * s).append(" A").append(A / n).append(" max").append(maxA).append(" rgb(premul)=").append(R / n).append(",").append(G / n).append(",").append(B / n);
            }
            b.recycle();
            XposedBridge.log(r.toString());
        } catch (Throwable t) { XposedBridge.log(TAG + " render failed " + t); }
    }

    private static void dump(View v, int depth, StringBuilder sb, int[] count) {
        if (count[0]++ > 60 || depth > 7) return;
        int[] l = new int[2]; v.getLocationOnScreen(l);
        String id = "";
        try { if (v.getId() != View.NO_ID) id = " id=" + v.getResources().getResourceEntryName(v.getId()); } catch (Throwable ignored) {}
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getName()).append(id).append(" [").append(l[0]).append(",").append(l[1]).append(" ").append(v.getWidth()).append("x").append(v.getHeight())
          .append("] vis=").append(v.getVisibility()).append(" alpha=").append(v.getAlpha()).append(" bg=").append(describe(v.getBackground()))
          .append(v.willNotDraw() ? " noDraw" : "").append('\n');
        if (v instanceof ViewGroup && v.getVisibility() == View.VISIBLE) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dump(g.getChildAt(i), depth + 1, sb, count);
        }
    }
}