package io.giasinton.countday;

import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * 小组件的广播入口。
 *
 * <p>整个应用只有这一个 BroadcastReceiver，没有 Service、没有 Activity 常驻，
 * 也没有 android:updatePeriodMillis 的周期唤醒。跨天靠 {@link MidnightAlarm}。
 */
public class CountDayWidgetProvider extends BroadcastReceiver {

    /** 每天零点后由 AlarmManager 投递的私有 action。 */
    static final String ACTION_MIDNIGHT = "io.giasinton.countday.action.MIDNIGHT";

    /** 点一下小组件时发来的私有 action（用于识别"连点三下"）。 */
    static final String ACTION_WIDGET_TAP = "io.giasinton.countday.action.WIDGET_TAP";

    /** 连点判定：相邻两下不超过这个间隔才算连击。 */
    private static final long TAP_INTERVAL_MS = 600L;
    private static final int TAP_COUNT_TO_OPEN = 3;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (action == null) {
            return;
        }

        switch (action) {
            case Intent.ACTION_MY_PACKAGE_REPLACED:
            case AppWidgetManager.ACTION_APPWIDGET_UPDATE: {
                int[] ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
                if (ids == null) {
                    ids = AppWidgetManager.getInstance(context)
                            .getAppWidgetIds(new ComponentName(context, CountDayWidgetProvider.class));
                }
                for (int id : ids) {
                    WidgetUi.updateWidget(context, id);
                }
                MidnightAlarm.schedule(context);
                break;
            }
            case AppWidgetManager.ACTION_APPWIDGET_DELETED: {
                int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                        AppWidgetManager.INVALID_APPWIDGET_ID);
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    WidgetStore.delete(context, id);
                    ImageStore.delete(context, id);
                }
                break;
            }
            case AppWidgetManager.ACTION_APPWIDGET_ENABLED: {
                MidnightAlarm.schedule(context);
                break;
            }
            case AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED: {
                // 用户拉伸了小组件：尺寸变了，背景圆角和图片分辨率都要重算
                int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                        AppWidgetManager.INVALID_APPWIDGET_ID);
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    WidgetUi.updateWidget(context, id);
                }
                break;
            }
            case AppWidgetManager.ACTION_APPWIDGET_DISABLED: {
                MidnightAlarm.cancel(context);
                break;
            }
            case ACTION_WIDGET_TAP: {
                handleWidgetTap(context, intent);
                break;
            }
            case ACTION_MIDNIGHT:
            case Intent.ACTION_TIME_CHANGED:
            case Intent.ACTION_TIMEZONE_CHANGED:
            case Intent.ACTION_DATE_CHANGED:
            case Intent.ACTION_LOCALE_CHANGED: {
                // 日期/时区/语言变化：重算所有实例，并把下一次零点闹钟排好
                WidgetUi.updateAll(context);
                MidnightAlarm.schedule(context);
                break;
            }
            default:
                break;
        }
    }

    /**
     * 数连击：单击/双击什么都不做（避免误触），连点 {@link #TAP_COUNT_TO_OPEN} 下才打开配置页。
     *
     * <p>状态存在 SharedPreferences 里而不是静态变量 —— 广播之间进程可能被回收。
     */
    private void handleWidgetTap(Context context, Intent intent) {
        int widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long last = WidgetStore.loadTapTime(context, widgetId);
        int count = WidgetStore.loadTapCount(context, widgetId);
        count = (now - last <= TAP_INTERVAL_MS) ? count + 1 : 1;
        WidgetStore.saveTap(context, widgetId, count, now);

        if (count >= TAP_COUNT_TO_OPEN) {
            WidgetStore.saveTap(context, widgetId, 0, now);
            Intent edit = ConfigActivity.editIntent(context, widgetId);
            edit.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(edit);
            } catch (RuntimeException e) {
                // Android 10+ 有后台启动 Activity 的限制；真被拦了就只剩长按 ->「设置」这条路
                android.util.Log.w("CountDay", "连击打开配置页被系统拦截", e);
            }
        }
    }
}
