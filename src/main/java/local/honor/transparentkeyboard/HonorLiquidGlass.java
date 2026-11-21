package local.honor.transparentkeyboard;

import android.os.Looper;
import android.os.Handler;
import android.view.SurfaceControl;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * Default-off, reversible test hook: request Honor compositor liquid glass on
 * the IME window's own SurfaceControl only. Does not write system properties,
 * patch SurfaceFlinger, or expand the LSPosed scope. Whole-window request,
 * not per-key refraction. applied is cleared only after a successful close
 * or a confirmed invalid/destroyed Surface; otherwise cleanup stays pending.
 * Public status is a request/lifecycle fact, not compositor success.
 */
final class HonorLiquidGlass {
    private static final String TAG = "HonorTransparentKeyboard";
    private static final String FACTORY = "com.hihonor.android.graphics.material.HnMaterialFactory";
    private static final String MATERIAL = "com.hihonor.android.graphics.material.HnLiquidGlassMaterial";
    private static final String MANAGER = "com.hihonor.android.graphics.HnLiquidGlassWindowManger";

    private static final ReflectionBackend backend = new ReflectionBackend();
    private static final HonorLiquidGlassSession session = new HonorLiquidGlassSession(backend);
    private static volatile String lastMaterialSummary = "";
    private static volatile String lastEvent = "";
    private static volatile String lastCause = "";
    private static volatile Runnable publicStatusHook;
    private static final List<WeakReference<View>> seenFrames = new ArrayList<WeakReference<View>>();
    private static final int[] SURFACE_RETRY_DELAYS = {32, 96, 250, 600, 1200};
    // All retry state is owned by the IME UI thread.
    private static int retryGeneration;

    static {
        session.setAfterChange(new Runnable() {
            @Override public void run() { notifyPublicStatus(); }
        });
    }

    private HonorLiquidGlass() {}

    static String status() { return session.status(); }
    static boolean requested() { return session.requested(); }
    static String diagHint() {
        return HonorLiquidGlassDiagnostics.publicHint(lastMaterialSummary);
    }

    /** Public status only. Hook must not re-enter session enable/disable. */
    static void setPublicStatusHook(Runnable hook) {
        publicStatusHook = hook;
    }

    static void setRequested(boolean on) {
        View frame = pickUsableFrame();
        runOnFrame(frame, new Runnable() {
            @Override public void run() {
                View picked = on ? pickUsableFrame() : null;
                int generation = ++retryGeneration;
                HonorLiquidGlassRequest.applyRequested(session, on, picked);
                retrySurface(picked, generation, 0);
                log();
            }
        });
    }

    static void attach(View view) {
        if (view == null) return;
        remember(view);
        runOnFrame(view, new Runnable() {
            @Override public void run() {
                session.attach(view);
                retrySurface(view, ++retryGeneration, 0);
                log();
            }
        });
    }

    static void onVisibility(View view, int visibility) {
        if (view == null) return;
        remember(view);
        if (visibility == View.VISIBLE) {
            attach(view);
            return;
        }
        runOnFrame(view, new Runnable() {
            @Override public void run() {
                session.onVisibilityHidden(view);
                log();
            }
        });
    }

    static void onDetached(View view) {
        if (view == null) return;
        runOnFrame(view, new Runnable() {
            @Override public void run() {
                session.onDetached(view);
                log();
            }
        });
    }

    private static void remember(View view) {
        if (view == null) return;
        synchronized (seenFrames) {
            Iterator<WeakReference<View>> it = seenFrames.iterator();
            while (it.hasNext()) {
                View v = it.next().get();
                if (v == null || v == view) it.remove();
            }
            seenFrames.add(0, new WeakReference<View>(view));
        }
    }

    private static void retrySurface(View frame, int generation, int attempt) {
        if (attempt >= SURFACE_RETRY_DELAYS.length || !needsSurfaceRetry(frame, generation)) return;
        Handler handler = frame.getHandler();
        if (handler == null) return;
        handler.postDelayed(() -> {
            if (!needsSurfaceRetry(frame, generation)) return;
            session.enable();
            log();
            retrySurface(frame, generation, attempt + 1);
        }, SURFACE_RETRY_DELAYS[attempt]);
    }

    private static boolean needsSurfaceRetry(View frame, int generation) {
        if (generation != retryGeneration || !session.requested()
                || frame == null || session.activeFrame() != frame || !usable(frame)) {
            return false;
        }
        String status = session.status();
        return "surface-null".equals(status) || "surface-invalid".equals(status);
    }

