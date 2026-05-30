package nu.staldal.mymail.api

import nu.staldal.mymail.model.Contact
import nu.staldal.mymail.model.ContactsGet200Response
import nu.staldal.mymail.model.ContactsPostRequest
import retrofit2.http.*

interface ContactsApi {
    @GET("contacts")
    suspend fun listContacts(
        @Query("q") q: String? = null,
        @Query("limit") limit: Int? = 50,
        @Query("offset") offset: Int? = 0,
    ): ContactsGet200Response

    @POST("contacts")
    suspend fun createContact(@Body request: ContactsPostRequest): Contact

    @PUT("contacts/{id}")
    suspend fun replaceContact(@Path("id") id: Long, @Body request: ContactsPostRequest): Contact

    @DELETE("contacts/{id}")
    suspend fun deleteContact(@Path("id") id: Long)
}
