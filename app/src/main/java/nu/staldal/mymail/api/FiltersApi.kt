package nu.staldal.mymail.api

import nu.staldal.mymail.model.FiltersGet200Response
import retrofit2.http.GET

interface FiltersApi {
    @GET("filters")
    suspend fun listFilters(): FiltersGet200Response
}
