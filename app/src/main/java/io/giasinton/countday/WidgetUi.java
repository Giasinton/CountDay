package io.giasinton.countday;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.net.Uri;
import android.util.SizeF;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RemoteViews;

import java.util.List;

/** 负责生成 / 刷新小组件界面。 */
final class WidgetUi {

    private static final String TAG = "CountDay";

    /** 自定义尺寸的百分比范围（占格子短边）。 */
    static final int SIZE_MIN_PERCENT = 30;
    static final int SIZE_MAX_PERCENT = 100;

    /** 基准字号（sp），实际字号 = 基准 × textScalePercent%。 */
    static final float BASE_TITLE_SP = 13f;
    static final float BASE_NUMBER_SP = 38f;
    static final float BASE_DATE_SP = 10f;
    /** 卡片圆角（dp），和 widget_card_default / CardBackground 保持一致。 */
    static final float DEFAULT_RADIUS_DP = 20f;
    /** 圆角可调范围（dp）：0 = 直角，上限给到能把卡片做成胶囊大小。 */
    static final float MIN_RADIUS_DP = 0f;
    static final float MAX_RADIUS_DP = 60f;

    /** 数字后面那个"天"的字号：和下面的日期小字保持一致。 */
    static final float BASE_UNIT_SP = BASE_DATE_SP;

    private WidgetUi() {
    }

