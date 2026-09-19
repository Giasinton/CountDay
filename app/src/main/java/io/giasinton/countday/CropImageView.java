package io.giasinton.countday;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 按组件尺寸裁剪图片：裁剪框固定（比例 = 卡片比例），用户拖动 / 双指缩放图片本身。
 *
 * <p><b>图片必须始终完全盖住裁剪框</b>：不能拖出框外，也不能缩到比框小 ——
 * 因为成品图是按"填满卡片"渲染的，框内露出空白就等于最终显示有空白。
 * 所以最小缩放就是"刚好铺满框"，拖动时四边也都被夹在框外。
 *
 * <p>裁剪结果用<b>归一化坐标</b>（相对原图 0~1）保存，和控件大小、图片分辨率都无关；
 * 小组件渲染时按这个矩形裁一次即可。
 */
public class CropImageView extends View {

    interface OnCropListener {
        void onCropChanged();
    }

    private Bitmap bitmap;
    private float frameAspect = 1f;

    /** 图片绘制参数：view 像素 / 原图像素，以及原图左上角在 view 中的位置。 */
    private float scale = 1f;
    private float offsetX = 0f;
    private float offsetY = 0f;
    private float minScale = 0.1f;
    private float maxScale = 8f;

    private final RectF frame = new RectF();
    private final RectF imageRect = new RectF();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float density;
    private float downX;
    private float downY;
    private float startOffsetX;
    private float startOffsetY;
    private float pinchStartDistance;
    private float pinchStartScale;
    private boolean multiTouch;

    private OnCropListener listener;
    /** 控件还没布局完（宽高为 0）时先存着，等 onSizeChanged 再应用。 */
    private float[] pendingCrop;
    private boolean needsInitialFit = true;
    /** 用户是否手动拖动/缩放过；没动过时，控件尺寸变化就重新铺满。 */
    private boolean userAdjusted;

    public CropImageView(Context context) {
        this(context, null);
    }

