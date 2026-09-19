package io.giasinton.countday;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * 跨天刷新。
 *
 * <p>没有用任何 Service：只在每天零点后用一个<b>非精确</b>闹钟
 * （{@link AlarmManager#set}）给自己发一次普通广播，广播接收完就结束进程。
 * 非精确闹钟不需要 SCHEDULE_EXACT_ALARM 权限，也不会被系统当成常驻任务。
 */
final class MidnightAlarm {

    private MidnightAlarm() {
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, CountDayWidgetProvider.class);
        intent.setAction(CountDayWidgetProvider.ACTION_MIDNIGHT);
        return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        PendingIntent pending = pendingIntent(context);
        alarmManager.cancel(pending);
        alarmManager.set(AlarmManager.RTC, DayMath.nextMidnightMillis(), pending);
    }

    static void cancel(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.cancel(pendingIntent(context));
        }
    }
}
