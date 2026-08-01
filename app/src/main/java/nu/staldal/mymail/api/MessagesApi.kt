package nu.staldal.mymail.api

import nu.staldal.mymail.model.MessageDetail
import nu.staldal.mymail.model.MessageSummary
import nu.staldal.mymail.model.MessagesDeleteRequest
import nu.staldal.mymail.model.MessagesIdPatchRequest
import nu.staldal.mymail.model.MessagesIdSnoozeDelete200Response
import nu.staldal.mymail.model.MessagesIdThreadGet200Response
import nu.staldal.mymail.model.MessagesMovePostRequest
import nu.staldal.mymail.model.MessagesPatchRequest
import nu.staldal.mymail.model.MessagesSearchGet200Response
import nu.staldal.mymail.model.FoldersFolderIdMessagesGet200Response
import retrofit2.http.*

interface MessagesApi {
    @GET("folders/{folder_id}/messages")
    suspend fun listMessagesInFolder(
        @Path("folder_id") folderId: Long,
        @Query("limit") limit: Int? = 50,
        @Query("offset") offset: Int? = 0,
    ): FoldersFolderIdMessagesGet200Response

    @GET("messages/{id}")
    suspend fun getMessageDetails(@Path("id") id: Long): MessageDetail

    @PATCH("messages/{id}")
    suspend fun updateMessageMetadata(
        @Path("id") id: Long,
        @Body request: MessagesIdPatchRequest,
    ): MessageSummary

    @DELETE("messages/{id}")
    suspend fun deleteMessage(@Path("id") id: Long)

    @PATCH("messages")
    suspend fun bulkUpdateMessages(@Body request: MessagesPatchRequest)

    @DELETE("messages")
    suspend fun bulkDeleteMessages(@Body request: MessagesDeleteRequest)

    @POST("messages/move")
    suspend fun bulkMoveMessagesToAFolder(@Body request: MessagesMovePostRequest)

    @GET("messages/{id}/thread")
    suspend fun getThread(@Path("id") id: Long): MessagesIdThreadGet200Response

    @POST("messages/{id}/mark-junk")
    suspend fun markMessageAsJunk(@Path("id") id: Long)

    @POST("messages/{id}/mark-not-junk")
    suspend fun markMessageAsNotJunk(@Path("id") id: Long)

    @DELETE("messages/{id}/snooze")
    suspend fun cancelSnooze(@Path("id") id: Long): MessagesIdSnoozeDelete200Response

    @GET("messages/search")
    suspend fun searchMessages(
        @Query("q") q: String,
        @Query("folder_id") folderId: Long? = null,
        @Query("date_from") dateFrom: String? = null,
        @Query("date_to") dateTo: String? = null,
        @Query("from_addr") fromAddr: String? = null,
        @Query("to_addr") toAddr: String? = null,
        @Query("limit") limit: Int? = 50,
        @Query("offset") offset: Int? = 0,
    ): MessagesSearchGet200Response
}
