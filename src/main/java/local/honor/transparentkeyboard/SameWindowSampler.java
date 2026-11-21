package local.honor.transparentkeyboard;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * Samples pixels that already belong to a View or Window in this process.
 *
 * Allowed: software {@link View#draw(Canvas)} or {@link PixelCopy} of the
 * same Activity window, for this module’s Preview / public test patterns.
 * Refused: {@code FLAG_SECURE} windows, MediaProjection, Accessibility
 * screenshots, display-wide capture, other-app content, and bypassing
 * secure flags. This class does not import or call those APIs.
 *
 * Cross-window IME backdrop (the app sitting under the keyboard) is not
 * available through these official methods. A successful copy of the IME
 * or Preview window is not a copy of WeChat, a call, or a password field
 * behind it.
 */
public final class SameWindowSampler {
    public static final String OK = "OK";
    public static final String REFUSED_NULL = "REFUSED_NULL";
    public static final String REFUSED_SECURE = "REFUSED_SECURE";
    public static final String REFUSED_SIZE = "REFUSED_SIZE";
    public static final String REFUSED_NO_WINDOW = "REFUSED_NO_WINDOW";
    public static final String REFUSED_POLICY = "REFUSED_POLICY";
    public static final String PIXEL_COPY_FAILED = "PIXEL_COPY_FAILED";

    public interface Callback {
        void onDone(Result result);
    }

    public static final class Result {
        public final boolean ok;
        public final String status;
        public final Bitmap bitmap;
        public final String detail;

        Result(boolean ok, String status, Bitmap bitmap, String detail) {
            this.ok = ok;
            this.status = status;
            this.bitmap = bitmap;
            this.detail = detail;
        }

        public static Result fail(String status, String detail) {
            return new Result(false, status, null, detail);
        }
    }

    public static boolean isFlagSecure(Window window) {
        if (window == null) return false;
        WindowManager.LayoutParams lp = window.getAttributes();
        return lp != null && (lp.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
    }

    public static Window windowOf(View view) {
        if (view == null) return null;
        Context c = view.getContext();
        int hops = 0;
        while (c instanceof ContextWrapper && hops++ < 8) {
            if (c instanceof Activity) return ((Activity) c).getWindow();
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    /**
     * Synchronous copy of a View’s own drawing tree. This does not include
     * other windows, system window blur, or pixels the view never drew.
     */
    public static Result drawViewSoftware(View view) {
        if (view == null) return Result.fail(REFUSED_NULL, "view is null");
        Window window = windowOf(view);
        if (isFlagSecure(window)) {
            return Result.fail(REFUSED_SECURE, "FLAG_SECURE: will not copy");
        }
        int w = view.getWidth();
        int h = view.getHeight();
        if (w <= 0 || h <= 0) {
            return Result.fail(REFUSED_SIZE, "view has no size yet");
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        view.draw(canvas);
        return new Result(true, OK, bmp, "software View.draw, same-window only");
    }

    /**
     * PixelCopy of the same Activity window. On API 26+. Still only that
     * window’s buffer; transparent IME regions will not reveal the app
     * underneath. Safe patterns only; never use this on a lock/password UI.
     */
    public static void copyWindowAsync(Window window, Rect src, Callback cb) {
        if (cb == null) return;
        if (window == null) {
            cb.onDone(Result.fail(REFUSED_NO_WINDOW, "no window"));
            return;
        }
        if (isFlagSecure(window)) {
            cb.onDone(Result.fail(REFUSED_SECURE, "FLAG_SECURE: will not copy"));
            return;
        }
        if (Build.VERSION.SDK_INT < 26) {
            cb.onDone(Result.fail(REFUSED_POLICY, "PixelCopy requires API 26"));
            return;
        }
        View decor = window.getDecorView();
        if (decor.getWidth() <= 0 || decor.getHeight() <= 0) {
            cb.onDone(Result.fail(REFUSED_SIZE, "window has no size yet"));
            return;
        }
        Rect r = src != null ? new Rect(src) : new Rect(0, 0, decor.getWidth(), decor.getHeight());
        if (r.width() <= 0 || r.height() <= 0) {
            cb.onDone(Result.fail(REFUSED_SIZE, "empty copy rect"));
            return;
        }
        final Bitmap bmp = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(window, r, bmp, new PixelCopy.OnPixelCopyFinishedListener() {
                @Override public void onPixelCopyFinished(int result) {
                    if (result == PixelCopy.SUCCESS) {
                        cb.onDone(new Result(true, OK, bmp, "PixelCopy same window"));
                    } else {
                        bmp.recycle();
                        cb.onDone(Result.fail(PIXEL_COPY_FAILED, "PixelCopy result=" + result));
                    }
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            bmp.recycle();
            cb.onDone(Result.fail(PIXEL_COPY_FAILED, String.valueOf(t)));
        }
    }

    public static String capabilityNote() {
        return "Official APIs used: View.draw, PixelCopy of the same Window. "
                + "Official cross-window blur (Window.setBackgroundBlurRadius / "
                + "setBlurBehindRadius) frosts via the compositor and does not "
                + "hand pixels to an app shader. RenderEffect processes a View’s "
                + "own content. Hidden APIs, MediaProjection and Accessibility "
                + "capture are not used. HarmonyOS ArkUI backdropBlur is not "
                + "applicable to this Android Xposed module.";
    }

    private SameWindowSampler() {}
}
