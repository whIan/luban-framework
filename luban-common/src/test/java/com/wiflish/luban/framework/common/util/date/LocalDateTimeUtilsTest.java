package com.wiflish.luban.framework.common.util.date;

import cn.hutool.core.date.DateUtil;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author wiflish
 * @since 2024-06-14
 */
class LocalDateTimeUtilsTest {

    @Test
    void getTodayRange() {
        LocalDateTime[] todayRange = LocalDateTimeUtils.getTodayRange();

        LocalDateTime now = LocalDateTime.now();
        assertEquals(DateUtil.format(now.withHour(0).withMinute(0).withSecond(0).withNano(0), "yyyy-MM-dd HH:mm:ss"), DateUtil.format(todayRange[0], "yyyy-MM-dd HH:mm:ss"));
        assertEquals(DateUtil.format(now.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0), "yyyy-MM-dd HH:mm:ss"), DateUtil.format(todayRange[1], "yyyy-MM-dd HH:mm:ss"));
    }
}