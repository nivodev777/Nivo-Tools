package com.nivodev.nivotools.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

/**
 * Blur helpers that avoid deprecated RenderScript so the project keeps
 * compiling on high compileSdk versions used by Code On The Go.
 */
public final class BlurUtils {

    private BlurUtils() {
    }

    /** Renders the given view into a small bitmap (view scaled down by {@code downsample}). */
    public static Bitmap captureView(View view, float downsample) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return null;
        }
        float factor = Math.max(1f, downsample);
        int w = Math.max(1, (int) (view.getWidth() / factor));
        int h = Math.max(1, (int) (view.getHeight() / factor));
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale((float) w / view.getWidth(), (float) h / view.getHeight());
        view.draw(canvas);
        return bitmap;
    }

    /**
     * Approximates a gaussian blur with three box blur passes.
     * Operates in-place and returns the same bitmap instance.
     *
     * Scratch arrays are reused on the UI thread (the glass pipeline is the
     * only hot caller) so repeated frames stop allocating ~9 arrays each time.
     * Background threads always get private arrays.
     */
    public static Bitmap boxBlur(Bitmap bitmap, int radius) {
        if (bitmap == null || radius < 1) {
            return bitmap;
        }
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int size = w * h;
        boolean onMain = android.os.Looper.getMainLooper().getThread()
                .equals(Thread.currentThread());
        boolean reuse = onMain && sPix != null && sPix.length == size;

        if (reuse) {
            pixels = sPix;
            a = sA;
            r = sR;
            g = sG;
            b = sB;
            ta = sTa;
            tr = sTr;
            tg = sTg;
            tb = sTb;
        } else {
            pixels = new int[size];
            a = new int[size];
            r = new int[size];
            g = new int[size];
            b = new int[size];
            ta = new int[size];
            tr = new int[size];
            tg = new int[size];
            tb = new int[size];
            if (onMain) {
                sPix = pixels;
                sA = a;
                sR = r;
                sG = g;
                sB = b;
                sTa = ta;
                sTr = tr;
                sTg = tg;
                sTb = tb;
            }
        }
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);

        for (int i = 0; i < size; i++) {
            int p = pixels[i];
            a[i] = (p >>> 24) & 0xFF;
            r[i] = (p >> 16) & 0xFF;
            g[i] = (p >> 8) & 0xFF;
            b[i] = p & 0xFF;
        }

        for (int pass = 0; pass < 3; pass++) {
            boxBlurH(a, ta, w, h, radius);
            boxBlurH(r, tr, w, h, radius);
            boxBlurH(g, tg, w, h, radius);
            boxBlurH(b, tb, w, h, radius);

            boxBlurT(ta, a, w, h, radius);
            boxBlurT(tr, r, w, h, radius);
            boxBlurT(tg, g, w, h, radius);
            boxBlurT(tb, b, w, h, radius);
        }

        for (int i = 0; i < size; i++) {
            pixels[i] = (a[i] << 24) | (r[i] << 16) | (g[i] << 8) | b[i];
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
        return bitmap;
    }

    private static int[] sPix, sA, sR, sG, sB, sTa, sTr, sTg, sTb;
    private static int[] pixels, a, r, g, b, ta, tr, tg, tb;

    private static void boxBlurH(int[] src, int[] dst, int w, int h, int radius) {
        float iarr = 1f / (radius + radius + 1);
        for (int y = 0; y < h; y++) {
            int ti = y * w;
            int li = ti;
            int ri = ti + radius;
            int fv = src[ti];
            int lv = src[ti + w - 1];
            int val = (radius + 1) * fv;
            for (int j = 0; j < radius; j++) {
                val += src[ti + j];
            }
            for (int j = 0; j <= radius; j++) {
                val += src[ri++] - fv;
                dst[ti++] = (int) (val * iarr);
            }
            for (int j = radius + 1; j < w - radius; j++) {
                val += src[ri++] - src[li++];
                dst[ti++] = (int) (val * iarr);
            }
            for (int j = w - radius; j < w; j++) {
                val += lv - src[li++];
                dst[ti++] = (int) (val * iarr);
            }
        }
    }

    private static void boxBlurT(int[] src, int[] dst, int w, int h, int radius) {
        float iarr = 1f / (radius + radius + 1);
        for (int x = 0; x < w; x++) {
            int ti = x;
            int li = ti;
            int ri = ti + radius * w;
            int fv = src[ti];
            int lv = ti + w * (h - 1);
            int val = (radius + 1) * fv;
            for (int j = 0; j < radius; j++) {
                val += src[ti + j * w];
            }
            for (int j = 0; j <= radius; j++) {
                val += src[ri] - fv;
                dst[ti] = (int) (val * iarr);
                ri += w;
                ti += w;
            }
            for (int j = radius + 1; j < h - radius; j++) {
                val += src[ri] - src[li];
                dst[ti] = (int) (val * iarr);
                li += w;
                ri += w;
                ti += w;
            }
            for (int j = h - radius; j < h; j++) {
                val += src[lv] - src[li];
                dst[ti] = (int) (val * iarr);
                li += w;
                ti += w;
            }
        }
    }
}
