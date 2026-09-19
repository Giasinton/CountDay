package io.giasinton.countday;

import android.graphics.Bitmap;

/**
 * 双三次插值（Catmull-Rom）缩放。
 *
 * <p>Android 框架只暴露了双线性（{@code Bitmap.createScaledBitmap} / {@code Paint.setFilterBitmap}），
 * 缩小和放大都会发糊。这里自己做一遍分离式卷积：先水平、再垂直，每轴取 4 个抽样点按
 * Catmull-Rom 核加权，边缘处夹取并归一化权重。
 *
 * <p>颜色按<b>预乘 alpha</b> 插值，避免透明区域的颜色渗到边缘（否则抠图边缘会发灰/发黑）。
 */
final class BicubicScaler {

    private BicubicScaler() {
    }

    /** 缩放到精确的 dstW x dstH。尺寸相同则原样返回。 */
    static Bitmap scale(Bitmap src, int dstW, int dstH) {
        if (src == null || dstW <= 0 || dstH <= 0) {
            return src;
        }
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        if (srcW == dstW && srcH == dstH) {
            return src;
        }

        int[] srcPixels = new int[srcW * srcH];
        src.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH);

        // ---- 水平方向 ----
        float scaleX = (float) srcW / dstW;
        int[] tmp = new int[dstW * srcH];
        int[] idx = new int[4];
        float[] w = new float[4];
        for (int y = 0; y < srcH; y++) {
            int row = y * srcW;
            int outRow = y * dstW;
            for (int x = 0; x < dstW; x++) {
                float center = (x + 0.5f) * scaleX - 0.5f;
                int base = (int) Math.floor(center);
                float sum = 0f;
                for (int i = 0; i < 4; i++) {
                    int sx = clamp(base - 1 + i, 0, srcW - 1);
                    idx[i] = row + sx;
                    w[i] = cubic(center - (base - 1 + i));
                    sum += w[i];
                }
                if (sum != 0f) {
                    for (int i = 0; i < 4; i++) {
                        w[i] /= sum;
                    }
                }
                tmp[outRow + x] = interpolate(srcPixels, idx[0], idx[1], idx[2], idx[3],
                        w[0], w[1], w[2], w[3]);
            }
        }

        // ---- 垂直方向 ----
        float scaleY = (float) srcH / dstH;
        int[] out = new int[dstW * dstH];
        for (int y = 0; y < dstH; y++) {
            float center = (y + 0.5f) * scaleY - 0.5f;
            int base = (int) Math.floor(center);
            float sum = 0f;
            for (int i = 0; i < 4; i++) {
                int sy = clamp(base - 1 + i, 0, srcH - 1);
                idx[i] = sy * dstW;
                w[i] = cubic(center - (base - 1 + i));
                sum += w[i];
            }
            if (sum != 0f) {
                for (int i = 0; i < 4; i++) {
                    w[i] /= sum;
                }
            }
            int outRow = y * dstW;
            for (int x = 0; x < dstW; x++) {
                out[outRow + x] = interpolate(tmp, idx[0] + x, idx[1] + x, idx[2] + x, idx[3] + x,
                        w[0], w[1], w[2], w[3]);
            }
        }

        return Bitmap.createBitmap(out, dstW, dstH, Bitmap.Config.ARGB_8888);
    }

    /** Catmull-Rom 核（a = -0.5）。 */
    private static float cubic(float t) {
        t = Math.abs(t);
        if (t < 1f) {
            return 1.5f * t * t * t - 2.5f * t * t + 1f;
        }
        if (t < 2f) {
            return -0.5f * t * t * t + 2.5f * t * t - 4f * t + 2f;
        }
        return 0f;
    }

    /** 预乘 alpha 的加权插值。 */
    private static int interpolate(int[] pixels, int i0, int i1, int i2, int i3,
                                   float w0, float w1, float w2, float w3) {
        float alphaWeight = 0f;
        float r = 0f;
        float g = 0f;
        float b = 0f;

        int c = pixels[i0];
        int a = c >>> 24;
        float wa = w0 * a;
        alphaWeight += wa;
        r += ((c >> 16) & 0xFF) * wa;
        g += ((c >> 8) & 0xFF) * wa;
        b += (c & 0xFF) * wa;

        c = pixels[i1];
        a = c >>> 24;
        wa = w1 * a;
        alphaWeight += wa;
        r += ((c >> 16) & 0xFF) * wa;
        g += ((c >> 8) & 0xFF) * wa;
        b += (c & 0xFF) * wa;

        c = pixels[i2];
        a = c >>> 24;
        wa = w2 * a;
        alphaWeight += wa;
        r += ((c >> 16) & 0xFF) * wa;
        g += ((c >> 8) & 0xFF) * wa;
        b += (c & 0xFF) * wa;

        c = pixels[i3];
        a = c >>> 24;
        wa = w3 * a;
        alphaWeight += wa;
        r += ((c >> 16) & 0xFF) * wa;
        g += ((c >> 8) & 0xFF) * wa;
        b += (c & 0xFF) * wa;

        if (alphaWeight <= 0.0001f) {
            return 0;
        }
        int outA = clamp(Math.round(alphaWeight), 0, 255);
        int outR = clamp(Math.round(r / alphaWeight), 0, 255);
        int outG = clamp(Math.round(g / alphaWeight), 0, 255);
        int outB = clamp(Math.round(b / alphaWeight), 0, 255);
        return (outA << 24) | (outR << 16) | (outG << 8) | outB;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }
}
