package com.mina.legadostudio.web

import java.time.LocalDate
import java.time.ZoneId

/** Pure request rules shared by the trusted log-management bridge. */
object LogManagementRules {
    fun dayWindow(date: String, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(date)) { "日期必须为 YYYY-MM-DD" }
        val day = LocalDate.parse(date)
        return day.atStartOfDay(zone).toInstant().toEpochMilli() to
            (day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1)
    }
    fun exactId(value: String): Long {
        require(Regex("[1-9][0-9]{0,18}").matches(value)) { "需要精确 ID" }
        return value.toLong()
    }
    fun requireComplete(expected: Int, actual: Int) {
        check(expected == actual) { "日志在导出过程中变化，请重试；未导出不完整文件" }
    }
}
