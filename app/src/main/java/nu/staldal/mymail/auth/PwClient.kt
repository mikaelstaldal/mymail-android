package nu.staldal.mymail.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Contract for fetching one credential from the separately installed pw app.
 *
 * The action, extra names and result semantics are documented in `../pw-android/INTEGRATION.md`;
 * the constants below are duplicated from that contract and must be kept in step with it.
 */
object PwClient {
    private const val PACKAGE = "nu.staldal.pw"
    private const val ACTION_FETCH = "nu.staldal.pw.action.FETCH_PASSWORD"
    private const val EXTRA_NAME = "nu.staldal.pw.extra.NAME"
    private const val EXTRA_USERNAME = "nu.staldal.pw.extra.RESULT_USERNAME"
    private const val EXTRA_PASSWORD = "nu.staldal.pw.extra.RESULT_PASSWORD"

    /** Explicit intent — pinning the package keeps another app from claiming the action. */
    fun createFetchIntent(entryName: String): Intent =
        Intent(ACTION_FETCH)
            .setPackage(PACKAGE)
            .putExtra(EXTRA_NAME, entryName)

    fun isAvailable(context: Context): Boolean =
        context.packageManager.resolveActivity(
            createFetchIntent("availability-check"),
            PackageManager.MATCH_DEFAULT_ONLY,
        ) != null

    /** Any result code other than [Activity.RESULT_OK] means that pw returned no credential. */
    fun credentialFromResult(resultCode: Int, data: Intent?): Credential? {
        if (resultCode != Activity.RESULT_OK) return null
        return credentialFromExtras(
            username = data?.getStringExtra(EXTRA_USERNAME),
            password = data?.getStringExtra(EXTRA_PASSWORD),
        )
    }

    /** An entry without a username is unusable for Basic auth; an empty password is legitimate. */
    fun credentialFromExtras(username: String?, password: String?): Credential? {
        if (username.isNullOrBlank() || password == null) return null
        return Credential(username, password)
    }
}
