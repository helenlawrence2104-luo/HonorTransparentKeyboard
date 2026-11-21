package local.honor.transparentkeyboard;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * Same-window liquid-glass optics: rounded-rect SDF edge refraction, slight
 * chromatic split, clear center, glyphs drawn afterwards by the caller.
 *
 * The SDF / circle-map / gradient-offset lens is a CPU port of the model in
 * Kyant0/AndroidLiquidGlass (Copyright 2025 Kyant, Apache License 2.0).
 * Default amounts, over-light behaviour (halved displacement, extra frost,
 * a dark veil — not a hairline stroke) and “keep typography unfiltered”
 * follow the documented behaviour of liquid-glass-react
 * (Copyright 2025 MAX ROVENSKY, MIT). This file does not embed that
 * project’s SVG filters, CSS, or displacement JPEGs.
 *
 * This class never captures a display. The caller must pass a backdrop
 * bitmap already obtained by {@link SameWindowSampler} (preview / public
 * test pattern only). One shared backdrop is used for every tile; do not
 * screenshot each key or each frame.
 *
 * Synthetic CPU output is a prototype. It is not a device IME composite
 * and must not be shipped as “Apple Liquid Glass on the keyboard”.
 */
public final class LiquidGlassOptics {
    public static final class Params {
        public float refractionHeight = 16f;
        public float refractionAmount = 24f;
        public float chroma = 0.28f;
        public float preBlur = 5f;
        public float saturate = 1.40f;
        public boolean autoOverLight = true;
        public float overLightLuma = 0.78f;
        public float veil = 0f;
        public float highlight = 0.22f;

        public Params copy() {
            Params p = new Params();
            p.refractionHeight = refractionHeight;
            p.refractionAmount = refractionAmount;
            p.chroma = chroma;
            p.preBlur = preBlur;
            p.saturate = saturate;
            p.autoOverLight = autoOverLight;
            p.overLightLuma = overLightLuma;
            p.veil = veil;
            p.highlight = highlight;
            return p;
        }
    }

