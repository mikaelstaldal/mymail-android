package nu.staldal.mymail.repository

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import nu.staldal.mymail.model.MessageSummary
import java.time.OffsetDateTime

/**
 * The message list reads its density preference from plain prefs. No test sets one, so the stub
 * answers with whatever default the caller passed.
 */
fun mockPrefs(): SharedPreferences = mockk {
    every { getString(any(), any()) } answers { secondArg() }
}

fun messageSummaries(count: Int): List<MessageSummary> = (1..count).map { i ->
    val timestamp = OffsetDateTime.parse("2026-07-31T12:00:00Z")
    MessageSummary(
        id = i,
        folderId = 1,
        messageId = null,
        fromAddr = "sender@example.com",
        toAddr = "me@example.com",
        subject = "Subject $i",
        date = timestamp,
        read = false,
        flagged = false,
        hasAttachments = false,
        sendFailed = false,
        // These belong to the Scheduled and Snoozed folders; an inbox message carries neither.
        sendAt = null,
        snoozedUntil = null,
        createdAt = timestamp,
    )
}

fun searchItems(count: Int): List<MessageSummaryWithSnippet> =
    messageSummaries(count).map { MessageSummaryWithSnippet(summary = it, snippet = "snippet ${it.id}") }
