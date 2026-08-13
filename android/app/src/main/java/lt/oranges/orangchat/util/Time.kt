package lt.oranges.orangchat.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private val dateTimeFmt = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm", Locale.getDefault())
private val dateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
private val fullDateTimeFmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT)

fun parseInstant(iso: String?): Instant? =
    iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

fun formatTime(iso: String?): String {
    val inst = parseInstant(iso) ?: return ""
    return timeFmt.format(inst.atZone(ZoneId.systemDefault()))
}

fun formatDateTime(iso: String?): String {
    val inst = parseInstant(iso) ?: return ""
    return dateTimeFmt.format(inst.atZone(ZoneId.systemDefault()))
}

fun formatDate(iso: String?): String {
    val inst = parseInstant(iso) ?: return ""
    return dateFmt.format(inst.atZone(ZoneId.systemDefault()))
}

fun formatFullTime(iso: String?): String {
    val inst = parseInstant(iso) ?: return ""
    return fullDateTimeFmt.format(inst.atZone(ZoneId.systemDefault()))
}

private val dayFmt = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.getDefault())

/** Local calendar day, stable to compare two timestamps by. */
fun dayKey(iso: String?): String? {
    val inst = parseInstant(iso) ?: return null
    return inst.atZone(ZoneId.systemDefault()).toLocalDate().toString()
}

/** 0 for today, 1 for yesterday, otherwise the whole-day distance. */
fun daysAgo(iso: String?, nowMs: Long = System.currentTimeMillis()): Long? {
    val inst = parseInstant(iso) ?: return null
    val zone = ZoneId.systemDefault()
    val then = inst.atZone(zone).toLocalDate()
    val now = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return now.toEpochDay() - then.toEpochDay()
}

fun formatDayLabel(iso: String?): String {
    val inst = parseInstant(iso) ?: return ""
    return dayFmt.format(inst.atZone(ZoneId.systemDefault()))
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
private const val MONTH_MS = 30 * DAY_MS
private const val YEAR_MS = 365 * DAY_MS

/** Compact single-unit relative time for list rows: "12h", "4d", "9mo", "2y". */
fun formatShortRelativeTime(iso: String?, nowMs: Long = System.currentTimeMillis()): String? {
    val inst = parseInstant(iso) ?: return null
    val diff = (nowMs - inst.toEpochMilli()).coerceAtLeast(0)
    return when {
        diff < MINUTE_MS -> "now"
        diff < HOUR_MS -> "${diff / MINUTE_MS}m"
        diff < DAY_MS -> "${diff / HOUR_MS}h"
        diff < MONTH_MS -> "${diff / DAY_MS}d"
        diff < YEAR_MS -> "${diff / MONTH_MS}mo"
        else -> "${diff / YEAR_MS}y"
    }
}
