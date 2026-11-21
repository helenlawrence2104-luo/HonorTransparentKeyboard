package local.honor.transparentkeyboard;

/**
 * IME-window liquid-glass request state. No Android types so branches can be
 * tested with javac. applied means a leftover or live target may exist;
 * enableOk (confirmed-enabled) is set only after setEnabled(true) returns.
 * A failed enable whose compensating disable also fails, or a failed disable,
 * stays pending (enableOk false). The next enable on the same frame must
 * close that residue first; if close fails, status stays pending and must
 * not become enable-ok. Only after a successful close may a new create/enable
 * run. applied is cleared only after a successful close or a confirmed
 * invalid/destroyed Surface.
 */
final class HonorLiquidGlassSession {
    interface Backend {
        Object createMaterial() throws Exception;
        Object getSurface(Object frame) throws Exception;
        void setEnabled(Object surface, boolean enable, Object material) throws Exception;
        boolean isSurfaceValid(Object surface);
        boolean isFrameAttached(Object frame);
        /** Same enable eligibility HonorLiquidGlass.usable(View) uses; cleanup paths must not consult this to skip disable. */
        boolean isFrameEligibleToEnable(Object frame);
    }

    private final Backend backend;
    private boolean requested;
    private boolean applied;
    private Object activeFrame;
    private Object appliedFrame;
    private Object appliedSurface;
    private Object appliedMaterial;
    private String status = "off";
    /** True only after setEnabled(true) returned. Pending/compensated failures stay false. */
    private boolean enableOk;

    HonorLiquidGlassSession(Backend backend) {
        this.backend = backend;
    }

    boolean requested() { return requested; }
    boolean applied() { return applied; }
    String status() { return status; }

    private Runnable afterChange;
    final ReentrancyGuard statusNotify = new ReentrancyGuard();
    void setAfterChange(Runnable r) { afterChange = r; }
    private void setStatus(String next) {
        status = next;
        notifyChanged();
    }
    private void notifyChanged() {
        statusNotify.run(afterChange);
    }
    Object activeFrame() { return activeFrame; }
    Object appliedFrame() { return appliedFrame; }
    Object appliedSurface() { return appliedSurface; }
    Object appliedMaterial() { return appliedMaterial; }

    void setRequested(boolean on) {
        requested = on;
        if (on) enable();
        else disable("switch-off");
    }

    void attach(Object frame) {
        if (frame == null) return;
        activeFrame = frame;
        if (hasTarget() && appliedFrame != null && appliedFrame != frame) {
            disable("frame-switch");
            return;
        }
        if (requested) enable();
        else if (hasTarget()) disable("attached-switch-off");
        else setStatus("off");
    }

    void onVisibilityHidden(Object frame) {
        if (frame == null) return;
        if (!matchesApplied(frame) && !matchesActive(frame)) return;
        if (hasTarget() && matchesApplied(frame)) disable("visibility-hidden");
        if (matchesActive(frame) && !hasTarget()) {
            /* hidden current frame with nothing to clean */
        }
    }

    void onDetached(Object frame) {
        if (frame == null) return;
        if (matchesApplied(frame)) {
            disable("detached");
        }
        if (matchesActive(frame) && !matchesApplied(frame)) {
            activeFrame = null;
        } else if (matchesActive(frame) && !hasTarget()) {
            activeFrame = null;
        }
    }

