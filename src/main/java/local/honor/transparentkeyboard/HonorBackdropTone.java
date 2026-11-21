package local.honor.transparentkeyboard;

import android.view.View;
import android.view.ViewGroup;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Uses only Honor's aggregate brightness for the keyboard area. */
final class HonorBackdropTone {
    enum Tone { UNKNOWN, DARK, MIXED, LIGHT }

    private static final String API = "com.hihonor.android.graphics.material.HnMaterialManager";
    private static WeakReference<View> target = new WeakReference<>(null);
    private static Tone tone = Tone.UNKNOWN;
    private static boolean unavailable;
    private static boolean editorActive;

    private HonorBackdropTone() {}

    static Tone current() { return tone; }

    static void onEditorChanged(boolean active) {
        editorActive = active;
        detach(target.get());
        tone = Tone.UNKNOWN;
    }

    static void observe(View panel) {
        if (!editorActive || !panel.isShown() || unavailable || target.get() == panel) return;
        detach(target.get());
        try {
            Class<?> manager = Class.forName(API);
            Class<?> listenerType = Class.forName(API + "$IColorPickListenerEx");
            Class<?> paramsType = Class.forName(API + "$ColorPickParams");
            Object params = paramsType.getConstructor().newInstance();
            XposedHelpers.setBooleanField(params, "crossLayer", true);
            XposedHelpers.setBooleanField(params, "needCallback", true);
            XposedHelpers.setIntField(params, "frameInterval", 12);
            XposedHelpers.setIntField(params, "mode",
                    paramsType.getField("COLOR_PICK_MODE_VALUE").getInt(null));
            WeakReference<View> watched = new WeakReference<>(panel);
            Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(),
                    new Class<?>[]{listenerType}, (proxy, method, args) -> {
                        if ("colorChangedEx".equals(method.getName()) && args[1] != null) {
                            View view = watched.get();
                            int value = XposedHelpers.getIntField(args[1], "avgValue");
                            if (view != null && value >= 0 && value <= 100) {
                                view.post(() -> {
                                    if (target == watched) update(view, value);
                                });
                            }
                        }
                        return null;
                    });
            XposedHelpers.callStaticMethod(manager, "setColorPickListener", panel, listener, params);
            target = watched;
        } catch (Throwable error) {
            unavailable = true;
            XposedBridge.log("HonorGlass: backdrop brightness unavailable " + error);
        }
    }

    static void detach(View panel) {
        if (panel == null || target.get() != panel) return;
        target.clear();
        tone = Tone.UNKNOWN;
        try {
            XposedHelpers.callStaticMethod(Class.forName(API), "removeColorPickListener", panel);
        } catch (Throwable error) {
            XposedBridge.log("HonorGlass: backdrop listener cleanup failed " + error);
        }
    }

    private static void update(View view, int value) {
        if (target.get() != view || !view.isShown() || view.getWindowVisibility() != View.VISIBLE) return;
        Tone updated;
        if (value <= (tone == Tone.DARK ? 39 : 32)) updated = Tone.DARK;
        else if (value >= (tone == Tone.LIGHT ? 80 : 87)) updated = Tone.LIGHT;
        else updated = Tone.MIXED;
        if (updated == tone) return;
        tone = updated;
        XposedBridge.log("HonorGlass: backdrop tone " + updated);
        invalidateTree(view.getRootView());
    }

    private static void invalidateTree(View view) {
        view.invalidate();
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            invalidateTree(group.getChildAt(index));
        }
    }
}
