package nu.staldal.mymail.auth

/**
 * A username/password pair used for HTTP Basic authentication.
 *
 * [toString] redacts both halves — the username names a mail account — so that an accidental log
 * or crash report cannot leak either.
 */
class Credential(val username: String, val password: String) {
    override fun toString(): String = "Credential(<redacted>)"
}

/**
 * A [Credential] together with the pw entry it came from.
 *
 * The pairing is what keeps a credential fetched for one entry from being sent to a server
 * configured for another: every reader asks for the entry it wants by name.
 */
class FetchedCredential(val entryName: String, val credential: Credential) {
    fun isFor(entryName: String?): Boolean =
        entryName != null && entryName.trim() == this.entryName.trim()

    override fun toString(): String = "FetchedCredential(entryName=$entryName, credential=$credential)"
}
