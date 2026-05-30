package nu.staldal.mymail.api

import nu.staldal.mymail.model.IdentitiesGet200Response
import retrofit2.http.GET

interface IdentitiesApi {
    @GET("identities")
    suspend fun listIdentities(): IdentitiesGet200Response
}
