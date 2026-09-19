package io.giasinton.countday;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * DayMath 的纯逻辑校验：不依赖 Android，可以直接在电脑上用 JDK 跑。
 *
 * <pre>bash tools/run-tests.sh</pre>
 */
public class DayMathCheck {

    private static int failed;

    private static void check(String name, Object actual, Object expected) {
        boolean ok = expected.equals(actual);
        if (!ok) {
            failed++;
        }
        System.out.printf("%-46s %s  actual=%s expected=%s%n",
                name, ok ? "PASS" : "FAIL", actual, expected);
    }

    public static void main(String[] args) {
        check("epochDayOf(1970,1,1)", DayMath.epochDayOf(1970, 1, 1), 0L);
        check("epochDayOf(2026,9,16)",
                DayMath.epochDayOf(2026, 9, 16), LocalDate.of(2026, 9, 16).toEpochDay());
        check("format(epochDayOf(2026,9,16))",
                DayMath.format(DayMath.epochDayOf(2026, 9, 16)), "2026-09-16");

        long leap = DayMath.epochDayOf(2024, 2, 29);
        int[] ymd = DayMath.ymd(leap);
        check("ymd 往返 (2024-02-29)", ymd[0] + "-" + ymd[1] + "-" + ymd[2], "2024-2-29");
        check("ymd 往返后再算回 epochDay", DayMath.epochDayOf(ymd[0], ymd[1], ymd[2]), leap);

        long today = DayMath.epochDayOf(2026, 9, 16);
        check("距离 2026-10-01 还有多少天", DayMath.epochDayOf(2026, 10, 1) - today, 15L);
        check("同一天相差 0 天", today - today, 0L);
        check("从 2026-01-01 起已经过去多少天", today - DayMath.epochDayOf(2026, 1, 1), 258L);
        check("闰年 2024-02-28 -> 2024-03-01",
                DayMath.epochDayOf(2024, 3, 1) - DayMath.epochDayOf(2024, 2, 28), 2L);
        check("跨年 2025-12-31 -> 2026-01-01",
                DayMath.epochDayOf(2026, 1, 1) - DayMath.epochDayOf(2025, 12, 31), 1L);

        long next = DayMath.nextMidnightMillis();
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime nextTime = Instant.ofEpochMilli(next).atZone(zone);
        check("下次刷新落在明天",
                nextTime.toLocalDate().toString(), LocalDate.now(zone).plusDays(1).toString());
        check("下次刷新在 24 小时内",
                next - System.currentTimeMillis() <= 24L * 3600 * 1000, true);
        check("下次刷新不早于今天结束",
                next > LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), true);
        System.out.println("    下次刷新时间点 = " + nextTime);

        System.out.println(failed == 0 ? "\n全部通过" : "\n失败 " + failed + " 项");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