    static RemoteViews build(Context context, int widgetId, WidgetData data) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] cell = widgetSizeDp(manager, widgetId);
        int[] box = boxSizeDp(data, cell[0], cell[1]);

        // 有没有图片，决定卡片要不要描边（图片的透明边缘会把描边透出来）
        boolean hasPhoto = data != null && data.hasImage && ImageStore.exists(context, widgetId);

        Log.d(TAG, "widget " + widgetId + " cell=" + cell[0] + "x" + cell[1]
                + "dp box=" + box[0] + "x" + box[1] + "dp"
                + " radius=" + clampRadiusDp(data) + "dp"
                + " customBg=" + (data != null && data.customBg)
                + " bg=#" + (data != null && data.customBg
                        ? Integer.toHexString(data.bgColor) : "theme")
                + " hasPhoto=" + hasPhoto
                + " customRadius=" + (Math.abs(clampRadiusDp(data) - DEFAULT_RADIUS_DP) > 0.01f));

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_countday);
        // 卡片的目标显示像素尺寸：图片渲染和文字微调都要用
        int[] target = targetPixels(context, box);

        // 1) 背景卡片
        //    默认色用真正的 shape drawable：圆角原生、跟随深浅色、零传输开销；
        //    自定义色/自定义圆角只能靠位图（RemoteViews 无法动态改 drawable 的圆角或颜色）。
        float radiusDp = clampRadiusDp(data);
        boolean customRadius = Math.abs(radiusDp - DEFAULT_RADIUS_DP) > 0.01f;
        if (data != null && (data.customBg || customRadius)) {
            int cardColor = data.customBg ? data.bgColor : context.getColor(R.color.widget_bg);
            views.setImageViewBitmap(R.id.widget_card,
                    CardBackground.create(cardColor, box[0], box[1], !hasPhoto, radiusDp));
        } else {
            views.setImageViewResource(R.id.widget_card,
                    hasPhoto ? R.drawable.widget_card_plain : R.drawable.widget_card_default);
        }

        // 2) 卡片盒子尺寸
        //    自定义尺寸时精确指定 dp；填满格子时显式还原成 MATCH_PARENT（否则上一次的固定尺寸会残留）。
        //    动态尺寸是 Android 12+ 的能力，更低版本退化为铺满。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean custom = data != null && data.sizeMode != WidgetData.SIZE_FILL;
            int unit = custom ? TypedValue.COMPLEX_UNIT_DIP : TypedValue.COMPLEX_UNIT_PX;
            int width = custom ? box[0] : ViewGroup.LayoutParams.MATCH_PARENT;
            int height = custom ? box[1] : ViewGroup.LayoutParams.MATCH_PARENT;
            views.setViewLayoutWidth(R.id.widget_box, width, unit);
            views.setViewLayoutHeight(R.id.widget_box, height, unit);
        }

        // 3) 图片：交给桌面按 URI 自己解码（完整分辨率，不受 Binder 传输大小限制）
        if (hasPhoto) {
            // 成品图必须是「当前尺寸 + 当前圆角 + 当前渲染器版本」算出来的；任一变了就重算
            if (data.renderedBoxWidthDp != box[0] || data.renderedBoxHeightDp != box[1]
                    || data.renderRevision != ImageStore.RENDER_REVISION) {
                float density = context.getResources().getDisplayMetrics().density;
                if (ImageStore.saveDisplay(context, widgetId,
                        data.cropLeft, data.cropTop, data.cropRight, data.cropBottom,
                        target[0], target[1], radiusDp * density)) {
                    data.renderedBoxWidthDp = box[0];
                    data.renderedBoxHeightDp = box[1];
                    data.renderRevision = ImageStore.RENDER_REVISION;
                    WidgetStore.save(context, widgetId, data);
                }
            }
            Uri photoUri = ImageProvider.uriFor(widgetId, ImageStore.croppedVersion(context, widgetId));
            grantHostReadAccess(context, photoUri);
            views.setImageViewUri(R.id.widget_photo, photoUri);
            views.setViewVisibility(R.id.widget_photo, View.VISIBLE);
        } else {
            views.setViewVisibility(R.id.widget_photo, View.GONE);
        }

        // 4) 文字内容
        if (data == null) {
            views.setTextViewText(R.id.widget_title, context.getString(R.string.app_name));
            views.setTextViewText(R.id.widget_number, "");
            views.setTextViewText(R.id.widget_unit, "");
            views.setTextViewText(R.id.widget_date, context.getString(R.string.tap_to_configure));
        } else {
            WidgetText text = WidgetText.of(context, data, DayMath.todayEpochDay());
            views.setTextViewText(R.id.widget_title, data.title);
            views.setTextViewText(R.id.widget_number, text.number);
            views.setTextViewText(R.id.widget_unit, text.unit);
            views.setTextViewText(R.id.widget_date, text.dateLine);
        }

        // 5) 文字颜色：自定义时直接用；否则用主题色（同样显式设置，避免上一次的颜色残留）
        int titleColor;
        int numberColor;
        int dateColor;
        if (data != null && data.customTextColor) {
            titleColor = data.textColor;
            numberColor = data.textColor;
            dateColor = data.textColor;
        } else {
            titleColor = context.getColor(R.color.widget_text_secondary);
            numberColor = context.getColor(R.color.widget_text_primary);
            dateColor = context.getColor(R.color.widget_text_tertiary);
        }
        views.setTextColor(R.id.widget_title, titleColor);
        views.setTextColor(R.id.widget_number, numberColor);
        views.setTextColor(R.id.widget_unit, numberColor);
        views.setTextColor(R.id.widget_date, dateColor);

        // 6) 文字位置 / 对齐 / 字号 / 微调偏移
        WidgetData style = data != null ? data : new WidgetData();
        views.setInt(R.id.widget_text, "setGravity", textBlockGravity(style.textVPos));

        // 微调：在基础对齐之上平移整块文字。用 translation 而不是 padding，
        // 好处是不会压缩文字可用宽度（padding 会让它提前省略号）。
        float offsetX = style.textOffsetXPercent / 100f * target[0];
        float offsetY = style.textOffsetYPercent / 100f * target[1];
        views.setFloat(R.id.widget_text, "setTranslationX", offsetX);
        views.setFloat(R.id.widget_text, "setTranslationY", offsetY);
        int textGravity = textAlignGravity(style.textAlign);
        float scale = style.textScalePercent / 100f;
        setText(views, R.id.widget_title, BASE_TITLE_SP * scale, textGravity);
        setText(views, R.id.widget_date, BASE_DATE_SP * scale, textGravity);
        // 数字和小号"天"在同一行：行的 gravity 决定整体左右位置，垂直方向默认按基线对齐
        views.setInt(R.id.widget_number_row, "setGravity", textGravity);
        setText(views, R.id.widget_number, BASE_NUMBER_SP * scale, textGravity);
        setText(views, R.id.widget_unit, BASE_UNIT_SP * scale, textGravity);

        // 单击不响应（避免误触），但把点击报给自己数连击 —— 连点 3 下即打开配置页。
        // 用广播而不是直接 getActivity：连击状态需要自己维护，不能每点一下就拉起界面。
        Intent tap = new Intent(context, CountDayWidgetProvider.class);
        tap.setAction(CountDayWidgetProvider.ACTION_WIDGET_TAP);
        tap.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getBroadcast(
                context, widgetId, tap, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        return views;
    }

    /**
     * 把图片 URI 的读权限显式授给桌面。
     *
     * <p>实测系统不会自动把 RemoteViews 里的 URI 权限授予 host，所以这里自己来：
     * 通过 HOME intent 查出所有桌面应用的包名（清单里声明了对应的 queries），逐个授权。
     * 授权是持久的，桌面之后重新应用 RemoteViews 也还能读到图。
     */
    private static void grantHostReadAccess(Context context, Uri uri) {
        PackageManager packageManager = context.getPackageManager();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> hosts = packageManager.queryIntentActivities(home, 0);
        for (ResolveInfo info : hosts) {
            if (info.activityInfo == null) {
                continue;
            }
            try {
                context.grantUriPermission(info.activityInfo.packageName, uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (RuntimeException e) {
                Log.w(TAG, "授权给 " + info.activityInfo.packageName + " 失败", e);
            }
        }
    }

    private static void setText(RemoteViews views, int viewId, float sizeSp, int gravity) {
        views.setTextViewTextSize(viewId, TypedValue.COMPLEX_UNIT_SP, Math.max(6f, sizeSp));
        views.setInt(viewId, "setGravity", gravity);
    }

    /** 文字块在卡片里的竖直位置（水平方向由每个 TextView 自己对齐）。 */
    static int textBlockGravity(int vPos) {
        int vertical;
        switch (vPos) {
            case WidgetData.VPOS_TOP:
                vertical = Gravity.TOP;
                break;
            case WidgetData.VPOS_BOTTOM:
                vertical = Gravity.BOTTOM;
                break;
            default:
                vertical = Gravity.CENTER_VERTICAL;
                break;
        }
        return vertical | Gravity.CENTER_HORIZONTAL;
    }

    static int textAlignGravity(int align) {
        switch (align) {
            case WidgetData.ALIGN_LEFT:
                return Gravity.START;
            case WidgetData.ALIGN_RIGHT:
                return Gravity.END;
            default:
                return Gravity.CENTER_HORIZONTAL;
        }
    }

    /** 卡片实际尺寸（dp）：填满格子 / 正方形 / 跟随图片 / 自定义。 */
    static int[] boxSizeDp(WidgetData data, int cellWidthDp, int cellHeightDp) {
        if (data == null) {
            return new int[] { cellWidthDp, cellHeightDp };
        }
        int base = Math.min(cellWidthDp, cellHeightDp);
        switch (data.sizeMode) {
            case WidgetData.SIZE_SQUARE:
                return new int[] { base, base };
            case WidgetData.SIZE_IMAGE:
                // 比例未知（还没选过图片）时退化成填满格子
                return BoxMath.imageBox(cellWidthDp, cellHeightDp, data.imageAspect);
            case WidgetData.SIZE_CUSTOM:
                return new int[] {
                        Math.max(1, Math.round(base * clampPercent(data.sizePercentW) / 100f)),
                        Math.max(1, Math.round(base * clampPercent(data.sizePercentH) / 100f)) };
            default:
                return new int[] { cellWidthDp, cellHeightDp };
        }
    }

    /** 卡片的目标显示像素尺寸（dp × 屏幕密度）。 */
    static int[] targetPixels(Context context, int[] boxDp) {
        float density = context.getResources().getDisplayMetrics().density;
        return new int[] {
                Math.max(1, Math.round(boxDp[0] * density)),
                Math.max(1, Math.round(boxDp[1] * density)) };
    }

    static int clampPercent(int percent) {
        return Math.max(SIZE_MIN_PERCENT, Math.min(percent, SIZE_MAX_PERCENT));
    }

    /** 卡片圆角（dp），夹到可调范围内；数据为空时用默认值。 */
    static float clampRadiusDp(WidgetData data) {
        if (data == null) {
            return DEFAULT_RADIUS_DP;
        }
        float radius = data.cornerRadiusDp;
        if (Float.isNaN(radius)) {
            return DEFAULT_RADIUS_DP;
        }
        return Math.max(MIN_RADIUS_DP, Math.min(radius, MAX_RADIUS_DP));
    }

    static void updateWidget(Context context, int widgetId) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            manager.updateAppWidget(widgetId, build(context, widgetId, WidgetStore.load(context, widgetId)));
        } catch (RuntimeException e) {
            // 刷新失败（例如位图太大被 Binder 拒绝）不应该把调用方一起弄崩
            Log.w(TAG, "刷新小组件失败 widgetId=" + widgetId, e);
        }
    }

    /** 刷新所有还在桌面上的实例。 */
    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName provider = new ComponentName(context, CountDayWidgetProvider.class);
        for (int widgetId : manager.getAppWidgetIds(provider)) {
            updateWidget(context, widgetId);
        }
    }

    /**
     * 小组件当前格子尺寸（dp）。
     *
     * <p>坑：各家桌面给的 {@code OPTION_APPWIDGET_MIN_*} 并不完全可靠 ——
     * 实测某 ROM 上 2x2 组件报的是 179x107（高度只按 1 行算），而实际渲染是 179x200。
     * API 31+ 的 {@code OPTION_APPWIDGET_SIZES} 第一项才是当前尺寸，优先用它，
     * 再和 MIN 取较大值兜底，避免算小了把卡片压扁。
     */
    static int[] widgetSizeDp(AppWidgetManager manager, int widgetId) {
        int minWidth = 0;
        int minHeight = 0;
        float sizesWidth = 0f;
        float sizesHeight = 0f;

        Bundle options = manager.getAppWidgetOptions(widgetId);
        if (options != null) {
            minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
            minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                List<SizeF> sizes = options.getParcelableArrayList("appWidgetSizes");
                if (sizes != null && !sizes.isEmpty()) {
                    sizesWidth = sizes.get(0).getWidth();
                    sizesHeight = sizes.get(0).getHeight();
                }
            }
        }

        int width = Math.round(Math.max(minWidth, sizesWidth));
        int height = Math.round(Math.max(minHeight, sizesHeight));
        if (width > 0 && height > 0) {
            return new int[] { width, height };
        }

        AppWidgetProviderInfo info = manager.getAppWidgetInfo(widgetId);
        if (info != null && info.minWidth > 0 && info.minHeight > 0) {
            return new int[] { info.minWidth, info.minHeight };
        }
        return new int[] { 110, 110 };
    }

}
