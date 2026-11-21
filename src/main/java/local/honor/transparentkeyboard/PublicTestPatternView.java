package local.honor.transparentkeyboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Built-in public test artwork for same-window optics preview only.
 * Not user content, not a capture of another app, and not drawn into the IME.
 */
final class PublicTestPatternView extends View {
    static final int MODE_STRIPES = 0;
    static final int MODE_WHITE_FORM = 1;
    private int mode = MODE_STRIPES;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    PublicTestPatternView(Context context) {
        super(context);
        setContentDescription("公开测试图案");
    }

    int mode() { return mode; }

    void setMode(int mode) {
        this.mode = mode == MODE_WHITE_FORM ? MODE_WHITE_FORM : MODE_STRIPES;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (mode == MODE_WHITE_FORM) drawWhiteForm(canvas, w, h);
        else drawStripes(canvas, w, h);
    }

    private void drawStripes(Canvas canvas, int w, int h) {
        int[] colors = new int[]{
                Color.rgb(220, 40, 60), Color.rgb(240, 160, 30), Color.rgb(250, 220, 50),
                Color.rgb(40, 180, 90), Color.rgb(40, 120, 210), Color.rgb(120, 70, 200)
        };
        float stripe = Math.max(18f, w / 12f);
        for (int i = 0, x = 0; x < w; i++, x += (int) stripe) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(colors[i % colors.length]);
            canvas.drawRect(x, 0, x + stripe + 1, h, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(3f, h / 40f));
        paint.setColor(Color.rgb(20, 20, 24));
        for (int i = -2; i < 8; i++) {
            float y = h * 0.15f + i * h * 0.18f;
            canvas.drawLine(0, y, w, y + h * 0.22f, paint);
        }
        stamp(canvas, w, h, "公开测试图案", Color.WHITE, 0xCC000000);
    }

    private void drawWhiteForm(Canvas canvas, int w, int h) {
        canvas.drawColor(Color.rgb(252, 252, 253));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setColor(Color.rgb(180, 186, 196));
        float pad = w * 0.06f;
        float row = (h - pad * 2) / 4f;
        for (int i = 0; i < 4; i++) {
            RectF box = new RectF(pad, pad + i * row + 6, w - pad, pad + (i + 1) * row - 6);
            canvas.drawRoundRect(box, 10, 10, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(60, 66, 78));
            paint.setTextSize(Math.max(22f, row * 0.32f));
            paint.setTextAlign(Paint.Align.LEFT);
            String[] lines = { "公开测试 示例表单", "姓名 ________", "日期 ________", "备注 条纹对照区" };
            canvas.drawText(lines[i], box.left + 16, box.centerY() - (paint.descent() + paint.ascent()) / 2f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(Color.rgb(180, 186, 196));
        }
        // Dark diagonal marks so refraction stays visible on a light page.
        paint.setStrokeWidth(3f);
        paint.setColor(Color.rgb(40, 90, 200));
        for (int i = 0; i < 6; i++) {
            float x = pad + i * (w - 2 * pad) / 5f;
            canvas.drawLine(x, pad, x + (w - 2 * pad) * 0.2f, h - pad, paint);
        }
        stamp(canvas, w, h, "公开测试 · 白底", Color.rgb(20, 24, 32), 0x00000000);
    }

    private void stamp(Canvas canvas, int w, int h, String text, int color, int plate) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(Math.max(28f, h * 0.18f));
        if ((plate >>> 24) != 0) {
            paint.setColor(plate);
            float tw = paint.measureText(text);
            canvas.drawRoundRect(w / 2f - tw / 2f - 16, h * 0.38f, w / 2f + tw / 2f + 16, h * 0.62f, 12, 12, paint);
        }
        paint.setColor(color);
        canvas.drawText(text, w / 2f, h * 0.5f - (paint.descent() + paint.ascent()) / 2f, paint);
    }
}
