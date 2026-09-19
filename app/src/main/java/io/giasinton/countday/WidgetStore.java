package io.giasinton.countday;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 每个小组件实例一份数据，用 {@code widget_<appWidgetId>} 作为 key 存在
 * SharedPreferences 里。不需要数据库，也不需要后台进程。
 */
final class WidgetStore {

    private static final String PREFS_NAME = "countday_widgets";
    private static final String KEY_PREFIX = "widget_";

    private WidgetStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 返回 null 表示这个实例还没配置过。 */
    static WidgetData load(Context context, int widgetId) {
        String raw = prefs(context).getString(KEY_PREFIX + widgetId, null);
        if (raw == null) {
            return null;
        }
        try {
            return WidgetData.fromJson(new JSONObject(raw));
        } catch (JSONException e) {
            return null;
        }
    }

    static void save(Context context, int widgetId, WidgetData data) {
        prefs(context).edit()
                .putString(KEY_PREFIX + widgetId, data.toJson().toString())
                .apply();
    }

    static int loadTapCount(Context context, int widgetId) {
        return prefs(context).getInt("tap_count_" + widgetId, 0);
    }

    static long loadTapTime(Context context, int widgetId) {
        return prefs(context).getLong("tap_time_" + widgetId, 0L);
    }

    static void saveTap(Context context, int widgetId, int count, long time) {
        prefs(context).edit()
                .putInt("tap_count_" + widgetId, count)
                .putLong("tap_time_" + widgetId, time)
                .apply();
    }

    static void delete(Context context, int widgetId) {
        prefs(context).edit().remove(KEY_PREFIX + widgetId).apply();
    }
}
