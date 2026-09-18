package nu.staldal.mymail.utils

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private val weekdayTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault())
private val monthDayTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.getDefault())
private val monthDayYearFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
private val monthDayYearTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy, HH:mm", Locale.getDefault())
private val fullDetailFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy, HH:mm z", Locale.getDefault())

fun formatMessageListDate(date: OffsetDateTime): String {
    val now = OffsetDateTime.now()
    val zoneId = ZoneId.systemDefault()

    val localNow = now.atZoneSameInstant(zoneId).toLocalDateTime()
    val localDate = date.atZoneSameInstant(zoneId).toLocalDateTime()

    val minutesAgo = ChronoUnit.MINUTES.between(localDate, localNow)
    if (minutesAgo < 1) {
        return "just now"
    }

    if (minutesAgo < 60) {
        return "$minutesAgo min ago"
    }

    val todayDate = localNow.toLocalDate()
    val msgDate = localDate.toLocalDate()

    if (msgDate == todayDate) {
        return localDate.format(timeFormatter)
    }

    val yesterdayDate = todayDate.minusDays(1)
    if (msgDate == yesterdayDate) {
        return "Yesterday ${localDate.format(timeFormatter)}"
    }

    val daysAgo = ChronoUnit.DAYS.between(msgDate, todayDate)
    if (daysAgo in 2..6) {
        return localDate.format(weekdayTimeFormatter)
    }

    if (msgDate.year == todayDate.year) {
        return localDate.format(monthDayTimeFormatter)
    }

    return localDate.format(monthDayYearFormatter)
}

/**
 * Formats a `send_at` or `snoozed_until` value for the Scheduled and Snoozed listings.
 *
 * These normally lie in the *future*, which is exactly what [formatMessageListDate] cannot say: it
 * measures elapsed time, so a future value gives it a negative difference and every scheduled send
 * would read as "just now". A past value is still formatted rather than treated as impossible —
 * the scheduler polls once a minute, so a due message sits in the folder with its time already
 * behind it, and a send that keeps failing is retried with the original time left in place.
 *
 * The time of day is kept at every distance, because when a message goes out is the point of the
 * column rather than incidental to it. [now] is a parameter so the ladder can be tested.
 */
fun formatScheduleDate(date: OffsetDateTime, now: OffsetDateTime = OffsetDateTime.now()): String {
    val zoneId = ZoneId.systemDefault()
    val localNow = now.atZoneSameInstant(zoneId).toLocalDateTime()
    val localDate = date.atZoneSameInstant(zoneId).toLocalDateTime()

    // Positive is the future here — the opposite sign to formatMessageListDate. Measured in
    // seconds, because MINUTES.between truncates towards zero and would hand the half-minute just
    // past as a 0 that reads as the future. The sign picks the wording and the magnitude the
    // number, separately.
    val seconds = ChronoUnit.SECONDS.between(localNow, localDate)
    if (seconds > -3600 && seconds < 3600) {
        // Floored, so the last seconds before the hour do not read as "60 min", and floored to at
        // least 1 so the minute either side of now is not "0 min".
        val magnitude = maxOf(1, abs(seconds) / 60)
        return if (seconds >= 0) "in $magnitude min" else "$magnitude min ago"
    }

    val todayDate = localNow.toLocalDate()
    val msgDate = localDate.toLocalDate()
    val time = localDate.format(timeFormatter)

    // Positive is the past, matching formatMessageListDate's daysAgo.
    val daysAgo = ChronoUnit.DAYS.between(msgDate, todayDate)
    when (daysAgo) {
        0L -> return "Today $time"
        -1L -> return "Tomorrow $time"
        1L -> return "Yesterday $time"
    }

    // Symmetric on purpose: a send_at days behind is what a scheduled send that keeps failing looks
    // like, and it deserves the same named day a pending one gets. Beyond a week the weekday stops
    // identifying a day.
    if (daysAgo in -6..6) {
        return localDate.format(weekdayTimeFormatter)
    }

    if (msgDate.year == todayDate.year) {
        return localDate.format(monthDayTimeFormatter)
    }

    return localDate.format(monthDayYearTimeFormatter)
}

fun formatMessageDetailDate(date: OffsetDateTime): String {
    val zoneId = ZoneId.systemDefault()
    return date.atZoneSameInstant(zoneId).format(fullDetailFormatter)
}

fun formatFullDateForCopy(date: OffsetDateTime): String {
    return date.toString()
}
