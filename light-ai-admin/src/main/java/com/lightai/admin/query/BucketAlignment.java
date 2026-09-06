package com.lightai.admin.query;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 查询桶对齐工具（BE-034/035 共用）。
 * 查询时间必须桶对齐：HOUR 按配置时区自然小时截断，DAY 按配置时区自然日（本地日历，
 * DST 安全）；范围左闭右开，终点向上对齐到桶边界，保证与原始范围重叠的桶不被遗漏。
 */
public final class BucketAlignment {

    private BucketAlignment() {
    }

    public static OffsetDateTime alignStart(OffsetDateTime start, ZoneId zone, boolean hour) {
        ZonedDateTime local = start.atZoneSameInstant(zone);
        return hour ? local.truncatedTo(ChronoUnit.HOURS).toOffsetDateTime()
                : local.toLocalDate().atStartOfDay(zone).toOffsetDateTime();
    }

    public static OffsetDateTime alignEnd(OffsetDateTime end, ZoneId zone, boolean hour) {
        ZonedDateTime local = end.atZoneSameInstant(zone);
        if (hour) {
            OffsetDateTime truncated = local.truncatedTo(ChronoUnit.HOURS).toOffsetDateTime();
            return truncated.isBefore(local.toOffsetDateTime()) ? truncated.plusHours(1) : truncated;
        }
        OffsetDateTime midnight = local.toLocalDate().atStartOfDay(zone).toOffsetDateTime();
        return midnight.isBefore(local.toOffsetDateTime()) ? midnight.plusDays(1) : midnight;
    }

    public static OffsetDateTime bucketEnd(OffsetDateTime bucketStart, ZoneId zone, boolean hour) {
        ZonedDateTime local = bucketStart.atZoneSameInstant(zone);
        return hour ? local.plusHours(1).toOffsetDateTime()
                : local.toLocalDate().plusDays(1).atStartOfDay(zone).toOffsetDateTime();
    }

    /** 起点对齐后的连续桶序列，左闭右开，无重复。 */
    public static List<OffsetDateTime> iterateBuckets(OffsetDateTime alignedStart,
                                                      OffsetDateTime alignedEnd,
                                                      ZoneId zone, boolean hour) {
        List<OffsetDateTime> buckets = new ArrayList<>();
        ZonedDateTime cursor = alignedStart.atZoneSameInstant(zone);
        while (cursor.toOffsetDateTime().isBefore(alignedEnd)) {
            buckets.add(cursor.toOffsetDateTime());
            cursor = hour ? cursor.plusHours(1)
                    : cursor.toLocalDate().plusDays(1).atStartOfDay(zone);
        }
        return buckets;
    }

    /** 配置时区解析失败回退 UTC，不虚构本地化口径。 */
    public static ZoneId safeZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.of("UTC");
        }
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            return ZoneId.of("UTC");
        }
    }
}
