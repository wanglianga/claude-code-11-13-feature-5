package com.example.rtbf.util;

import java.time.DayOfWeek;
import java.time.LocalDate;

public final class WorkdayUtil {

    private WorkdayUtil() {
    }

    /** 在起点上加 n 个工作日（跳过周六周日；法定假日不在本演示范围内）。 */
    public static LocalDate plusWorkdays(LocalDate start, int workdays) {
        LocalDate d = start;
        int added = 0;
        while (added < workdays) {
            d = d.plusDays(1);
            if (!(d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY)) {
                added++;
            }
        }
        return d;
    }
}
