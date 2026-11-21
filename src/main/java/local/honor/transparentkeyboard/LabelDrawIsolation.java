package local.honor.transparentkeyboard;

/**
 * Testable label-draw isolation and per-call hook resource cleanup.
 * KeyboardHook must use CallResources for entered/paint/canvas-save/scale/toolbar
 * state so tests exercise the same wiring. No Android types, no character
 * content, no claim that IME night mode is the real backdrop, and no claim
 * that source-confirmed narrow hooks have taken effect on a device.
 */
final class LabelDrawIsolation {
    private LabelDrawIsolation() {}

    /** Do not hook every Canvas text overload. */
    static final boolean ALLOW_PLATFORM_HOOK_ALL_TEXT_APIS = false;

    /** Proven IME glyph call: Canvas.drawText(String, float, float, Paint). */
    static final String PROVEN_DRAW_TEXT_NAME = "drawText";
    static final int PROVEN_DRAW_TEXT_ARITY = 4;

    /** Software host (declared wrappers) and hardware override host. */
    static final String SOFTWARE_CANVAS_CLASS = "android.graphics.Canvas";
    static final String HARDWARE_CANVAS_CLASS = "android.graphics.BaseRecordingCanvas";

    static String[] drawTextHostClasses() {
        return new String[] { SOFTWARE_CANVAS_CLASS, HARDWARE_CANVAS_CLASS };
    }

    static boolean isProvenImeDrawText(String methodName, String[] paramTypeNames) {
        if (!PROVEN_DRAW_TEXT_NAME.equals(methodName) || paramTypeNames == null) return false;
        if (paramTypeNames.length != PROVEN_DRAW_TEXT_ARITY) return false;
        return isStringType(paramTypeNames[0])
            && isFloatType(paramTypeNames[1])
            && isFloatType(paramTypeNames[2])
            && isPaintType(paramTypeNames[3]);
    }

    static boolean isRejectedBroadTextApi(String methodName) {
        if (methodName == null) return false;
        return "drawTextRun".equals(methodName)
            || "drawTextOnPath".equals(methodName)
            || "drawPosText".equals(methodName)
            || "drawGlyphs".equals(methodName)
            || methodName.startsWith("drawTextRunUse")
            || methodName.startsWith("drawTextUse");
    }

    /**
     * Tint only when this call entered a key/toolbar frame, the Canvas identity
     * matches that frame, and the ink policy (IME night bit + glass) is on.
     * Night is not a real-backdrop measurement.
     */
    static boolean shouldTint(boolean entered, boolean canvasMatches, boolean imeNightFlag, int glass) {
        return entered && canvasMatches && KeyLabelLook.useLightInk(imeNightFlag, glass);
    }

    static boolean canvasMatches(Object frameCanvas, Object drawCanvas) {
        return frameCanvas != null && frameCanvas == drawCanvas;
    }

    static final class Frame {
        boolean entered;
        Object canvas;
        Object plate;
        float radius;
        boolean overlayApplied;
        boolean nativePlateDrawn;
        boolean pressed;
    }

    static final class Stack {
        private final java.util.ArrayList<Frame> frames = new java.util.ArrayList<Frame>();

        int depth() { return frames.size(); }

        Frame current() {
            return frames.isEmpty() ? null : frames.get(frames.size() - 1);
        }

        /** Push a new entered frame, preserving any parent plate/overlay untouched. */
        Frame enter(Object canvas) {
            Frame f = new Frame();
            f.entered = true;
            f.canvas = canvas;
            frames.add(f);
            return f;
        }

        /**
         * Leave only if this call actually entered. Returns false and does not
         * pop a parent when entered is false (unmatched afterHook).
         * Not intrinsically idempotent: a second true leave while a parent
         * frame remains will pop that parent. Callers must consume a per-call
         * flag and invoke this at most once (see CallResources.cleanup).
         */
        boolean leaveIfEntered(boolean entered) {
            if (!entered) return false;
            if (frames.isEmpty()) return false;
            frames.remove(frames.size() - 1);
            return true;
        }

        void rememberPlate(Object plate, float radius) {
            Frame f = current();
            if (f == null || !f.entered) return;
            f.plate = plate;
            f.radius = radius;
        }

