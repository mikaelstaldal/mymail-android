package nu.staldal.mymail.repository

import nu.staldal.mymail.model.MessageSummary

class HttpStatusException(val statusCode: Int, message: String) : RuntimeException(message)

data class ThreadResponse(
    val total: Int,
    val truncated: Boolean,
    val items: List<MessageSummary>,
)

data class MessageSummaryWithSnippet(
    val summary: MessageSummary,
    val snippet: String,
)

data class SearchResult(
    val total: Int,
    val items: List<MessageSummaryWithSnippet>,
)

data class CancelSnoozeResponse(
    val id: Long,
    val folderId: Long,
)

data class ContactListResult(
    val total: Int,
    val items: List<nu.staldal.mymail.model.Contact>,
)
