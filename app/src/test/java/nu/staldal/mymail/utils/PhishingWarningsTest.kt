package nu.staldal.mymail.utils

import nu.staldal.mymail.model.MessageWarning
import org.junit.Assert.*
import org.junit.Test

class PhishingWarningsTest {
    @Test fun `all server kinds have plain language descriptions`() {
        val expected = mapOf(
            MessageWarning.Kind.auth_fail to "Sender authentication failed:",
            MessageWarning.Kind.display_name_address to "The sender's display name contains a different address or domain:",
            MessageWarning.Kind.lookalike_domain to "Lookalike domain:",
            MessageWarning.Kind.reply_to_mismatch to "Replies go to a different domain:",
            MessageWarning.Kind.first_time_sender to "You have never sent mail to this address.",
            MessageWarning.Kind.deceptive_link to "Deceptive link:",
            MessageWarning.Kind.risky_attachment to "Risky attachment:",
        )
        assertEquals(MessageWarning.Kind.entries.toSet(), expected.keys)
        expected.forEach { (kind, text) -> assertTrue(warningText(MessageWarning(kind)).startsWith(text)) }
    }

    @Test fun `deceptive link includes text and full destination`() {
        val text = warningText(MessageWarning(MessageWarning.Kind.deceptive_link,
            text = "bank.example", href = "https://evil.example/full?x=1", decodedHost = "evil.example"))
        assertTrue(text.contains("bank.example → https://evil.example/full?x=1"))
        assertTrue(text.contains("host: evil.example"))
    }

    @Test fun `long deceptive links are abbreviated while full tooltip text is preserved`() {
        val label = "https://bank.example/" + "a".repeat(100)
        val destination = "https://evil.example/" + "b".repeat(100)
        val warning = MessageWarning(MessageWarning.Kind.deceptive_link,
            text = label, href = destination, decodedHost = "evil.example")
        assertEquals("Deceptive link: ${label.take(59)}… → ${destination.take(59)}… (host: evil.example)",
            warningText(warning, abbreviateLinks = true))
        assertEquals("Deceptive link: $label → $destination (host: evil.example)", warningText(warning))
    }

    @Test fun `short deceptive links are unchanged`() {
        val warning = MessageWarning(MessageWarning.Kind.deceptive_link, text = "a".repeat(60), href = "https://evil.example")
        assertEquals(warningText(warning), warningText(warning, abbreviateLinks = true))
    }

    @Test fun `attachment reasons are readable`() {
        MessageWarning.Reason.entries.forEach {
            val text = warningText(MessageWarning(MessageWarning.Kind.risky_attachment, filename = "invoice.exe", reason = it))
            assertTrue(text.contains("invoice.exe"))
            assertFalse(text.contains(it.value))
        }
    }

    @Test fun `authentication negatives precede first contact`() {
        val warnings = listOf(MessageWarning(MessageWarning.Kind.first_time_sender), MessageWarning(MessageWarning.Kind.auth_fail))
        assertEquals(MessageWarning.Kind.auth_fail, orderedWarnings(warnings).first().kind)
    }

    @Test fun `quoted display name is separate from actual sender`() {
        assertEquals(SenderDisplay("Bank, Inc", "sender@example.org"), senderDisplays("\"Bank, Inc\" <sender@example.org>").single())
        assertEquals(SenderDisplay(null, "sender@example.org"), senderDisplays("sender@example.org").single())
    }

    @Test fun `server decoded unicode display name is separate from address`() {
        assertEquals(listOf(SenderDisplay("Mikael Ståldal", "mikaelstaldal@gmail.com")),
            senderDisplays("Mikael Ståldal <mikaelstaldal@gmail.com>"))
    }

    @Test fun `decoded unicode names preserve all following addresses`() {
        assertEquals(listOf(SenderDisplay("张三", "first@example.org"), SenderDisplay("Åsa", "second@example.org")),
            senderDisplays("张三 <first@example.org>, Åsa <second@example.org>"))
    }

    @Test fun `unicode names still require an actual mailbox address`() {
        assertTrue(senderDisplays("Åsa <").single().address.startsWith("Unparsed From header:"))
    }

    @Test fun `internationalized domains are decoded and marked`() {
        val sender = senderDisplays("Bank <sender@xn--bcher-kva.example>").single()
        assertEquals("Bank", sender.name)
        assertEquals("sender@bücher.example (internationalized domain: xn--bcher-kva.example)", sender.address)
    }

    @Test fun `multiple senders retain their name address pairing`() {
        assertEquals(listOf(SenderDisplay("One", "one@example.org"), SenderDisplay("Two", "two@example.org")),
            senderDisplays("One <one@example.org>, Two <two@example.org>"))
    }

    @Test fun `quoted local parts remain quoted`() {
        assertEquals("\"one two\"@example.org", senderDisplays("Name <\"one two\"@example.org>").single().address)
    }

    @Test fun `unparsed headers are explicitly labelled`() {
        assertTrue(senderDisplays("<").single().address.startsWith("Unparsed From header:"))
    }

    @Test fun `unmatched attachment warning still requires confirmation`() {
        val warning = MessageWarning(MessageWarning.Kind.risky_attachment, filename = "other.exe")
        assertEquals(warning, attachmentWarning(listOf(warning), listOf("actual.exe"), "actual.exe"))
        assertNull(attachmentWarning(listOf(warning), listOf("other.exe", "safe.txt"), "safe.txt"))
        assertEquals(warning, attachmentWarning(listOf(warning), listOf("other.exe"), "other.exe"))
    }
}
