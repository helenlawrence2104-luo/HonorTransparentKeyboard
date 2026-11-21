package local.honor.transparentkeyboard;

import android.inputmethodservice.InputMethodService;
import android.graphics.RectF;
import android.os.Build;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorBoundsInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import java.util.function.BooleanSupplier;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Keeps page content behind the keyboard when the focused editor is safely above it. */
final class KeyboardInsets {
    static final String TEST_OPTION = "local.honor.transparentkeyboard.INSETS_TEST.";
    private static String lastReport;
    private static EditorInfo trackedEditor;
    private static InputConnection trackedConnection;
    private static final RectF editorBounds = new RectF();
    private static final RectF incomingBounds = new RectF();
    private static final int[] decorPosition = new int[2];
    private static boolean overlay;
    private static boolean blockedForEditor;

    private KeyboardInsets() {}

    private static void reset() {
        trackedEditor = null;
        trackedConnection = null;
        editorBounds.setEmpty();
        overlay = false;
        blockedForEditor = false;
        lastReport = null;
    }

    static void install(ClassLoader loader, BooleanSupplier glassEnabled) {
        Class<?> service = XposedHelpers.findClass("com.baidu.input_honor.ImeService", loader);
        XposedHelpers.findAndHookMethod(service,
                "onComputeInsets", InputMethodService.Insets.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.hasThrowable()) return;
                        InputMethodService ime = (InputMethodService) param.thisObject;
                        EditorInfo editor = ime.getCurrentInputEditorInfo();
                        if (editor == null) return;
                        String options = editor.privateImeOptions;
                        boolean publicTest = "local.honor.transparentkeyboard".equals(editor.packageName)
                                && options != null && options.startsWith(TEST_OPTION);
                        if (ime.isFullscreenMode()) return;
                        View decor = decorOf(ime);
                        if (decor == null) return;
                        int height = decor.getHeight();
                        InputMethodService.Insets insets = (InputMethodService.Insets) param.args[0];
                        if (height <= 0 || insets.touchableInsets != InputMethodService.Insets.TOUCHABLE_INSETS_REGION
                                || insets.touchableRegion.isEmpty()) return;
                        String mode;
                        if (publicTest) {
                            mode = options.substring(TEST_OPTION.length());
                        } else {
                            if (!glassEnabled.getAsBoolean() || trackedEditor != editor) return;
                            boolean safe = safeToOverlay(decor, insets.contentTopInsets);
                            if (overlay && !safe) {
                                blockedForEditor = true;
                            }
                            overlay = !blockedForEditor && safe;
                            mode = overlay ? "editor-overlay" : "reserved";
                        }
                        int original = insets.contentTopInsets;
                        int visible = insets.visibleTopInsets;
                        if ("content".equals(mode) || "both".equals(mode) || "editor-overlay".equals(mode)) {
                            insets.contentTopInsets = height;
                        }
                        if ("both".equals(mode) || "editor-overlay".equals(mode)) insets.visibleTopInsets = height;
                        String report = mode + " content=" + original + "->" + insets.contentTopInsets
                                + " visible=" + visible + "->" + insets.visibleTopInsets
                                + " touch=" + insets.touchableRegion.getBounds().toShortString();
                        if (!report.equals(lastReport)) {
                            lastReport = report;
                            XposedBridge.log("HonorGlass: keyboard insets " + report);
                        }
                    }
                });
        Class<?> lifecycle = XposedHelpers.findClass("com.baidu.input.modular.ObservableImeService", loader);
        XposedHelpers.findAndHookMethod(lifecycle, "onStartInputView",
                EditorInfo.class, boolean.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        reset();
                        if (param.hasThrowable() || !glassEnabled.getAsBoolean()) return;
                        InputMethodService ime = (InputMethodService) param.thisObject;
                        EditorInfo editor = ime.getCurrentInputEditorInfo();
                        if (!eligible(editor) || ime.isFullscreenMode()) return;
                        View decor = decorOf(ime);
                        if (decor == null || Build.VERSION.SDK_INT < 33) return;
                        trackedEditor = editor;
                        trackedConnection = ime.getCurrentInputConnection();
                        InputConnection connection = trackedConnection;
                        if (connection == null) return;
                        decor.post(() -> {
                            if (trackedEditor != editor || ime.getCurrentInputConnection() != connection) return;
                            try {
                                connection.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE
                                                | InputConnection.CURSOR_UPDATE_MONITOR,
                                        InputConnection.CURSOR_UPDATE_FILTER_EDITOR_BOUNDS);
                            } catch (RuntimeException error) {
                                XposedBridge.log("HonorGlass: editor bounds unavailable");
                            }
                        });
                    }
                });
        XposedHelpers.findAndHookMethod(lifecycle, "onWindowHidden", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) { reset(); }
        });
        XposedHelpers.findAndHookMethod(service, "onUpdateCursorAnchorInfo",
                CursorAnchorInfo.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        InputMethodService ime = (InputMethodService) param.thisObject;
                        if (trackedEditor == null || trackedEditor != ime.getCurrentInputEditorInfo()
                                || trackedConnection != ime.getCurrentInputConnection()) return;
                        if (!updateBounds((CursorAnchorInfo) param.args[0])) return;
                        View decor = decorOf(ime);
                        if (decor != null) decor.requestLayout();
                    }
                });
    }

    private static boolean eligible(EditorInfo editor) {
        if (editor == null || (editor.inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) {
            return false;
        }
        int variation = editor.inputType & InputType.TYPE_MASK_VARIATION;
        return variation != InputType.TYPE_TEXT_VARIATION_PASSWORD
                && variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                && variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
    }

    private static View decorOf(InputMethodService ime) {
        return ime.getWindow() == null || ime.getWindow().getWindow() == null
                ? null : ime.getWindow().getWindow().getDecorView();
    }

    private static boolean updateBounds(CursorAnchorInfo info) {
        incomingBounds.setEmpty();
        if (info != null && Build.VERSION.SDK_INT >= 33) {
            EditorBoundsInfo boundsInfo = info.getEditorBoundsInfo();
            RectF bounds = boundsInfo == null ? null : boundsInfo.getEditorBounds();
            if (bounds != null && !bounds.isEmpty()) {
                incomingBounds.set(bounds);
                info.getMatrix().mapRect(incomingBounds);
            }
        }
        if (incomingBounds.equals(editorBounds)) return false;
        editorBounds.set(incomingBounds);
        return true;
    }

    private static boolean safeToOverlay(View decor, int keyboardTop) {
        if (editorBounds.isEmpty() || keyboardTop <= 0 || keyboardTop >= decor.getHeight()) return false;
        decor.getLocationOnScreen(decorPosition);
        float density = decor.getResources().getDisplayMetrics().density;
        float editorLimit = Math.min(decorPosition[1] + keyboardTop - 112 * density,
                decorPosition[1] + decor.getHeight() * 0.42f);
        return editorBounds.top >= 0 && editorBounds.bottom <= editorLimit;
    }
}