    void enable() {
        if (!requested) return;
        if (hasTarget() && appliedFrame != null && activeFrame != null && appliedFrame != activeFrame) {
            disable("rebind");
            if (hasTarget()) return;
        }
        if (enableOk && hasTarget() && appliedFrame == activeFrame && backend.isSurfaceValid(appliedSurface)) {
            if (frameEligibleToEnable(activeFrame)) {
                setStatus("enable-ok");
                return;
            }
            disable("visibility-hidden");
            return;
        }
        if (hasTarget()) {
            if (!clearPendingForRetry()) return;
        }
        Object frame = activeFrame;
        if (frame == null || !backend.isFrameAttached(frame)) {
            String waiting;
            if (frame == null && requested && !hasTarget()) waiting = "on-waiting-attach";
            else if (frame != null && !backend.isFrameAttached(frame) && !hasTarget()) waiting = "not-attached";
            else waiting = requested ? "on-waiting-attach" : "not-attached";
            setStatus(waiting);
            return;
        }
        if (!frameEligibleToEnable(frame)) {
            setStatus("on-waiting-visible");
            return;
        }
        Object material = null;
        Object surface = null;
        boolean attemptStarted = false;
        try {
            surface = backend.getSurface(frame);
            if (surface == null) {
                setStatus("surface-null");
                return;
            }
            if (!backend.isSurfaceValid(surface)) {
                setStatus("surface-invalid");
                return;
            }
            material = backend.createMaterial();
            if (material == null) {
                setStatus("material-null");
                return;
            }
            rememberAttempt(frame, surface, material);
            attemptStarted = true;
            backend.setEnabled(surface, true, material);
            applied = true;
            enableOk = true;
            setStatus("enable-ok");
        } catch (Throwable t) {
            enableOk = false;
            String name = t.getClass().getSimpleName();
            if (attemptStarted) {
                applied = true;
                rememberAttempt(frame, surface, material);
                try {
                    backend.setEnabled(surface, false, material);
                    if (!backend.isSurfaceValid(surface)) {
                        clearTarget();
                        setStatus("enable-fail-compensated:" + name);
                    } else {
                        /* close returned; treat as compensated only if close did not throw */
                        clearTarget();
                        setStatus("enable-fail-compensated:" + name);
                    }
                } catch (Throwable t2) {
                    applied = true;
                    rememberAttempt(frame, surface, material);
                    setStatus("enable-fail-pending:" + name);
                }
            } else {
                setStatus("enable-fail:" + name);
            }
        }
    }

    void disable(String reason) {
        if (!hasTarget()) {
            if (!requested) setStatus("off");
            return;
        }
        if (appliedSurface == null || appliedMaterial == null) {
            setStatus("disable-pending:" + reason + ":missing-target");
            return;
        }
        if (!backend.isSurfaceValid(appliedSurface)) {
            clearTarget();
            setStatus(requested ? ("on-cleared-invalid-" + reason) : "off");
            if (shouldEnableAfterClose(reason)) enable();
            return;
        }
        try {
            backend.setEnabled(appliedSurface, false, appliedMaterial);
            clearTarget();
            setStatus(requested ? ("on-paused-" + reason) : "off");
            if (shouldEnableAfterClose(reason)) enable();
        } catch (Throwable t) {
            enableOk = false;
            setStatus("disable-fail:" + reason + ":" + t.getClass().getSimpleName());
        }
    }

    /**
     * Close a pending or disable-fail residue before a new enable.
     * @return true only when no target remains
     */
    private boolean clearPendingForRetry() {
        if (!hasTarget()) return true;
        enableOk = false;
        if (appliedSurface == null || appliedMaterial == null) {
            if (status == null || !(status.startsWith("enable-fail-pending:")
                    || status.startsWith("disable-fail:")
                    || status.startsWith("disable-pending:"))) {
                setStatus("disable-pending:retry:missing-target");
            }
            return false;
        }
        if (!backend.isSurfaceValid(appliedSurface)) {
            clearTarget();
            return true;
        }
        try {
            backend.setEnabled(appliedSurface, false, appliedMaterial);
            clearTarget();
            return true;
        } catch (Throwable t) {
            enableOk = false;
            if (status == null || !(status.startsWith("enable-fail-pending:")
                    || status.startsWith("disable-fail:"))) {
                setStatus("enable-fail-pending:" + t.getClass().getSimpleName());
            }
            return false;
        }
    }

    private boolean shouldEnableAfterClose(String reason) {
        if (!requested || activeFrame == null || !backend.isFrameAttached(activeFrame)) return false;
        if ("detached".equals(reason) || "visibility-hidden".equals(reason)) return false;
        if (!frameEligibleToEnable(activeFrame)) return false;
        return true;
    }

    /** Open-path only. Disable, detach, and pending close must not use this to skip cleanup. */
    private boolean frameEligibleToEnable(Object frame) {
        return frame != null && backend.isFrameEligibleToEnable(frame);
    }

    private boolean hasTarget() {
        return applied || appliedSurface != null || appliedMaterial != null;
    }

    private boolean matchesApplied(Object frame) {
        return frame != null && frame == appliedFrame;
    }

    private boolean matchesActive(Object frame) {
        return frame != null && frame == activeFrame;
    }

    private void rememberAttempt(Object frame, Object surface, Object material) {
        appliedFrame = frame;
        appliedSurface = surface;
        appliedMaterial = material;
    }

    private void clearTarget() {
        applied = false;
        enableOk = false;
        appliedFrame = null;
        appliedSurface = null;
        appliedMaterial = null;
    }
}
