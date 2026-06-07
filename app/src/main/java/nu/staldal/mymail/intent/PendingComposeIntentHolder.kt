package nu.staldal.mymail.intent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands [ComposeIntentData] from [nu.staldal.mymail.MainActivity] (which receives the incoming
 * send/sendto intent) over to the compose screen's view model, without round-tripping it through
 * string-based navigation arguments.
 */
@Singleton
class PendingComposeIntentHolder @Inject constructor() {

    @Volatile
    private var pending: ComposeIntentData? = null

    fun set(data: ComposeIntentData) {
        pending = data
    }

    fun consume(): ComposeIntentData? {
        val data = pending
        pending = null
        return data
    }
}
