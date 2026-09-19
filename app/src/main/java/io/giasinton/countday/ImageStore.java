package io.giasinton.countday;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 小组件图片的存取。
 *
 * <p>每个实例存两份文件（都在应用私有目录 {@code filesDir/widget_images/}）：
 * <ul>
 *   <li>{@code widget_<id>_src.png} —— 选图后未经裁剪的原图，供反复裁剪；</li>
 *   <li>{@code widget_<id>.png} —— 按裁剪框裁好的成品，小组件渲染用的就是它。</li>
 * </ul>
 * 不依赖 SAF 的长期 URI 授权，也没有跨进程读取权限问题。
 *
 * <p>缩放用<b>逐级折半</b>（progressive downscale）：大幅缩小时一次双线性采样会漏掉大量像素、
 * 产生锯齿，分多次折半逼近目标尺寸能保留更多细节。全程 {@link Bitmap.Config#ARGB_8888}，
 * 保证 PNG 的透明通道不丢。
 */
final class ImageStore {

    private static final String DIR_NAME = "widget_images";
    /**
     * 落盘时的最长边（像素）。
     *
     * <p>图片不再塞进 RemoteViews 的 Bundle（那样会被 Binder 的 1MB 限制卡到 384px），
     * 改成由桌面通过 {@code setImageViewUri} 直接读这个文件，所以这里可以放心存大图。
     */
    private static final int STORE_MAX_PX = 1440;

    /**
     * 成品图渲染器的版本号。
     *
     * <p>渲染算法本身改了（比如换了切圆角的方式），老成品图就会是错的，
     * 所以要把它记在配置里：版本号对不上就自动重渲染一次，用户不用重新保存。
     */
    static final int RENDER_REVISION = 1;

    private ImageStore() {
    }

    private static File dir(Context context) {
        File dir = new File(context.getFilesDir(), DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        return dir;
    }

    /** 裁剪后的成品（小组件用）。 */
    private static File fileFor(Context context, int widgetId) {
        File dir = dir(context);
        return dir == null ? null : new File(dir, "widget_" + widgetId + ".png");
    }

    /** 未裁剪的原图（裁剪编辑用）。 */
    private static File sourceFileFor(Context context, int widgetId) {
        File dir = dir(context);
        return dir == null ? null : new File(dir, "widget_" + widgetId + "_src.png");
    }

    /** 是否还留着未裁剪的原图（供反复裁剪）。 */
    static boolean hasSource(Context context, int widgetId) {
        File source = sourceFileFor(context, widgetId);
        return source != null && source.exists() && source.length() > 0;
    }

    static boolean exists(Context context, int widgetId) {
        File file = fileFor(context, widgetId);
        return file != null && file.exists() && file.length() > 0;
    }

    // ------------------------------------------------------------ 读

    /** 小组件渲染用的成品图。 */
    static Bitmap load(Context context, int widgetId, int maxPx) {
        return decode(fileFor(context, widgetId), maxPx);
    }

    /** 裁剪编辑用的原图；早期版本没有原图时退回成品图。 */
    static Bitmap loadSource(Context context, int widgetId, int maxPx) {
        File source = sourceFileFor(context, widgetId);
        if (source != null && source.exists() && source.length() > 0) {
            return decode(source, maxPx);
        }
        return load(context, widgetId, maxPx);
    }

    private static Bitmap decode(File file, int maxPx) {
        if (file == null || !file.exists()) {
            return null;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            bounds.inScaled = false;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx);
            options.inPreferredConfig = Bitmap.Config.ARGB_8888; // 保留透明
            options.inScaled = false;                            // 不要按图片自带的 density 再缩放
            Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            return scaleTo(decoded, maxPx);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    // ------------------------------------------------------------ 写

    /** 从用户选中的图片流读入，缩放后返回（不写盘），失败返回 null。 */
    static Bitmap importFrom(Context context, InputStream input, int maxPx) {
        File temp = null;
        try {
            temp = File.createTempFile("countday", ".img", context.getCacheDir());
            copy(input, temp);

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            bounds.inScaled = false;
            BitmapFactory.decodeFile(temp.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx);
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inScaled = false;
            return scaleTo(BitmapFactory.decodeFile(temp.getAbsolutePath(), options), maxPx);
        } catch (IOException | OutOfMemoryError e) {
            return null;
        } finally {
            if (temp != null) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
    }

    /** 以 PNG 保存原图。 */
    static boolean saveSource(Context context, int widgetId, Bitmap bitmap) {
        return write(sourceFileFor(context, widgetId), bitmap);
    }

    /**
     * 生成并保存"最终显示用"的成品图。
     *
     * <p>流程：按裁剪框裁 → 按卡片长宽比裁齐（保证铺满不变形）→ 逐级折半粗降
     * → <b>双三次插值</b>缩放到目标分辨率 → 烘上卡片圆角。这样桌面拿到的图正好是它要显示的
     * 像素尺寸，不需要再缩放，也就不会糊。
     */
    static boolean saveDisplay(Context context, int widgetId,
                              float left, float top, float right, float bottom,
                              int targetWidth, int targetHeight,
                              float cornerRadiusPx) {
        Bitmap source = loadSource(context, widgetId, STORE_MAX_PX);
        if (source == null) {
            return false;
        }
        Bitmap display = renderForTarget(source, left, top, right, bottom,
                targetWidth, targetHeight, cornerRadiusPx);
        if (display == null) {
            return false;
        }
        // 四角必须是透明的（0），否则就是圆角没生效（历史上 DST_IN 在部分机型上是 no-op）
        Log.d("CountDay", "saveDisplay widget=" + widgetId
                + " target=" + targetWidth + "x" + targetHeight
                + " out=" + display.getWidth() + "x" + display.getHeight()
                + " radiusPx=" + Math.round(cornerRadiusPx)
                + " corners=" + alphaAt(display, 2, 2)
                + "," + alphaAt(display, display.getWidth() - 3, 2)
                + "," + alphaAt(display, 2, display.getHeight() - 3)
                + "," + alphaAt(display, display.getWidth() - 3, display.getHeight() - 3));
        return write(fileFor(context, widgetId), display);
    }

    /**
     * 按目标分辨率渲染（不落盘），预览和成品图共用同一套算法。
     *
     * <p>图片<b>必须完全铺满卡片</b>：按卡片长宽比居中裁齐，尺寸正好是目标像素，
     * 不会有留白。同时把卡片圆角<b>烘进位图</b> —— RemoteViews 没法给 ImageView 做圆角裁剪，
     * 透明图片的直角会戳出卡片圆角，只能这样解决。
     */
    static Bitmap renderForTarget(Bitmap source, float left, float top, float right, float bottom,
                                  int targetWidth, int targetHeight, float cornerRadiusPx) {
        if (source == null || targetWidth <= 0 || targetHeight <= 0) {
            return null;
        }
        Bitmap cropped = crop(source, left, top, right, bottom);
        if (cropped == null) {
            return null;
        }
        Bitmap squared = coverCrop(cropped, targetWidth, targetHeight);
        Bitmap scaled = BicubicScaler.scale(scaleTo(squared, Math.max(targetWidth, targetHeight) * 2),
                targetWidth, targetHeight);
        return cornerRadiusPx > 0f ? roundCorners(scaled, cornerRadiusPx) : scaled;
    }

    /** 居中裁到目标长宽比（保证铺满且不变形）。 */
    private static Bitmap coverCrop(Bitmap src, int targetWidth, int targetHeight) {
        if (src == null) {
            return null;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        float targetAspect = (float) targetWidth / targetHeight;
        float aspect = (float) w / h;
        if (Math.abs(aspect - targetAspect) < 0.002f) {
            return src;
        }
        int x = 0;
        int y = 0;
        int cw = w;
        int ch = h;
        if (aspect > targetAspect) {
            cw = Math.max(1, Math.round(h * targetAspect));
            x = (w - cw) / 2;
        } else {
            ch = Math.max(1, Math.round(w / targetAspect));
            y = (h - ch) / 2;
        }
        try {
            return Bitmap.createBitmap(src, x, y, cw, ch);
        } catch (IllegalArgumentException | OutOfMemoryError e) {
            return src;
        }
    }

    /**
     * 把四个角切成透明，让图片和卡片的圆角吻合。
     *
     * <p><b>不能用 {@code PorterDuffXfermode(DST_IN)}</b> —— 实测在 Android 16 上是 no-op，
     * 掩码画上去什么都不切（表现就是"只有上面两个角有圆角"）。改用 {@code clipPath}：
     * 先裁出圆角路径再把图绘进去，路径外自然保持透明。
     *
     * <p>另外画布和新建位图的密度都要对齐源图：{@code Canvas.drawBitmap} 会按
     * 「画布密度 / 位图密度」再缩放一次，密度不一致会把图画大、右下角被推出可视区。
     */
    private static Bitmap roundCorners(Bitmap src, float radiusPx) {
        int w = src.getWidth();
        int h = src.getHeight();
        float radius = Math.min(radiusPx, Math.min(w, h) / 2f);
        if (radius <= 0.5f) {
            return src;
        }
        try {
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            out.setDensity(src.getDensity());
            Canvas canvas = new Canvas(out);
            canvas.setDensity(src.getDensity());
            Path path = new Path();
            path.addRoundRect(new RectF(0f, 0f, w, h), radius, radius, Path.Direction.CW);
            canvas.clipPath(path);
            canvas.drawBitmap(src, 0f, 0f, new Paint(Paint.FILTER_BITMAP_FLAG));
            return out;
        } catch (OutOfMemoryError e) {
            return src;
        }
    }

    /** 取某个像素的 alpha（调试用）。 */
    private static int alphaAt(Bitmap bitmap, int x, int y) {
        try {
            return Color.alpha(bitmap.getPixel(
                    Math.max(0, Math.min(x, bitmap.getWidth() - 1)),
                    Math.max(0, Math.min(y, bitmap.getHeight() - 1))));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static boolean write(File file, Bitmap bitmap) {
        if (file == null || bitmap == null) {
            return false;
        }
        try (OutputStream out = new FileOutputStream(file)) {
            return bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteFile(File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    static void delete(Context context, int widgetId) {
        deleteFile(fileFor(context, widgetId));
        deleteFile(sourceFileFor(context, widgetId));
    }

    /** 按归一化坐标裁剪；坐标为 0~1，自动夹取到有效范围。返回新位图（可能等于原图）。 */
    static Bitmap crop(Bitmap source, float left, float top, float right, float bottom) {
        if (source == null) {
            return null;
        }
        int w = source.getWidth();
        int h = source.getHeight();
        // 裁剪框夹回图片范围内
        left = clamp01(left);
        top = clamp01(top);
        right = clamp01(right);
        bottom = clamp01(bottom);
        int x1 = clamp(Math.round(left * w), 0, w - 1);
        int y1 = clamp(Math.round(top * h), 0, h - 1);
        int x2 = clamp(Math.round(right * w), x1 + 1, w);
        int y2 = clamp(Math.round(bottom * h), y1 + 1, h);
        if (x1 == 0 && y1 == 0 && x2 == w && y2 == h) {
            return source;
        }
        try {
            return Bitmap.createBitmap(source, x1, y1, x2 - x1, y2 - y1);
        } catch (IllegalArgumentException | OutOfMemoryError e) {
            return source;
        }
    }

    /**
     * 等比缩放到最长边不超过 maxPx。
     *
     * <p>先反复折半逼近目标，再做最后一次精确缩放：这样每次采样的缩放比都接近 1:1，
     * 不会像一次性大幅缩小那样整片丢像素（锯齿/摩尔纹）。
     * 注意不能 recycle 入参 —— 传进来的可能就是调用方手上的位图。
     */
    static Bitmap scaleTo(Bitmap source, int maxPx) {
        if (source == null || maxPx <= 0) {
            return source;
        }
        int longest = Math.max(source.getWidth(), source.getHeight());
        if (longest <= maxPx) {
            return source;
        }
        float ratio = (float) maxPx / longest;
        int targetW = Math.max(1, Math.round(source.getWidth() * ratio));
        int targetH = Math.max(1, Math.round(source.getHeight() * ratio));

        Bitmap current = source;
        while (current.getWidth() >= targetW * 2 && current.getHeight() >= targetH * 2) {
            int nextW = Math.max(targetW, current.getWidth() / 2);
            int nextH = Math.max(targetH, current.getHeight() / 2);
            current = Bitmap.createScaledBitmap(current, nextW, nextH, true);
        }
        if (current.getWidth() != targetW || current.getHeight() != targetH) {
            current = Bitmap.createScaledBitmap(current, targetW, targetH, true);
        }
        return current;
    }

    private static void copy(InputStream input, File target) throws IOException {
        try (OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    /** inSampleSize 取 2 的幂，保证解码结果不会比目标分辨率小。 */
    private static int sampleSize(int width, int height, int maxPx) {
        int sample = 1;
        while (maxPx > 0 && maxPx < Integer.MAX_VALUE) {
            int next = sample * 2;
            if (Math.max(width, height) / next < maxPx) {
                break;
            }
            sample = next;
        }
        return sample;
    }

    private static float clamp01(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    /** 供 ContentProvider 读取成品文件。 */
    static File croppedFile(Context context, int widgetId) {
        return fileFor(context, widgetId);
    }

    /** 成品文件的"版本号"（修改时间），用来生成会变化的 URI。 */
    static long croppedVersion(Context context, int widgetId) {
        File file = fileFor(context, widgetId);
        return file != null && file.exists() ? file.lastModified() : 0L;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    static int storeMaxPx() {
        return STORE_MAX_PX;
    }
}