        boolean applyOverlayIfNeeded() {
            Frame f = current();
            if (f == null || !f.entered || f.overlayApplied || f.plate == null) return false;
            f.overlayApplied = true;
            return true;
        }
    }

    /** Per-call original paint color including alpha. */
    static final class PaintSave {
        boolean saved;
        int argb;

        void save(int argb) {
            this.argb = argb;
            this.saved = true;
        }

        /** Unconditional restore of this call's color; no-op if this call never saved. */
        Integer restoreOrNull() {
            if (!saved) return null;
            saved = false;
            return Integer.valueOf(argb);
        }
    }

    /** Per-call Canvas save point. */
    static final class SavePoint {
        boolean opened;
        int id;

        boolean consumed;

        void open(int saveId) {
            if (opened || consumed) return;
            this.id = saveId;
            this.opened = true;
        }

        Integer closeOrNull() {
            if (!opened) return null;
            opened = false;
            consumed = true;
            return Integer.valueOf(id);
        }
    }

    /** Host Canvas/Paint operations. Hook and tests supply their own implementation. */
    interface CanvasOps {
        void restoreToCount(Object canvas, int saveId);
        void setPaintColor(Object paint, int argb);
        int save(Object canvas);
        void scale(Object canvas, float sx, float sy, float px, float py);
    }

    /** Thread-local-like slot; set(null) clears. */
    interface ObjectSlot {
        void set(Object value);
    }

    /**
     * Per-call resources stored on the hooked MethodHookParam and cleaned by
     * the same cleanup() from before-compensation and after. Each resource is
     * consumed once. Failure of one restore still runs the remaining finally
     * blocks. Does not rely on afterHookedMethod being skipped when before throws.
     */
    static final class CallResources {
        static final String EXTRA_KEY = "htkCallResources";

        private boolean entered;
        private boolean leaveConsumed;
        final PaintSave paint = new PaintSave();
        Object paintTarget;
        final SavePoint canvasSave = new SavePoint();
        private Object canvasSaveCanvas;
        private boolean scaleDepthHeld;
        private boolean scaleDepthConsumed;
        private boolean toolbarDepthHeld;
        private boolean toolbarDepthConsumed;
        private boolean toolbarBgHeld;
        private boolean toolbarBgConsumed;
        private Object previousToolbar;

        boolean entered() { return entered; }
        boolean leaveConsumed() { return leaveConsumed; }
        boolean canvasSaveOpened() { return canvasSave.opened; }
        Object boundCanvas() { return canvasSaveCanvas; }
        boolean scaleDepthActive() { return scaleDepthHeld && !scaleDepthConsumed; }
        boolean toolbarDepthActive() { return toolbarDepthHeld && !toolbarDepthConsumed; }
        boolean toolbarBgActive() { return toolbarBgHeld && !toolbarBgConsumed; }
        Object previousToolbar() { return previousToolbar; }

        void markEntered() { entered = true; }

        /**
         * Record a successful Canvas.save / saveLayerAlpha return value
         * immediately, before any transform. Bound to that canvas. First
         * registration wins; a second open is ignored so the first id is not lost.
         */
        void registerCanvasSave(Object canvas, int saveId) {
            if (canvas == null) return;
            if (canvasSave.opened || canvasSave.consumed) return;
            canvasSave.open(saveId);
            canvasSaveCanvas = canvas;
        }

        void markScaleDepthHeld() { scaleDepthHeld = true; }
        void markToolbarDepthHeld() { toolbarDepthHeld = true; }

        void holdToolbarBackground(Object previous) {
            if (toolbarBgHeld) return;
            toolbarBgHeld = true;
            previousToolbar = previous;
        }

        void savePaint(Object paintObj, int argb) {
            paintTarget = paintObj;
            paint.save(argb);
        }

        /**
         * Increment scale depth; only the outermost call saves and scales.
         * The save id is registered before the first transform. If scale
         * throws, the save is already bound and cleanup can consume it once.
         */
        static void applyOutermostScale(CallResources st, int[] depth, Object canvas,
                CanvasOps ops, float factor, float cx, float cy) {
            if (st == null || depth == null) return;
            depth[0]++;
            st.markScaleDepthHeld();
            if (depth[0] != 1 || canvas == null || ops == null) return;
            int id = ops.save(canvas);
            st.registerCanvasSave(canvas, id);
            ops.scale(canvas, factor, factor, cx, cy);
        }

