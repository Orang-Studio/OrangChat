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
