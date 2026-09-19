package io.giasinton.countday;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 日期计算工具。
 *
 * <p>所有"日期"都以<b>本地日期的 epochDay</b>（1970-01-01 起的天数）保存，
 * 这样天然与时分秒、时区偏移无关，不会出现"差一天"的问题。
 */
final class DayMath {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private DayMath() {
    }

    /** 今天的本地日期。 */
    static long todayEpochDay() {
        return LocalDate.now().toEpochDay();
    }

    /** 把年月日转成 epochDay。 */
    static long epochDayOf(int year, int month, int dayOfMonth) {
        return LocalDate.of(year, month, dayOfMonth).toEpochDay();
    }

    /** 拆成 {year, month, dayOfMonth}，月份从 1 开始（方便直接喂给 DatePicker）。 */
    static int[] ymd(long epochDay) {
        LocalDate date = LocalDate.ofEpochDay(epochDay);
        return new int[] { date.getYear(), date.getMonthValue(), date.getDayOfMonth() };
    }

    static String format(long epochDay) {
        return LocalDate.ofEpochDay(epochDay).format(DATE_FORMAT);
    }

    /** 下一个本地零点之后 3 秒对应的时间戳，用于跨天自动刷新。 */
    static long nextMidnightMillis() {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime next = now.toLocalDate().plusDays(1).atStartOfDay(now.getZone());
        return next.toInstant().toEpochMilli() + 3000L;
    }
}
