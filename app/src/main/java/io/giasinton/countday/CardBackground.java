package io.giasinton.countday;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * 生成小组件的圆角卡片背景。
 *
 * <p>RemoteViews 没有"动态修改 shape 颜色"的能力，所以这里把「颜色 + 透明度 + 圆角 + 描边」
 * 直接画成一张小位图，由 ImageView 拉伸铺满。
 *
 * <p>位图按小组件的<b>长宽比</b>生成（最长边 {@link #MASK_SIZE}），这样拉满后圆角仍然是正圆
 * 而不是被拉成椭圆；圆角默认大小用 {@code @drawable/widget_card_default}，
 * 只有用了自定义颜色或自定义圆角时才走这里。
 */
final class CardBackground {

    private static final int MASK_SIZE = 96;
    private static final int MASK_MIN = 20;
    /** 卡片圆角，和 shape drawable 保持一致。 */
    private static final float RADIUS_DP = WidgetUi.DEFAULT_RADIUS_DP;

    private CardBackground() {
    }

    static Bitmap create(int argbColor, int widthDp, int heightDp) {
        return create(argbColor, widthDp, heightDp, true, RADIUS_DP);
    }

    /**
     * @param withStroke 是否画描边。放了图片时应该传 false —— 图片的透明边缘会把描边透出来，
     *                   看起来像"图片外面围了一圈暗线"。
     * @param radiusDp   圆角半径（dp）；0 表示直角，超过短边一半时自动变成胶囊形。
     */
    static Bitmap create(int argbColor, int widthDp, int heightDp, boolean withStroke, float radiusDp) {
        int wDp = Math.max(1, widthDp);
        int hDp = Math.max(1, heightDp);

        int maskW;
        int maskH;
        if (wDp >= hDp) {
            maskW = MASK_SIZE;
            maskH = Math.max(MASK_MIN, Math.round(MASK_SIZE * (float) hDp / wDp));
        } else {
            maskH = MASK_SIZE;
            maskW = Math.max(MASK_MIN, Math.round(MASK_SIZE * (float) wDp / hDp));
        }

        float scale = maskW / (float) wDp; // mask 像素 / dp
        float radius = Math.min(Math.max(0f, radiusDp) * scale, Math.min(maskW, maskH) / 2f);

        Bitmap bitmap = Bitmap.createBitmap(maskW, maskH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(argbColor);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(new RectF(0f, 0f, maskW, maskH), radius, radius, paint);

        if (withStroke) {
            // 1dp 描边；浅色卡片用深边、深色卡片用浅边，避免和壁纸糊在一起
            float strokeWidth = Math.max(0.9f, 1f * scale);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(strokeWidth);
            paint.setColor(isLight(argbColor) ? 0x1F000000 : 0x33FFFFFF);
            float inset = strokeWidth / 2f;
            canvas.drawRoundRect(
                    new RectF(inset, inset, maskW - inset, maskH - inset),
                    radius, radius, paint);
        }

        return bitmap;
    }

    /** 用感知亮度判断底色深浅（忽略 alpha）。 */
    static boolean isLight(int argbColor) {
        int r = Color.red(argbColor);
        int g = Color.green(argbColor);
        int b = Color.blue(argbColor);
        return (0.299 * r + 0.587 * g + 0.114 * b) > 160;
    }
}