    public CropImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(Math.max(1.5f, 2f * density));
        borderPaint.setColor(0xFFFFFFFF);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(Math.max(1f, density));
        gridPaint.setColor(0x40FFFFFF);
    }

    void setOnCropListener(OnCropListener listener) {
        this.listener = listener;
    }

    void setFrameAspect(float aspect) {
        if (aspect <= 0f) {
            return;
        }
        frameAspect = aspect;
        updateFrame();
        if (!userAdjusted && bitmap != null && isLaidOut()) {
            // 用户还没手动调过：框的比例变了就重新居中铺满，否则会偏在一边
            resetCrop();
        } else {
            clampTransform();
        }
        invalidate();
    }

    void setBitmap(Bitmap bitmap) {
        this.bitmap = bitmap;
        this.pendingCrop = null;
        this.needsInitialFit = true;
        updateFrame();
        if (isLaidOut()) {
            resetCrop();
        } else {
            invalidate();
        }
    }

    Bitmap getBitmap() {
        return bitmap;
    }

    /** 裁剪结果：归一化到原图的 {left, top, right, bottom}。 */
    float[] getCropRect() {
        if (bitmap == null || scale <= 0f) {
            return new float[] { 0f, 0f, 1f, 1f };
        }
        float w = bitmap.getWidth();
        float h = bitmap.getHeight();
        float l = (frame.left - offsetX) / scale / w;
        float t = (frame.top - offsetY) / scale / h;
        float r = (frame.right - offsetX) / scale / w;
        float b = (frame.bottom - offsetY) / scale / h;
        return new float[] { clamp(l, -2f, 3f), clamp(t, -2f, 3f), clamp(r, -2f, 3f), clamp(b, -2f, 3f) };
    }

    void setCropRect(float l, float t, float r, float b) {
        if (bitmap == null) {
            return;
        }
        if (!isLaidOut()) {
            pendingCrop = new float[] { l, t, r, b };
            return;
        }
        float imgW = bitmap.getWidth();
        float imgH = bitmap.getHeight();
        float cropW = (r - l) * imgW;
        float cropH = (b - t) * imgH;
        if (cropW <= 1f || cropH <= 1f || frame.width() <= 0f) {
            resetCrop();
            return;
        }
        scale = Math.max(frame.width() / cropW, frame.height() / cropH);
        float centerX = (l + r) / 2f * imgW;
        float centerY = (t + b) / 2f * imgH;
        offsetX = frame.centerX() - centerX * scale;
        offsetY = frame.centerY() - centerY * scale;
        userAdjusted = true;   // 恢复出来的是用户之前存下的位置，别被后续尺寸变化重置
        clampTransform();
        invalidate();
    }

    /** 铺满裁剪框所需的最小缩放：图片必须完全盖住框，所以不能再小。 */
    private float coverScale() {
        if (bitmap == null) {
            return 1f;
        }
        return BoxMath.coverScale(bitmap.getWidth(), bitmap.getHeight(),
                frame.width(), frame.height());
    }

    /** 铺满裁剪框并居中。缩放范围也在这里定：最小 = 刚好盖住框，最大 = 8 倍。 */
    void resetCrop() {
        if (bitmap == null || !isLaidOut() || frame.width() <= 0f) {
            return;
        }
        needsInitialFit = false;
        pendingCrop = null;
        userAdjusted = false;
        float cover = coverScale();
        minScale = cover;
        maxScale = cover * 8f;
        scale = cover;
        offsetX = frame.centerX() - bitmap.getWidth() * scale / 2f;
        offsetY = frame.centerY() - bitmap.getHeight() * scale / 2f;
        clampTransform();
        invalidate();
        notifyChanged();
    }

    /** 按钮用的缩放（以裁剪框中心为焦点）。 */
    void zoomBy(float factor) {
        if (bitmap == null || !isLaidOut() || frame.width() <= 0f) {
            return;
        }
        float target = clamp(scale * factor, minScale, maxScale);
        float applied = target / scale;
        offsetX = frame.centerX() + (offsetX - frame.centerX()) * applied;
        offsetY = frame.centerY() + (offsetY - frame.centerY()) * applied;
        scale = target;
        userAdjusted = true;
        clampTransform();
        invalidate();
        notifyChanged();
    }

    private void updateFrame() {
        float pad = 10f * density;
        float availW = Math.max(1f, getWidth() - pad * 2f);
        float availH = Math.max(1f, getHeight() - pad * 2f);
        float w;
        float h;
        if (availW / availH > frameAspect) {
            h = availH;
            w = h * frameAspect;
        } else {
            w = availW;
            h = w / frameAspect;
        }
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        frame.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
    }

    private void clampTransform() {
        if (bitmap == null || frame.width() <= 0f) {
            return;
        }
        // 图片必须完全盖住裁剪框：最小缩放 = 刚好铺满，缩放范围随框的比例变化
        float cover = coverScale();
        minScale = cover;
        maxScale = Math.max(cover * 8f, cover);
        scale = clamp(scale, minScale, maxScale);

        float drawW = bitmap.getWidth() * scale;
        float drawH = bitmap.getHeight() * scale;

        // 四条边都不许缩进框内：图片左边缘 ≤ 框左边，右边缘 ≥ 框右边（上下同理）。
        // scale ≥ cover 保证了下界不会超过上界，所以拖到头就是"贴边"。
        offsetX = BoxMath.coverOffset(offsetX, frame.left, frame.right, drawW);
        offsetY = BoxMath.coverOffset(offsetY, frame.top, frame.bottom, drawH);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateFrame();
        if (w <= 0 || h <= 0 || bitmap == null) {
            return;
        }
        if (pendingCrop != null) {
            float[] c = pendingCrop;
            pendingCrop = null;
            setCropRect(c[0], c[1], c[2], c[3]);
        } else if (needsInitialFit || !userAdjusted) {
            // 首次布局，或者控件尺寸变了但用户还没手动调过 → 重新铺满
            resetCrop();
        } else {
            clampTransform();
        }
    }

    /** 供外部在控件刚显示出来后调用，确保初始化一定发生（有些情况下 onSizeChanged 不再触发）。 */
    void ensureInitialized() {
        if (bitmap == null || !isLaidOut()) {
            return;
        }
        updateFrame();
        if (pendingCrop != null) {
            float[] c = pendingCrop;
            pendingCrop = null;
            setCropRect(c[0], c[1], c[2], c[3]);
        } else if (needsInitialFit || !userAdjusted) {
            resetCrop();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null || frame.width() <= 0f) {
            return;
        }
        imageRect.set(offsetX, offsetY,
                offsetX + bitmap.getWidth() * scale,
                offsetY + bitmap.getHeight() * scale);

        int save = canvas.save();
        canvas.clipRect(frame);
        canvas.drawBitmap(bitmap, null, imageRect, bitmapPaint);
        canvas.restoreToCount(save);

        // 裁剪框 + 三分参考线
        canvas.drawRect(frame, borderPaint);
        float thirdW = frame.width() / 3f;
        float thirdH = frame.height() / 3f;
        for (int i = 1; i <= 2; i++) {
            canvas.drawLine(frame.left + thirdW * i, frame.top, frame.left + thirdW * i, frame.bottom, gridPaint);
            canvas.drawLine(frame.left, frame.top + thirdH * i, frame.right, frame.top + thirdH * i, gridPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                startOffsetX = offsetX;
                startOffsetY = offsetY;
                multiTouch = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() >= 2) {
                    multiTouch = true;
                    pinchStartDistance = distance(event);
                    pinchStartScale = scale;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                userAdjusted = true;
                if (multiTouch && event.getPointerCount() >= 2) {
                    float d = distance(event);
                    if (pinchStartDistance > 1f) {
                        float factor = d / pinchStartDistance;
                        float target = clamp(pinchStartScale * factor, minScale, maxScale);
                        float midX = (event.getX(0) + event.getX(1)) / 2f;
                        float midY = (event.getY(0) + event.getY(1)) / 2f;
                        float applied = target / scale;
                        offsetX = midX + (offsetX - midX) * applied;
                        offsetY = midY + (offsetY - midY) * applied;
                        scale = target;
                        clampTransform();
                        invalidate();
                    }
                } else {
                    offsetX = startOffsetX + (event.getX() - downX);
                    offsetY = startOffsetY + (event.getY() - downY);
                    clampTransform();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                if (multiTouch && event.getPointerCount() >= 2) {
                    // 双指抬起一根后重新以当前位置作为拖动基准
                    downX = event.getX();
                    downY = event.getY();
                    startOffsetX = offsetX;
                    startOffsetY = offsetY;
                }
                multiTouch = false;
                notifyChanged();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private float distance(MotionEvent event) {
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.hypot(dx, dy);
    }

    private void notifyChanged() {
        if (listener != null) {
            listener.onCropChanged();
        }
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }
}
