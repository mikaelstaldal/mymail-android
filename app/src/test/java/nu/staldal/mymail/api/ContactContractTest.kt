package nu.staldal.mymail.api

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nu.staldal.mymail.model.Contact
import nu.staldal.mymail.model.ContactsPostRequest
import org.junit.Assert.*
import org.junit.Test
import org.openapitools.client.infrastructure.Serializer

class ContactContractTest {
    private val json = Json {
        serializersModule = Serializer.kotlinxSerializationAdapters
        ignoreUnknownKeys = true
    }

    @Test fun `contact responses retain whether the user saved the contact`() {
        listOf(true, false).forEach { explicit ->
            val contact = json.decodeFromString<Contact>(
                """{"id":1,"address":"sender@example.org","name":"Sender","explicit":$explicit,
                    "created_at":"2026-10-21T12:00:00Z","updated_at":"2026-10-21T12:00:00Z"}"""
            )
            assertEquals(explicit, contact.explicit)
        }
    }

    @Test fun `contact writes leave the read only explicit flag to the server`() {
        val request = json.encodeToString(ContactsPostRequest(address = "sender@example.org", name = "Sender"))
        assertFalse(request.contains("explicit"))
        assertTrue(request.contains("sender@example.org"))
    }
}