        /**
         * Enter a label-draw frame and mark this call so cleanup leaves once.
         */
        static void enterCall(Stack stack, CallResources st, Object canvas) {
            if (stack == null || st == null) return;
            stack.enter(canvas);
            st.markEntered();
        }

        /**
         * One-shot cleanup shared by before compensation and after.
         * leaveIfEntered(true) is invoked at most once for this call.
         * afterSavesBeforeLeave runs after the canvas save is consumed and
         * before leave (e.g. overlay), and is not itself a consumed resource.
         */
        void cleanup(Stack stack, CanvasOps ops, int[] scaleDepth, int[] toolbarDepth,
                ObjectSlot toolbarBackground, Runnable afterSavesBeforeLeave) {
            Throwable first = null;
            first = runStep(first, () -> restorePaint(ops));
            first = runStep(first, () -> restoreCanvasSave(ops));
            first = runStep(first, () -> runAfterSaves(afterSavesBeforeLeave));
            first = runStep(first, () -> releaseScaleDepth(scaleDepth));
            first = runStep(first, () -> restoreToolbarBackground(toolbarBackground));
            first = runStep(first, () -> releaseToolbarDepth(toolbarDepth));
            first = runStep(first, () -> leaveOnce(stack));
            if (first != null) sneakyThrow(first);
        }

        private void restorePaint(CanvasOps ops) {
            Integer argb = paint.restoreOrNull();
            if (argb != null && paintTarget != null && ops != null) {
                ops.setPaintColor(paintTarget, argb.intValue());
            }
        }

        private void restoreCanvasSave(CanvasOps ops) {
            Integer id = canvasSave.closeOrNull();
            if (id != null && canvasSaveCanvas != null && ops != null) {
                ops.restoreToCount(canvasSaveCanvas, id);
            }
        }

        private void runAfterSaves(Runnable afterSavesBeforeLeave) {
            // Skip only when this call already left: a second after
            // must not paint overlay onto a parent frame. Calls that
            // never entered (e.g. g0.Y) still run the callback;
            // applyOverlayIfNeeded is itself one-shot per frame.
            if (afterSavesBeforeLeave != null && !(entered && leaveConsumed)) {
                afterSavesBeforeLeave.run();
            }
        }

        private void releaseScaleDepth(int[] scaleDepth) {
            if (scaleDepthHeld && !scaleDepthConsumed) {
                scaleDepthConsumed = true;
                if (scaleDepth != null && scaleDepth[0] > 0) scaleDepth[0]--;
            }
        }

        private void restoreToolbarBackground(ObjectSlot toolbarBackground) {
            if (toolbarBgHeld && !toolbarBgConsumed) {
                if (toolbarBackground != null) toolbarBackground.set(previousToolbar);
                toolbarBgConsumed = true;
            }
        }

        private void releaseToolbarDepth(int[] toolbarDepth) {
            if (toolbarDepthHeld && !toolbarDepthConsumed) {
                toolbarDepthConsumed = true;
                if (toolbarDepth != null && toolbarDepth[0] > 0) toolbarDepth[0]--;
            }
        }

        private void leaveOnce(Stack stack) {
            if (entered && !leaveConsumed) {
                leaveConsumed = true;
                if (stack != null) stack.leaveIfEntered(true);
            }
        }

        private interface Step { void run(); }

        private static Throwable runStep(Throwable first, Step step) {
            try { step.run(); }
            catch (Throwable t) { return first == null ? t : first; }
            return first;
        }

                @SuppressWarnings("unchecked")
        private static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
            throw (E) t;
        }
    }

    private static boolean isStringType(String t) {
        return "java.lang.String".equals(t) || "Ljava/lang/String;".equals(t) || "String".equals(t);
    }
    private static boolean isFloatType(String t) {
        return "float".equals(t) || "F".equals(t) || "java.lang.Float".equals(t);
    }
    private static boolean isPaintType(String t) {
        return "android.graphics.Paint".equals(t) || "Landroid/graphics/Paint;".equals(t) || "Paint".equals(t);
    }
}
