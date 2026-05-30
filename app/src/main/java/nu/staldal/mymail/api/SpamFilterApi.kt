package nu.staldal.mymail.api

import nu.staldal.mymail.model.SpamFilterSettings
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT

interface SpamFilterApi {
    @GET("spam-filter")
    suspend fun getSpamFilterSettings(): SpamFilterSettings

    @PUT("spam-filter")
    suspend fun replaceSpamFilterSettings(@Body settings: SpamFilterSettings): SpamFilterSettings
}
