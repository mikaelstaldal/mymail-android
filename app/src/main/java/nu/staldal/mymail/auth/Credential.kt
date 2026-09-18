package nu.staldal.mymail.auth

/**
 * A username/password pair used for HTTP Basic authentication.
 *
 * [toString] redacts the password so that an accidental log or crash report cannot leak it.
 */
class Credential(val username: String, val password: String) {
    override fun toString(): String = "Credential(username=$username, password=<redacted>)"
}