    public static final class Tile {
        public final float x, y, w, h, radius;
        public Tile(float x, float y, float w, float h, float radius) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.radius = radius;
        }
        public Tile(RectF r, float radius) {
            this(r.left, r.top, r.width(), r.height(), radius);
        }
    }

    public static Params defaults() { return new Params(); }

    /** Translate the reference lens dimensions into this renderer's bitmap coordinates.
     * Match blur and other controls so the comparison isolates lens geometry. */
    static Params comparison(boolean strong, float density) {
        Params params = defaults();
        params.refractionHeight = (strong ? 12f : 14f) * density;
        params.refractionAmount = (strong ? 24f : 9f) * density;
        params.preBlur = 2f * density;
        params.saturate = 1f;
        params.chroma = 0f;
        params.autoOverLight = false;
        params.highlight = 0f;
        return params;
    }

    public static Params adaptOverLight(Params in, float luma) {
        if (in == null) in = defaults();
        if (!in.autoOverLight || luma < in.overLightLuma) return in;
        Params p = in.copy();
        p.refractionAmount = in.refractionAmount * 0.5f;
        p.preBlur = in.preBlur + 4f;
        p.saturate = Math.min(in.saturate, 1.15f);
        p.veil = 0.16f;
        p.highlight = 0.28f;
        p.chroma = in.chroma * 0.7f;
        return p;
    }

    public static float lumaOf(Bitmap bmp, int left, int top, int right, int bottom) {
        if (bmp == null) return 0f;
        left = Math.max(0, left); top = Math.max(0, top);
        right = Math.min(bmp.getWidth(), right);
        bottom = Math.min(bmp.getHeight(), bottom);
        if (right <= left || bottom <= top) return 0f;
        int stepX = Math.max(1, (right - left) / 64);
        int stepY = Math.max(1, (bottom - top) / 64);
        double acc = 0;
        int n = 0;
        for (int y = top; y < bottom; y += stepY) {
            for (int x = left; x < right; x += stepX) {
                int c = bmp.getPixel(x, y);
                acc += 0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c);
                n++;
            }
        }
        return n == 0 ? 0f : (float) (acc / (n * 255.0));
    }

    /**
     * Renders glass tiles into a copy of {@code backdrop}. Labels must be
     * drawn by the caller afterwards so typography stays sharp.
     */
    public static Bitmap render(Bitmap backdrop, Tile[] tiles, Params params) {
        if (backdrop == null) return null;
        if (tiles == null || tiles.length == 0) return backdrop.copy(Bitmap.Config.ARGB_8888, false);
        int w = backdrop.getWidth();
        int h = backdrop.getHeight();
        if (params == null) params = defaults();

        int bandTop = h, bandBot = 0;
        for (Tile t : tiles) {
            bandTop = Math.min(bandTop, (int) Math.floor(t.y));
            bandBot = Math.max(bandBot, (int) Math.ceil(t.y + t.h));
        }
        Params p = adaptOverLight(params, lumaOf(backdrop, 0, bandTop, w, bandBot));

        Bitmap frost = prefilter(backdrop, p);
        Bitmap out = backdrop.copy(Bitmap.Config.ARGB_8888, true);
        int[] src = new int[w * h];
        int[] dst = new int[w * h];
        frost.getPixels(src, 0, w, 0, 0, w, h);
        out.getPixels(dst, 0, w, 0, 0, w, h);

        for (Tile t : tiles) {
            renderTile(src, dst, w, h, t, p);
        }
        out.setPixels(dst, 0, w, 0, 0, w, h);
        return out;
    }

    private static void renderTile(int[] src, int[] dst, int w, int h, Tile t, Params p) {
        int x0 = Math.max(0, (int) Math.floor(t.x) - 1);
        int y0 = Math.max(0, (int) Math.floor(t.y) - 1);
        int x1 = Math.min(w, (int) Math.ceil(t.x + t.w) + 1);
        int y1 = Math.min(h, (int) Math.ceil(t.y + t.h) + 1);
        float cx = t.x + t.w * 0.5f;
        float cy = t.y + t.h * 0.5f;
        float hx = t.w * 0.5f;
        float hy = t.h * 0.5f;
        float radius = Math.max(1f, Math.min(t.radius, Math.min(hx, hy)));
        float gradR = Math.min(radius * 1.5f, Math.min(hx, hy));
        float height = Math.max(1f, p.refractionHeight);
        float chroma = p.chroma;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                float px = x + 0.5f - cx;
                float py = y + 0.5f - cy;
                float sd = sdRoundedRect(px, py, hx, hy, radius);
                if (sd > 0f) continue;
                float d = 0f;
                float gx = 0f, gy = 0f;
                if (-sd < height) {
                    float[] g = new float[2];
                    gradSd(px, py, hx, hy, gradR, g);
                    gx = g[0];
                    gy = g[1];
                    float tt = 1f - (-sd) / height;
                    d = circleMap(tt) * p.refractionAmount;
                }
                int rgb = sample(src, w, h, x + d * gx, y + d * gy);
                if (chroma > 0f && d != 0f) {
                    int rCh = sample(src, w, h, x + d * gx * (1f + 0.35f * chroma), y + d * gy * (1f + 0.35f * chroma));
                    int bCh = sample(src, w, h, x + d * gx * (1f - 0.35f * chroma), y + d * gy * (1f - 0.35f * chroma));
                    rgb = Color.rgb(Color.red(rCh), Color.green(rgb), Color.blue(bCh));
                }
                if (p.highlight > 0f) {
                    float ny = (py / Math.max(hy, 1f) + 1f) * 0.5f;
                    float spec = p.highlight * (float) Math.pow(Math.max(0f, 1f - ny), 1.4);
                    float edge = 1f - clamp01((sd + 6f) / 8f);
                    spec *= edge;
                    rgb = Color.rgb(
                            clampCh(Color.red(rgb) + spec * 255f),
                            clampCh(Color.green(rgb) + spec * 255f),
                            clampCh(Color.blue(rgb) + spec * 255f));
                }
                dst[y * w + x] = 0xFF000000 | (rgb & 0x00FFFFFF);
            }
        }
    }

    private static Bitmap prefilter(Bitmap src, Params p) {
        Bitmap work = src.copy(Bitmap.Config.ARGB_8888, true);
        if (p.preBlur > 0.5f) {
            // Box blur approximates the cheap frost; not sold as the lens.
            boxBlur(work, Math.max(1, Math.round(p.preBlur)));
        }
        if (Math.abs(p.saturate - 1f) > 0.01f || p.veil > 0f) {
            int w = work.getWidth(), h = work.getHeight();
            int[] pix = new int[w * h];
            work.getPixels(pix, 0, w, 0, 0, w, h);
            float sat = p.saturate;
            float veil = clamp01(p.veil);
            for (int i = 0; i < pix.length; i++) {
                int c = pix[i];
                float r = Color.red(c), g = Color.green(c), b = Color.blue(c);
                float y = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                r = clampCh(y + (r - y) * sat);
                g = clampCh(y + (g - y) * sat);
                b = clampCh(y + (b - y) * sat);
                if (veil > 0f) {
                    r *= (1f - veil);
                    g *= (1f - veil);
                    b *= (1f - veil);
                }
                pix[i] = Color.rgb(Math.round(r), Math.round(g), Math.round(b));
            }
            work.setPixels(pix, 0, w, 0, 0, w, h);
        }
        return work;
    }

    private static void boxBlur(Bitmap bmp, int radius) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] a = new int[w * h];
        int[] b = new int[w * h];
        bmp.getPixels(a, 0, w, 0, 0, w, h);
        int pass = 2;
        for (int p = 0; p < pass; p++) {
            boxBlurHor(a, b, w, h, radius);
            boxBlurVer(b, a, w, h, radius);
        }
        bmp.setPixels(a, 0, w, 0, 0, w, h);
    }

    private static void boxBlurHor(int[] src, int[] dst, int w, int h, int r) {
        int wr = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int rs = 0, gs = 0, bs = 0;
            int row = y * w;
            for (int x = -r; x <= r; x++) {
                int c = src[row + clamp(x, 0, w - 1)];
                rs += Color.red(c); gs += Color.green(c); bs += Color.blue(c);
            }
            for (int x = 0; x < w; x++) {
                dst[row + x] = Color.rgb(rs / wr, gs / wr, bs / wr);
                int out = src[row + clamp(x - r, 0, w - 1)];
                int in = src[row + clamp(x + r + 1, 0, w - 1)];
                rs += Color.red(in) - Color.red(out);
                gs += Color.green(in) - Color.green(out);
                bs += Color.blue(in) - Color.blue(out);
            }
        }
    }

    private static void boxBlurVer(int[] src, int[] dst, int w, int h, int r) {
        int wr = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            int rs = 0, gs = 0, bs = 0;
            for (int y = -r; y <= r; y++) {
                int c = src[clamp(y, 0, h - 1) * w + x];
                rs += Color.red(c); gs += Color.green(c); bs += Color.blue(c);
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = Color.rgb(rs / wr, gs / wr, bs / wr);
                int out = src[clamp(y - r, 0, h - 1) * w + x];
                int in = src[clamp(y + r + 1, 0, h - 1) * w + x];
                rs += Color.red(in) - Color.red(out);
                gs += Color.green(in) - Color.green(out);
                bs += Color.blue(in) - Color.blue(out);
            }
        }
    }

    private static int sample(int[] src, int w, int h, float x, float y) {
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x > w - 1.001f) x = w - 1.001f;
        if (y > h - 1.001f) y = h - 1.001f;
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = Math.min(x0 + 1, w - 1);
        int y1 = Math.min(y0 + 1, h - 1);
        float fx = x - x0, fy = y - y0;
        return lerpColor(
                lerpColor(src[y0 * w + x0], src[y0 * w + x1], fx),
                lerpColor(src[y1 * w + x0], src[y1 * w + x1], fx),
                fy);
    }

    private static int lerpColor(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private static float sdRoundedRect(float x, float y, float hx, float hy, float radius) {
        float cx = Math.abs(x) - (hx - radius);
        float cy = Math.abs(y) - (hy - radius);
        float outside = (float) Math.hypot(Math.max(cx, 0f), Math.max(cy, 0f)) - radius;
        float inside = Math.min(Math.max(cx, cy), 0f);
        return outside + inside;
    }

    private static void gradSd(float x, float y, float hx, float hy, float radius, float[] out) {
        float cx = Math.abs(x) - (hx - radius);
        float cy = Math.abs(y) - (hy - radius);
        float gx, gy;
        if (cx >= 0f || cy >= 0f) {
            float mx = Math.max(cx, 0f), my = Math.max(cy, 0f);
            float n = (float) Math.hypot(mx, my) + 1e-8f;
            gx = Math.signum(x) * mx / n;
            gy = Math.signum(y) * my / n;
        } else {
            gx = Math.signum(x) * (cy <= cx ? 1f : 0f);
            gy = Math.signum(y) * (cy > cx ? 1f : 0f);
        }
        float n = (float) Math.hypot(gx, gy) + 1e-8f;
        out[0] = gx / n;
        out[1] = gy / n;
    }

    private static float circleMap(float t) {
        t = clamp01(t);
        return 1f - (float) Math.sqrt(Math.max(0f, 1f - t * t));
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : Math.min(v, hi); }
    private static float clamp01(float v) { return v < 0f ? 0f : Math.min(v, 1f); }
    private static int clampCh(float v) {
        int i = Math.round(v);
        return i < 0 ? 0 : Math.min(i, 255);
    }

    /**
     * Optional AGSL (API 33+) for the integrator. Same edge-band model as
     * the CPU path; bind a {@code uniform shader content} from the backdrop
     * bitmap, not from a cross-app capture. Center is a direct sample.
     */
    public static final String AGSL_LENS = ""
            + "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float2 offset;\n"
            + "uniform float radius;\n"
            + "uniform float refractionHeight;\n"
            + "uniform float refractionAmount;\n"
            + "uniform float chroma;\n"
            + "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
            + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
            + "    float outside = length(max(cornerCoord, 0.0)) - radius;\n"
            + "    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n"
            + "    return outside + inside;\n"
            + "}\n"
            + "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
            + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
            + "    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {\n"
            + "        return sign(coord) * normalize(max(cornerCoord, 0.0));\n"
            + "    } else {\n"
            + "        float gradX = step(cornerCoord.y, cornerCoord.x);\n"
            + "        return sign(coord) * float2(gradX, 1.0 - gradX);\n"
            + "    }\n"
            + "}\n"
            + "float circleMap(float x) { x = clamp(x, 0.0, 1.0); return 1.0 - sqrt(1.0 - x * x); }\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 halfSize = size * 0.5;\n"
            + "    float2 centered = (coord + offset) - halfSize;\n"
            + "    float sd = sdRoundedRect(centered, halfSize, radius);\n"
            + "    if (sd > 0.0) return half4(0.0);\n"
            + "    if (-sd >= refractionHeight) return content.eval(coord);\n"
            + "    sd = min(sd, 0.0);\n"
            + "    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;\n"
            + "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
            + "    float2 grad = normalize(gradSdRoundedRect(centered, halfSize, gradRadius));\n"
            + "    float2 mid = coord + d * grad;\n"
            + "    half4 g = content.eval(mid);\n"
            + "    if (chroma <= 0.001) return g;\n"
            + "    half4 r = content.eval(mid + d * grad * 0.35 * chroma);\n"
            + "    half4 b = content.eval(mid - d * grad * 0.35 * chroma);\n"
            + "    return half4(r.r, g.g, b.b, 1.0);\n"
            + "}\n";

    /** Utility for a one-off debug plate; not required at runtime. */
    public static void stampLabel(Canvas c, RectF tile, String text, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(Math.max(18f, tile.height() * 0.36f));
        float cy = tile.centerY() - (p.descent() + p.ascent()) / 2f;
        c.drawText(text, tile.centerX(), cy, p);
    }

    private LiquidGlassOptics() {}
}
