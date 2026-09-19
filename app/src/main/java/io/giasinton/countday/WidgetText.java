package io.giasinton.countday;

import android.content.Context;

/**
 * 把数据换算成小组件上显示的文字。
 *
 * <p>数字后面不再跟"天/天后/天前"，方向由标题或日期行表达，卡片更干净。
 */
final class WidgetText {

    final String number;
    /** 数字后面的小号单位；"今天"这类情况为空。 */
    final String unit;
    final String dateLine;

    private WidgetText(String number, String unit, String dateLine) {
        this.number = number;
        this.unit = unit;
        this.dateLine = dateLine;
    }

    static WidgetText of(Context context, WidgetData data, long todayEpochDay) {
        long diff = data.dateEpochDay - todayEpochDay; // >0 表示目标在未来
        String days = context.getString(R.string.unit_days);
        if (data.mode == WidgetData.MODE_COUNT_UP) {
            String dateLine = context.getString(R.string.date_line_start, DayMath.format(data.dateEpochDay));
            if (diff == 0) {
                return new WidgetText(context.getString(R.string.today), "", dateLine);
            }
            return new WidgetText(String.valueOf(Math.abs(diff)), days, dateLine);
        }

        String dateLine = context.getString(R.string.date_line_target, DayMath.format(data.dateEpochDay));
        if (diff == 0) {
            return new WidgetText(context.getString(R.string.today), "", dateLine);
        }
        return new WidgetText(String.valueOf(Math.abs(diff)), days, dateLine);
    }
}
