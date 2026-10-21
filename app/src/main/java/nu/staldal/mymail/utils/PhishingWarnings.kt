package nu.staldal.mymail.utils

import java.net.IDN
import nu.staldal.mymail.model.MessageWarning
import org.apache.james.mime4j.field.address.DefaultAddressParser

// Presentation only: the server decides which signals apply.
fun warningText(warning: MessageWarning, abbreviateLinks: Boolean = false): String = with(warning) {
    when (kind.value) {
        "auth_fail" -> "Sender authentication failed: ${failedChecks.orEmpty().joinToString { if (it.value == "dkim_alignment") "DKIM alignment" else it.value.uppercase() }}."
        "malformed_sender" -> "The sender address is malformed or ambiguous."
        "display_name_address" -> "The sender's display name contains a different address or domain: ${visibleFormatCharacters(address.orEmpty())}."
        "lookalike_domain" -> "Lookalike domain: ${domain.orEmpty()} (${punycode.orEmpty()})" +
            (resembles?.let { ", resembles $it" } ?: "") + "."
        "reply_to_mismatch" -> "Replies go to a different domain: ${visibleFormatCharacters(address.orEmpty())}."
        "first_time_sender" -> "You have never sent mail to this address."
        "deceptive_link" -> "Deceptive link: ${warningLinkText(text.orEmpty(), abbreviateLinks)} → ${warningLinkText(href.orEmpty(), abbreviateLinks)}" +
            (decodedHost?.let { " (host: $it)" } ?: "")
        "risky_attachment" -> "Risky attachment: ${filename.orEmpty()} (${when (reason?.value) {
            "double_extension" -> "double file extension"
            "executable_extension" -> "executable file extension"
            "executable_content_type" -> "executable file type"
            else -> "potentially dangerous file"
        }})."
        else -> "Message warning: ${kind.value}"
    }
}

private fun warningLinkText(value: String, abbreviate: Boolean): String =
    if (abbreviate && value.length > 60) value.take(59) + "…" else value

fun orderedWarnings(warnings: List<MessageWarning>): List<MessageWarning> {
    val order = listOf("auth_fail", "malformed_sender", "display_name_address", "lookalike_domain", "reply_to_mismatch", "deceptive_link", "risky_attachment", "first_time_sender")
    return warnings.sortedBy { order.indexOf(it.kind.value).let { index -> if (index < 0) Int.MAX_VALUE else index } }
}

data class SenderDisplay(val name: String?, val address: String)

/** Unmatched server attachment warnings conservatively apply to every attachment. */
fun attachmentWarning(warnings: List<MessageWarning>, filenames: List<String>, filename: String): MessageWarning? =
    warnings.firstOrNull {
        it.kind == MessageWarning.Kind.risky_attachment && (it.filename == filename || it.filename !in filenames)
    }

fun senderDisplays(from: String): List<SenderDisplay> = try {
    val mailboxes = DefaultAddressParser.DEFAULT.parseAddressList(from).flatten()
    if (mailboxes.isEmpty()) unparsedSender(from)
    else mailboxes.map { mailbox ->
        val domain = mailbox.domain.orEmpty()
        // IDN normalization can silently discard format controls from malformed domains.
        val hasFormatCharacters = domain.codePoints().anyMatch(::isFormatCharacter)
        val decoded = if (hasFormatCharacters) domain else IDN.toUnicode(domain)
        val local = mailbox.localPart.orEmpty()
        // Preserve the distinction between a quoted local part and address-list punctuation.
        val renderedLocal = if (local.matches(Regex("[A-Za-z0-9!#\$%&'*+/=?^_`{|}~.-]+"))) local
            else "\"" + local.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val address = renderedLocal + if (domain.isNotEmpty()) "@$decoded" else ""
        val displayedAddress = if (!hasFormatCharacters && (decoded != domain || domain.any { it.code > 127 })) {
            "$address (internationalized domain: ${IDN.toASCII(domain)})"
        } else address
        SenderDisplay(
            mailbox.name?.takeIf { it.isNotBlank() }?.let(::visibleFormatCharacters),
            visibleFormatCharacters(displayedAddress),
        )
    }
} catch (_: Exception) {
    unparsedSender(from)
}

private fun unparsedSender(from: String): List<SenderDisplay> =
    listOf(SenderDisplay(null, if (from.isBlank()) "(no sender)" else "Unparsed From header: ${visibleFormatCharacters(from)}"))

/** Expose Unicode format controls without changing the underlying address used for replies. */
internal fun visibleFormatCharacters(value: String): String = buildString {
    value.codePoints().forEach { codePoint ->
        if (isFormatCharacter(codePoint)) {
            append("[U+")
            append(codePoint.toString(16).uppercase().padStart(4, '0'))
            append(']')
        } else {
            appendCodePoint(codePoint)
        }
    }
}

private fun isFormatCharacter(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
    Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> true
    else -> false
}
