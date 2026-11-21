package local.honor.transparentkeyboard;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.view.View;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * One-shot key haptic replacement. Interval values are pulse gaps in milliseconds,
 * not hardware vibration frequency in Hz. Never uses a repeating pattern.
 *
 * Honor IME key presses call {@code com.hihonor.android.os.VibratorExDlg#setHwVibrator(String)}
 * (delegating to {@code VibratorEx}), not {@code Vibrator.vibrate}. Those methods are hooked
 * in addition to the standard vibrator / view paths. Custom playback uses
 * {@code VibrationEffect} only, with a reentrancy flag so it does not re-enter the hooks.
 */
final class KeyHaptics {
    private static final String TAG = "HonorTransparentKeyboard";
    static volatile boolean enabled = true;
    /** 0-100 user scale; 0% maps to amplitude 1 (minimum), not off. */
    static volatile int intensity = 60;
    /** Pulse width in milliseconds (5-80). */
    static volatile int durationMs = 20;
    /** 0 = single pulse, 1 = double, 2 = triple. */
    static volatile int rhythm = 0;
    /** Gap between pulses in milliseconds (20-120). Not Hz. */
    static volatile int intervalMs = 40;
    static volatile int amplitudeSupported = -1; // -1 unknown, 0 no, 1 yes
    /** 1 if at least one Honor setHwVibrator hook was installed in this process. */
    static volatile int honorHooked = 0;
    /** Count of Honor keyboard types successfully replaced. No type strings or input text. */
    static volatile int honorIntercepts = 0;
    private static final ThreadLocal<Boolean> applying = new ThreadLocal<>();
    private static final java.util.Set<String> hooked = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    /** Same-press dual-path guard only; short enough not to swallow a following key. */
    private static final long COALESCE_MS = 16;
    private static volatile long lastCustomPlayAt;
    private static boolean viewHooked;
    private static boolean installedLogged;
    /** Saved in attach(); used when a Vibrator instance has no getContext(). */
    private static volatile Context appContext;
    private KeyHaptics() {}

    static void installViewHook() {
        if (viewHooked) return;
        viewHooked = true;
        XC_MethodHook hook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (applying.get() != null) return;
                Context ctx = null;
                try { ctx = ((View) p.thisObject).getContext(); } catch (Throwable ignored) {}
                int policy = systemHapticsPolicy(ctx);
                if (!enabled) return;
                if (KeyHapticsPattern.shouldSuppressOriginal(policy)) {
                    p.setResult(false);
                    return;
                }
                if (!KeyHapticsPattern.shouldPlayCustom(policy)) return;
                if (tryReplace(resolveContext(ctx))) p.setResult(true);
            }
        };
        try {
            XposedHelpers.findAndHookMethod(View.class, "performHapticFeedback", int.class, hook);
            XposedHelpers.findAndHookMethod(View.class, "performHapticFeedback", int.class, int.class, hook);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": haptic view hook failed " + t);
        }
    }

    /**
     * Hook the Honor IME path found in source: VibratorExDlg / VibratorEx.setHwVibrator.
     * Only {@code haptic.common.click_keyboard*} types are rewritten.
     */
    static void installHonorHooks(ClassLoader loader) {
        if (loader == null) return;
        hookHonorSetHw(findHonorClass("com.hihonor.android.os.VibratorExDlg", loader));
        hookHonorSetHw(findHonorClass("com.hihonor.android.os.VibratorEx", loader));
    }

    private static Class<?> findHonorClass(String name, ClassLoader loader) {
        try {
            return XposedHelpers.findClassIfExists(name, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void hookHonorSetHw(Class<?> cls) {
        if (cls == null) return;
        String key = cls.getName() + "#setHwVibrator";
        if (!hooked.add(key)) return;
        try {
            int n = XposedBridge.hookAllMethods(cls, "setHwVibrator", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (applying.get() != null) return;
                    String type = firstString(p.args);
                    if (!KeyHapticsHonorTypes.isKeyboardType(type)) return;
                    if (!enabled) return;
                    int policy = systemHapticsPolicy(appContext);
                    if (KeyHapticsPattern.shouldSuppressOriginal(policy)) {
                        p.setResult(null);
                        return;
                    }
                    if (!KeyHapticsPattern.shouldPlayCustom(policy)) return;
                    if (tryReplace(appContext)) {
                        honorIntercepts++;
                        p.setResult(null);
                    }
                }
            }).size();
            if (n > 0) {
                honorHooked = 1;
                XposedBridge.log(TAG + ": hooked " + cls.getName() + ".setHwVibrator count=" + n);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": haptic honor hook " + cls.getName() + " failed " + t);
        }
    }

    private static String firstString(Object[] args) {
        if (args == null) return null;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof String) return (String) args[i];
        }
        return null;
    }

    /** Play custom haptics, or treat a same-press second path as already handled. */
    private static boolean tryReplace(Context context) {
        if (recentlyPlayed()) return true;
        return play(context);
    }

    private static boolean recentlyPlayed() {
        return SystemClock.uptimeMillis() - lastCustomPlayAt < COALESCE_MS;
    }

    static void attach(Context context) {
        if (context == null) return;
        try {
            Context app = context.getApplicationContext();
            if (app == null) app = context;
            appContext = app;
            Object vib = app.getSystemService(Context.VIBRATOR_SERVICE);
            if (vib instanceof Vibrator) hookVibrator((Vibrator) vib);
            if (Build.VERSION.SDK_INT >= 31) {
                Object mgr = app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (mgr instanceof VibratorManager) {
                    hookClass(mgr.getClass());
                    hookVibrator(((VibratorManager) mgr).getDefaultVibrator());
                }
            }
            try { installHonorHooks(app.getClassLoader()); } catch (Throwable ignored) {}
            if (!installedLogged) {
                installedLogged = true;
                XposedBridge.log(TAG + ": haptics hooks attached amplitudeSupported=" + amplitudeSupported + " honorHooked=" + honorHooked);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": haptic attach failed " + t);
        }
    }

    private static void hookVibrator(Vibrator vibrator) {
        if (vibrator == null) return;
        try {
            if (amplitudeSupported < 0) amplitudeSupported = vibrator.hasAmplitudeControl() ? 1 : 0;
        } catch (Throwable ignored) {}
        hookClass(vibrator.getClass());
        hookClass(Vibrator.class);
    }

    private static void hookClass(Class<?> cls) {
        if (cls == null) return;
        String name = cls.getName();
        if (!hooked.add(name)) return;
        try {
            XposedBridge.hookAllMethods(cls, "vibrate", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (applying.get() != null) return;
                    if (!enabled) return;
                    Context ctx = contextOf(p.thisObject);
                    int policy = systemHapticsPolicy(ctx);
                    if (KeyHapticsPattern.shouldSuppressOriginal(policy)) {
                        p.setResult(null);
                        return;
                    }
                    if (!KeyHapticsPattern.shouldPlayCustom(policy)) return;
                    if (tryReplace(resolveContext(ctx))) p.setResult(null);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": haptic hook " + name + " failed " + t);
        }
    }

    private static Context contextOf(Object vibrator) {
        try {
            Object ctx = XposedHelpers.callMethod(vibrator, "getContext");
            if (ctx instanceof Context) return (Context) ctx;
        } catch (Throwable ignored) {}
        return appContext;
    }

    private static Context resolveContext(Context preferred) {
        return preferred != null ? preferred : appContext;
    }

    /**
     * ALLOW only when the system haptic flag is explicitly on.
     * BLOCK when it is explicitly off (do not play custom, and suppress the original).
     * UNKNOWN when there is no context or the setting cannot be read (do not play custom,
     * leave the original feedback alone).
     */
    static int systemHapticsPolicy(Context context) {
        Context ctx = resolveContext(context);
        if (ctx == null) return KeyHapticsPattern.POLICY_UNKNOWN;
        try {
            int enabled = Settings.System.getInt(ctx.getContentResolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED);
            return enabled != 0 ? KeyHapticsPattern.POLICY_ALLOW : KeyHapticsPattern.POLICY_BLOCK;
        } catch (Throwable t) {
            return KeyHapticsPattern.POLICY_UNKNOWN;
        }
    }

    static boolean play(Context context) {
        Context ctx = resolveContext(context);
        if (ctx == null) return false;
        try {
            Vibrator vibrator = vibratorOf(ctx);
            return playOn(vibrator, ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    private static Vibrator vibratorOf(Context context) {
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager mgr = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (mgr != null) return mgr.getDefaultVibrator();
        }
        return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    private static boolean playOn(Object raw, Context context) {
        if (!(raw instanceof Vibrator)) {
            Context ctx = resolveContext(context);
            if (ctx == null) return false;
            raw = vibratorOf(ctx);
        }
        if (!(raw instanceof Vibrator)) return false;
        Vibrator vibrator = (Vibrator) raw;
        int dur = Math.max(5, Math.min(80, durationMs));
        int gap = Math.max(20, Math.min(120, intervalMs));
        int pulses = rhythm <= 0 ? 1 : (rhythm == 1 ? 2 : 3);
        int amp = KeyHapticsPattern.amplitudeFromPercent(intensity);
        boolean hasAmp = false;
        try {
            hasAmp = vibrator.hasAmplitudeControl();
            amplitudeSupported = hasAmp ? 1 : 0;
        } catch (Throwable ignored) {}
        VibrationEffect effect = buildEffect(dur, gap, pulses, amp, hasAmp);
        if (effect == null) return false;
        applying.set(Boolean.TRUE);
        try {
            try { vibrator.cancel(); } catch (Throwable ignored) {}
            vibrator.vibrate(effect);
            lastCustomPlayAt = SystemClock.uptimeMillis();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            applying.remove();
        }
    }

    static VibrationEffect buildEffect(int dur, int gap, int pulses, int amp, boolean hasAmp) {
        try {
            KeyHapticsPattern p = KeyHapticsPattern.create(dur, gap, pulses, amp, hasAmp);
            if (p.oneShot) {
                return VibrationEffect.createOneShot(p.oneShotDurationMs, p.oneShotAmplitude);
            }
            if (p.amplitudes != null) return VibrationEffect.createWaveform(p.timings, p.amplitudes, KeyHapticsPattern.REPEAT_NONE);
            return VibrationEffect.createWaveform(p.timings, KeyHapticsPattern.REPEAT_NONE);
        } catch (Throwable t) {
            try {
                return VibrationEffect.createOneShot(dur, VibrationEffect.DEFAULT_AMPLITUDE);
            } catch (Throwable t2) {
                return null;
            }
        }
    }
}
