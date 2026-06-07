package nu.staldal.mymail.intent

import android.content.Intent
import android.net.Uri
import android.os.Build

data class ComposeIntentData(
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String? = null,
    val body: String? = null,
    val attachmentUris: List<Uri> = emptyList(),
)

fun parseComposeIntent(intent: Intent): ComposeIntentData? = when (intent.action) {
    Intent.ACTION_SENDTO -> intent.data?.let { parseMailtoUri(it) }
    Intent.ACTION_SEND -> parseSendIntent(intent, multiple = false)
    Intent.ACTION_SEND_MULTIPLE -> parseSendIntent(intent, multiple = true)
    else -> null
}

private fun parseMailtoUri(uri: Uri): ComposeIntentData? {
    if (!uri.scheme.equals("mailto", ignoreCase = true)) return null

    val schemeSpecificPart = uri.schemeSpecificPart ?: ""
    val queryIndex = schemeSpecificPart.indexOf('?')
    val addressPart = if (queryIndex >= 0) schemeSpecificPart.substring(0, queryIndex) else schemeSpecificPart
    val queryPart = if (queryIndex >= 0) schemeSpecificPart.substring(queryIndex + 1) else ""

    val to = mutableListOf<String>()
    to += splitAddresses(addressPart)
    var cc = emptyList<String>()
    var bcc = emptyList<String>()
    var subject: String? = null
    var body: String? = null

    for (param in queryPart.split("&")) {
        val eqIndex = param.indexOf('=')
        if (eqIndex < 0) continue
        val key = param.substring(0, eqIndex).lowercase()
        val value = runCatching { Uri.decode(param.substring(eqIndex + 1)) }.getOrDefault("")
        when (key) {
            "to" -> to += splitAddresses(value)
            "cc" -> cc = splitAddresses(value)
            "bcc" -> bcc = splitAddresses(value)
            "subject" -> subject = value
            "body" -> body = value
        }
    }

    return ComposeIntentData(to = to, cc = cc, bcc = bcc, subject = subject, body = body)
}

private fun splitAddresses(raw: String): List<String> =
    raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

private fun parseSendIntent(intent: Intent, multiple: Boolean): ComposeIntentData {
    val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
    val body = intent.getStringExtra(Intent.EXTRA_TEXT)
    val to = intent.getStringArrayExtra(Intent.EXTRA_EMAIL)?.toList() ?: emptyList()
    val cc = intent.getStringArrayExtra(Intent.EXTRA_CC)?.toList() ?: emptyList()
    val bcc = intent.getStringArrayExtra(Intent.EXTRA_BCC)?.toList() ?: emptyList()

    val attachmentUris = if (multiple) getStreamUris(intent) else listOfNotNull(getStreamUri(intent))

    return ComposeIntentData(
        to = to,
        cc = cc,
        bcc = bcc,
        subject = subject,
        body = body,
        attachmentUris = attachmentUris,
    )
}

@Suppress("DEPRECATION")
private fun getStreamUri(intent: Intent): Uri? =
    if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }

@Suppress("DEPRECATION")
private fun getStreamUris(intent: Intent): List<Uri> =
    if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
    } else {
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
    }
