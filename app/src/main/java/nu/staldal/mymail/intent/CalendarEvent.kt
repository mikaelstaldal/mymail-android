package nu.staldal.mymail.intent

import android.content.Intent
import android.provider.CalendarContract
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class IcsEvent(
    val title: String?,
    val description: String?,
    val location: String?,
    val startMillis: Long?,
    val endMillis: Long?,
    val allDay: Boolean,
)

fun isCalendarAttachment(contentType: String, filename: String): Boolean =
    contentType.substringBefore(";").trim().equals("text/calendar", ignoreCase = true) ||
        filename.endsWith(".ics", ignoreCase = true)

fun parseIcsEvent(content: String): IcsEvent? {
    val lines = unfoldIcsLines(content)
    val veventStart = lines.indexOfFirst { it.equals("BEGIN:VEVENT", ignoreCase = true) }
    val veventEnd = lines.indexOfFirst { it.equals("END:VEVENT", ignoreCase = true) }
    if (veventStart < 0 || veventEnd < 0 || veventEnd <= veventStart) return null

    var title: String? = null
    var description: String? = null
    var location: String? = null
    var start: Pair<Long, Boolean>? = null
    var end: Pair<Long, Boolean>? = null

    for (i in (veventStart + 1) until veventEnd) {
        val property = parseIcsLine(lines[i]) ?: continue
        when (property.name) {
            "SUMMARY" -> title = unescapeIcsText(property.value)
            "DESCRIPTION" -> description = unescapeIcsText(property.value)
            "LOCATION" -> location = unescapeIcsText(property.value)
            "DTSTART" -> start = parseIcsDateTime(property)
            "DTEND" -> end = parseIcsDateTime(property)
        }
    }

    if (title == null && start == null) return null

    return IcsEvent(
        title = title,
        description = description,
        location = location,
        startMillis = start?.first,
        endMillis = end?.first,
        allDay = start?.second ?: false,
    )
}

fun buildCalendarInsertIntent(event: IcsEvent): Intent =
    Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        event.title?.let { putExtra(CalendarContract.Events.TITLE, it) }
        event.description?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
        event.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        event.startMillis?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
        event.endMillis?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
        putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, event.allDay)
    }

private data class IcsProperty(val name: String, val params: Map<String, String>, val value: String)

private val ICS_FOLD_REGEX = Regex("(\\r\\n|\\r|\\n)[ \\t]")
private val ICS_LINE_BREAK_REGEX = Regex("\\r\\n|\\r|\\n")

private fun unfoldIcsLines(content: String): List<String> =
    content.replace(ICS_FOLD_REGEX, "").split(ICS_LINE_BREAK_REGEX).filter { it.isNotBlank() }

private fun parseIcsLine(line: String): IcsProperty? {
    val colonIndex = line.indexOf(':')
    if (colonIndex < 0) return null

    val nameAndParams = line.substring(0, colonIndex).split(';')
    val name = nameAndParams[0].uppercase()
    val params = mutableMapOf<String, String>()
    for (i in 1 until nameAndParams.size) {
        val equalsIndex = nameAndParams[i].indexOf('=')
        if (equalsIndex > 0) {
            params[nameAndParams[i].substring(0, equalsIndex).uppercase()] = nameAndParams[i].substring(equalsIndex + 1)
        }
    }

    return IcsProperty(name, params, line.substring(colonIndex + 1))
}

private val ICS_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE
private val ICS_DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

private fun parseIcsDateTime(property: IcsProperty): Pair<Long, Boolean>? {
    val value = property.value.trim()
    return try {
        if (property.params["VALUE"] == "DATE" || (value.length == 8 && value.all { it.isDigit() })) {
            val date = LocalDate.parse(value, ICS_DATE_FORMAT)
            date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() to true
        } else {
            val isUtc = value.endsWith("Z")
            val localDateTime = LocalDateTime.parse(value.removeSuffix("Z"), ICS_DATE_TIME_FORMAT)
            val zone = if (isUtc) {
                ZoneOffset.UTC
            } else {
                property.params["TZID"]?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
            }
            localDateTime.atZone(zone).toInstant().toEpochMilli() to false
        }
    } catch (_: Exception) {
        null
    }
}

private fun unescapeIcsText(value: String): String {
    val result = StringBuilder()
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '\\' && i + 1 < value.length) {
            when (value[i + 1]) {
                'n', 'N' -> result.append('\n')
                ',' -> result.append(',')
                ';' -> result.append(';')
                '\\' -> result.append('\\')
                else -> result.append(c).append(value[i + 1])
            }
            i += 2
        } else {
            result.append(c)
            i += 1
        }
    }
    return result.toString()
}
