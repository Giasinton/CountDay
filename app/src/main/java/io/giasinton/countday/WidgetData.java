package io.giasinton.countday;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;

/** 单个小组件实例的配置数据。 */
final class WidgetData {

    /** 倒计时：距离目标日期还有几天。 */
    static final int MODE_COUNTDOWN = 0;
    /** 正计时：从起始日期开始已经过去几天。 */
    static final int MODE_COUNT_UP = 1;

    /** 卡片尺寸：填满桌面给的格子。 */
    static final int SIZE_FILL = 0;
    /** 卡片尺寸：正方形（取格子的短边）。 */
    static final int SIZE_SQUARE = 1;
    /** 卡片尺寸：自定义宽高（dp）。 */
    static final int SIZE_CUSTOM = 2;
    /** 卡片尺寸：比例跟随图片（在格子里取最大的同比例矩形）。 */
    static final int SIZE_IMAGE = 3;

    /** 文字块竖直位置。 */
    static final int VPOS_TOP = 0;
    static final int VPOS_CENTER = 1;
    static final int VPOS_BOTTOM = 2;
    /** 文字水平对齐。 */
    static final int ALIGN_LEFT = 0;
    static final int ALIGN_CENTER = 1;
    static final int ALIGN_RIGHT = 2;

    String title = "";
    long dateEpochDay = LocalDate.now().toEpochDay();
    int mode = MODE_COUNTDOWN;

    /** false 表示跟随系统主题色（浅色/深色自动切换）。 */
    boolean customBg = false;
    /** 自定义背景色，ARGB（已含透明度）；仅 customBg 为 true 时生效。 */
    int bgColor = 0;

    /** 是否使用自定义图片（文件在 ImageStore 里按 widgetId 存放）。 */
    boolean hasImage = false;
    /** 裁剪区域，归一化到 0~1（相对原图）；可以超出 [0,1] 表示图片外留白。 */
    float cropLeft = 0f;
    float cropTop = 0f;
    float cropRight = 1f;
    float cropBottom = 1f;

    /** 文字竖直位置 / 水平对齐 / 字号百分比（50~200）。 */
    int textVPos = VPOS_CENTER;
    int textAlign = ALIGN_CENTER;
    int textScalePercent = 100;
    /** 在基础对齐之上做微调，单位是卡片尺寸的百分比（-50~50）。 */
    int textOffsetXPercent = 0;
    int textOffsetYPercent = 0;

    /** 卡片尺寸模式 + 自定义尺寸（占格子短边的百分比，30~100）。 */
    int sizeMode = SIZE_FILL;
    int sizePercentW = 100;
    int sizePercentH = 100;

    /** 卡片圆角半径（dp）；0 = 直角。图片的圆角也按这个值预先处理好。 */
    float cornerRadiusDp = WidgetUi.DEFAULT_RADIUS_DP;

    /**
     * 原图的宽高比（宽 / 高）。{@link #SIZE_IMAGE} 模式下卡片按它取比例。
     *
     * <p>用<b>原图</b>而不是裁剪框的比例：裁剪框本身是按卡片比例摆的，
     * 两者互相依赖会绕圈；用原图比例则始终稳定，裁剪时会自动按这个比例去框。
     */
    float imageAspect = 0f;

    /** 当前成品图是按多大的卡片尺寸渲染的（dp；用于卡片尺寸变化时重算）。 */
    int renderedBoxWidthDp;
    int renderedBoxHeightDp;
    /** 成品图是按哪一版渲染器生成的；对不上就说明算法升级过，需要重渲染。 */
    int renderRevision;

    /** 文字颜色：false 表示跟随主题。 */
    boolean customTextColor = false;
    /** 自定义文字颜色，ARGB（含透明度）。 */
    int textColor = 0xDE000000;

    WidgetData() {
    }

    JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("title", title);
            json.put("date", dateEpochDay);
            json.put("mode", mode);
            json.put("customBg", customBg);
            json.put("bgColor", bgColor);
            json.put("hasImage", hasImage);
            json.put("cropLeft", cropLeft);
            json.put("cropTop", cropTop);
            json.put("cropRight", cropRight);
            json.put("cropBottom", cropBottom);
            json.put("textVPos", textVPos);
            json.put("textAlign", textAlign);
            json.put("textScale", textScalePercent);
            json.put("textOffsetX", textOffsetXPercent);
            json.put("textOffsetY", textOffsetYPercent);
            json.put("sizeMode", sizeMode);
            json.put("sizePercentW", sizePercentW);
            json.put("sizePercentH", sizePercentH);
            json.put("cornerRadius", cornerRadiusDp);
            json.put("imageAspect", imageAspect);
            json.put("customTextColor", customTextColor);
            json.put("textColor", textColor);
            json.put("renderedBoxW", renderedBoxWidthDp);
            json.put("renderedBoxH", renderedBoxHeightDp);
            json.put("renderRevision", renderRevision);
        } catch (JSONException ignored) {
            // 键值都是合法类型，不会走到这里
        }
        return json;
    }

    static WidgetData fromJson(JSONObject json) {
        WidgetData data = new WidgetData();
        data.title = json.optString("title", "");
        data.dateEpochDay = json.optLong("date", LocalDate.now().toEpochDay());
        data.mode = json.optInt("mode", MODE_COUNTDOWN);
        data.customBg = json.optBoolean("customBg", false);
        data.bgColor = json.optInt("bgColor", 0);
        data.hasImage = json.optBoolean("hasImage", false);
        data.cropLeft = (float) json.optDouble("cropLeft", 0d);
        data.cropTop = (float) json.optDouble("cropTop", 0d);
        data.cropRight = (float) json.optDouble("cropRight", 1d);
        data.cropBottom = (float) json.optDouble("cropBottom", 1d);
        data.textVPos = json.optInt("textVPos", VPOS_CENTER);
        data.textAlign = json.optInt("textAlign", ALIGN_CENTER);
        data.textScalePercent = json.optInt("textScale", 100);
        data.textOffsetXPercent = json.optInt("textOffsetX", 0);
        data.textOffsetYPercent = json.optInt("textOffsetY", 0);
        data.sizeMode = json.optInt("sizeMode", SIZE_FILL);
        // 旧版本存的是 dp，这里按"占格子短边比例"近似迁移（默认格子短边约 179dp）
        if (json.has("sizePercentW")) {
            data.sizePercentW = json.optInt("sizePercentW", 100);
            data.sizePercentH = json.optInt("sizePercentH", 100);
        } else if (json.has("boxWidth")) {
            data.sizePercentW = Math.round(json.optInt("boxWidth", 179) * 100f / 179f);
            data.sizePercentH = Math.round(json.optInt("boxHeight", 179) * 100f / 179f);
        }
        data.customTextColor = json.optBoolean("customTextColor", false);
        data.textColor = json.optInt("textColor", 0xDE000000);
        data.imageAspect = (float) json.optDouble("imageAspect", 0d);
        data.cornerRadiusDp = (float) json.optDouble("cornerRadius", WidgetUi.DEFAULT_RADIUS_DP);
        data.renderedBoxWidthDp = json.optInt("renderedBoxW", 0);
        data.renderedBoxHeightDp = json.optInt("renderedBoxH", 0);
        data.renderRevision = json.optInt("renderRevision", 0);
        return data;
    }
}
