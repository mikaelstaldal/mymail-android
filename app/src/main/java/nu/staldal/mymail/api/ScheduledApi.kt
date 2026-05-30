package nu.staldal.mymail.api

import retrofit2.http.DELETE
import retrofit2.http.Path

interface ScheduledApi {
    @DELETE("scheduled/{id}")
    suspend fun cancelScheduledMessage(@Path("id") id: Long)
}