    static View pickUsableFrame() {
        Object active = session.activeFrame();
        View activeView = active instanceof View ? (View) active : null;
        boolean activeUsable = usable(activeView);
        View[] candidates;
        boolean[] flags;
        synchronized (seenFrames) {
            List<View> live = new ArrayList<View>();
            Iterator<WeakReference<View>> it = seenFrames.iterator();
            while (it.hasNext()) {
                View v = it.next().get();
                if (v == null) it.remove();
                else live.add(v);
            }
            candidates = live.toArray(new View[0]);
            flags = new boolean[candidates.length];
            for (int i = 0; i < candidates.length; i++) flags[i] = usable(candidates[i]);
        }
        Object picked = HonorLiquidGlassFrames.pick(activeView, activeUsable, candidates, flags);
        return picked instanceof View ? (View) picked : null;
    }

    static boolean usable(View view) {
        if (view == null) return false;
        return HonorLiquidGlassFrames.usable(
                view.isAttachedToWindow(),
                view.getVisibility() == View.VISIBLE,
                view.getWindowVisibility() == View.VISIBLE,
                view.isShown());
    }

    private static void notifyPublicStatus() {
        Runnable hook = publicStatusHook;
        if (hook != null) hook.run();
    }

    private static void log() {
        Object frame = session.activeFrame();
        boolean visible = frame instanceof View && ((View) frame).getVisibility() == View.VISIBLE;
        boolean surfaceValid = session.appliedSurface() != null && backend.isSurfaceValid(session.appliedSurface());
        int pid = android.os.Process.myPid();
        XposedBridge.log(TAG + ": hnLiquid requested=" + session.requested()
                + " applied=" + session.applied()
                + " status=" + session.status()
                + " " + HonorLiquidGlassDiagnostics.eventLine("status", visible, surfaceValid, pid, lastMaterialSummary));
        if (lastCause != null && !lastCause.isEmpty()) {
            XposedBridge.log(TAG + ": hnLiquid diag " + lastCause);
        }
        notifyPublicStatus();
    }

    private static void runOnFrame(View view, Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper() || view == null) action.run();
        else view.post(action);
    }

    private static final class ReflectionBackend implements HonorLiquidGlassSession.Backend {
        @Override public Object createMaterial() throws Exception {
            Class<?> factory = Class.forName(FACTORY);
            int liquid = factory.getField("LIQUID_GLASS").getInt(null);
            Object material = factory.getMethod("createMaterial", int.class).invoke(null, Integer.valueOf(liquid));
            Class<?> matCls = Class.forName(MATERIAL);
            if (material == null || !matCls.isInstance(material)) {
                throw new IllegalStateException("material-type:" + (material == null ? "null" : material.getClass().getName()));
            }
            HonorLiquidGlassMaterials.attachNoArgParams(material);
            lastMaterialSummary = HonorLiquidGlassDiagnostics.inspectMaterial(material);
            XposedBridge.log(TAG + ": hnLiquid diag " + lastMaterialSummary);
            return material;
        }

        @Override public Object getSurface(Object frame) throws Exception {
            if (!(frame instanceof View)) return null;
            Class<?> mgr = Class.forName(MANAGER);
            return mgr.getMethod("getSurfaceControl", View.class).invoke(null, (View) frame);
        }

        @Override public void setEnabled(Object surface, boolean enable, Object material) throws Exception {
            Object frame = session.activeFrame();
            boolean visible = frame instanceof View && ((View) frame).getVisibility() == View.VISIBLE;
            boolean valid = isSurfaceValid(surface);
            int pid = android.os.Process.myPid();
            lastMaterialSummary = HonorLiquidGlassDiagnostics.inspectMaterial(material);
            lastEvent = HonorLiquidGlassDiagnostics.eventLine(enable ? "enable" : "disable", visible, valid, pid, lastMaterialSummary);
            lastCause = "";
            XposedBridge.log(TAG + ": hnLiquid diag " + lastEvent);
            try {
                Class<?> mgr = Class.forName(MANAGER);
                Class<?> matCls = Class.forName(MATERIAL);
                mgr.getMethod("setWindowLiquidGlassEnable", SurfaceControl.class, boolean.class, matCls)
                        .invoke(null, surface, Boolean.valueOf(enable), material);
            } catch (Throwable t) {
                lastCause = HonorLiquidGlassDiagnostics.causeChain(t);
                XposedBridge.log(TAG + ": hnLiquid diag fail " + lastEvent + " " + lastCause);
                if (t instanceof Exception) throw (Exception) t;
                throw new Exception(t);
            }
        }

        @Override public boolean isSurfaceValid(Object surface) {
            if (surface == null) return false;
            if (surface instanceof SurfaceControl) {
                try {
                    return ((SurfaceControl) surface).isValid();
                } catch (Throwable ignored) {
                    return true;
                }
            }
            return true;
        }

        @Override public boolean isFrameAttached(Object frame) {
            return frame instanceof View && ((View) frame).isAttachedToWindow();
        }

        @Override public boolean isFrameEligibleToEnable(Object frame) {
            return frame instanceof View && HonorLiquidGlass.usable((View) frame);
        }
    }
}
