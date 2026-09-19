package io.giasinton.countday;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 调色板：一个"饱和度 × 明度"方块，配合外部的色相滑杆使用。
 *
 * <p>画法用三层叠加（纯色相底色 + 白→透明 + 透明→黑），不用逐像素生成位图，
 * 任意尺寸都清晰、开销也小。取色结果由 {@link #getColor()} 给出（不含透明度）。
 */
public class ColorPickerView extends View {

    interface OnColorChangedListener {
        void onColorChanged(int color);
    }

    private float hue;
    private float saturation = 1f;
    private float value = 1f;

    private final RectF rect = new RectF();
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorStroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float density;
    private OnColorChangedListener listener;

    public ColorPickerView(Context context) {
        this(context, null);
    }

    public ColorPickerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(Math.max(1f, density));
        borderPaint.setColor(0x33000000);
        cursorStroke.setStyle(Paint.Style.STROKE);
        cursorStroke.setStrokeWidth(Math.max(2f, 2f * density));
        cursorStroke.setColor(0xFFFFFFFF);
        cursorPaint.setStyle(Paint.Style.STROKE);
        cursorPaint.setStrokeWidth(Math.max(1f, density));
        cursorPaint.setColor(0x66000000);
    }

    void setOnColorChangedListener(OnColorChangedListener listener) {
        this.listener = listener;
    }

    /** 设置当前颜色（不含 alpha 也没关系）。 */
    void setColor(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hue = hsv[0];
        saturation = hsv[1];
        value = hsv[2];
        rebuildShaders();   // 底色是跟着色相走的，换了色相必须重建，否则色块不变
        invalidate();
    }

    /** 只更新色相（来自色相滑杆）。 */
    void setHue(float newHue) {
        hue = ((newHue % 360f) + 360f) % 360f;
        rebuildShaders();   // ← 之前漏了这句：拖色条时色块不跟着变
        invalidate();
    }

    float getHue() {
        return hue;
    }

    int getColor() {
        return Color.HSVToColor(new float[] { hue, saturation, value });
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float pad = density * 1.5f;
        rect.set(pad, pad, w - pad, h - pad);
        rebuildShaders();
    }

    private void rebuildShaders() {
        basePaint.setShader(null);
        basePaint.setColor(Color.HSVToColor(new float[] { hue, 1f, 1f }));
        whitePaint.setShader(new LinearGradient(rect.left, 0, rect.right, 0,
                0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        blackPaint.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (rect.width() <= 0 || rect.height() <= 0) {
            return;
        }
        canvas.drawRect(rect, basePaint);
        canvas.drawRect(rect, whitePaint);
        canvas.drawRect(rect, blackPaint);
        canvas.drawRect(rect, borderPaint);

        float cx = rect.left + saturation * rect.width();
        float cy = rect.top + (1f - value) * rect.height();
        float r = Math.max(6f, 7f * density);
        canvas.drawCircle(cx, cy, r, cursorStroke);
        canvas.drawCircle(cx, cy, r, cursorPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                updateFromTouch(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                updateFromTouch(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                updateFromTouch(event.getX(), event.getY());
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void updateFromTouch(float x, float y) {
        if (rect.width() <= 0 || rect.height() <= 0) {
            return;
        }
        saturation = clamp01((x - rect.left) / rect.width());
        value = 1f - clamp01((y - rect.top) / rect.height());
        invalidate();
        if (listener != null) {
            listener.onColorChanged(getColor());
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
