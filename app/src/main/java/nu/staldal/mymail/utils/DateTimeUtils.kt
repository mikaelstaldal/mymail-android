package nu.staldal.mymail.utils

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private val weekdayTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault())
private val monthDayTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.getDefault())
private val monthDayYearFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
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

fun formatMessageDetailDate(date: OffsetDateTime): String {
    val zoneId = ZoneId.systemDefault()
    return date.atZoneSameInstant(zoneId).format(fullDetailFormatter)
}

fun formatFullDateForCopy(date: OffsetDateTime): String {
    return date.toString()
}
