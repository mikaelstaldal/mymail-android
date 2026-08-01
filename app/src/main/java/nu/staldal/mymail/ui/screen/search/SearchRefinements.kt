package nu.staldal.mymail.ui.screen.search

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Hard cap on the address filters, matching `maxLength: 200` in the OpenAPI spec. */
const val ADDRESS_FILTER_MAX_LENGTH = 200

/**
 * Everything that narrows a search beyond the query text itself. Kept as one value so that the
 * form state and the last-submitted state stay in step, and so pagination re-runs exactly what
 * was submitted rather than whatever the form holds right now.
 */
data class SearchRefinements(
    val folderId: Long? = null,
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,
    val fromAddr: String = "",
    val toAddr: String = "",
) {
    /**
     * Maps the refinements onto the `GET /messages/search` query parameters. A parameter that is
     * `null` here is omitted from the request entirely — blank address filters must not be sent
     * as an empty value.
     */
    fun toQueryParams(zone: ZoneId = ZoneId.systemDefault()): SearchQueryParams = SearchQueryParams(
        folderId = folderId,
        dateFrom = dateFrom?.let { rfc3339StartOfDay(it, zone) },
        dateTo = dateTo?.let { rfc3339StartOfDay(it.plusDays(1), zone) },
        fromAddr = fromAddr.trim().ifBlank { null },
        toAddr = toAddr.trim().ifBlank { null },
    )
}

data class SearchQueryParams(
    val folderId: Long?,
    val dateFrom: String?,
    val dateTo: String?,
    val fromAddr: String?,
    val toAddr: String?,
)

private fun rfc3339StartOfDay(date: LocalDate, zone: ZoneId): String =
    ZonedDateTime.of(date, LocalTime.MIDNIGHT, zone)
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
