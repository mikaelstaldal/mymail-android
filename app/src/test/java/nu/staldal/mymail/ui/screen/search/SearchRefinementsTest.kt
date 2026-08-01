package nu.staldal.mymail.ui.screen.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SearchRefinementsTest {

    private val zone = ZoneId.of("Europe/Stockholm")

    @Test
    fun `no refinements sends no parameters`() {
        val params = SearchRefinements().toQueryParams(zone)

        assertNull(params.folderId)
        assertNull(params.dateFrom)
        assertNull(params.dateTo)
        assertNull(params.fromAddr)
        assertNull(params.toAddr)
    }

    @Test
    fun `address filters are trimmed`() {
        val params = SearchRefinements(
            fromAddr = "  Alice@Example.COM ",
            toAddr = "\tbob@example.com\n",
        ).toQueryParams(zone)

        assertEquals("Alice@Example.COM", params.fromAddr)
        assertEquals("bob@example.com", params.toAddr)
    }

    @Test
    fun `blank address filters are omitted rather than sent empty`() {
        val params = SearchRefinements(fromAddr = "   ", toAddr = "").toQueryParams(zone)

        assertNull(params.fromAddr)
        assertNull(params.toAddr)
    }

    @Test
    fun `wildcard characters are passed through as literals`() {
        // The server treats % and _ as literals, so nothing here needs escaping.
        val params = SearchRefinements(fromAddr = "100%_real@example.com").toQueryParams(zone)

        assertEquals("100%_real@example.com", params.fromAddr)
    }

    @Test
    fun `date range covers whole days in the local timezone`() {
        val params = SearchRefinements(
            dateFrom = LocalDate.of(2026, 7, 31),
            dateTo = LocalDate.of(2026, 8, 1),
        ).toQueryParams(zone)

        assertEquals("2026-07-31T00:00:00+02:00", params.dateFrom)
        // Exclusive upper bound: the start of the day after the selected one.
        assertEquals("2026-08-02T00:00:00+02:00", params.dateTo)
    }

    @Test
    fun `folder filter is passed through`() {
        val params = SearchRefinements(folderId = 42L).toQueryParams(zone)

        assertEquals(42L, params.folderId)
    }

    @Test
    fun `refinements compare by value so a resubmitted set is recognised`() {
        val a = SearchRefinements(folderId = 1L, fromAddr = "alice@example.com")
        val b = SearchRefinements(folderId = 1L, fromAddr = "alice@example.com")

        assertEquals(a, b)
    }
}
