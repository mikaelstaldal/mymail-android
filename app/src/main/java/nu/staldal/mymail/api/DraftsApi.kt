package nu.staldal.mymail.api

import nu.staldal.mymail.model.DraftRequest
import nu.staldal.mymail.model.DraftsPost201Response
import nu.staldal.mymail.model.MessagesSendPost201Response
import retrofit2.Response
import retrofit2.http.*

interface DraftsApi {
    @POST("drafts")
    suspend fun saveNewDraft(@Body request: DraftRequest): DraftsPost201Response

    @PUT("drafts/{id}")
    suspend fun replaceDraftContent(@Path("id") id: Long, @Body request: DraftRequest)

    @DELETE("drafts/{id}")
    suspend fun deleteDraft(@Path("id") id: Long)

    @POST("drafts/{id}/send")
    suspend fun sendOrScheduleADraft(@Path("id") id: Long): Response<MessagesSendPost201Response>

    @DELETE("drafts/{id}/attachments/{attachment_id}")
    suspend fun removeASingleAttachmentFromADraft(
        @Path("id") id: Long,
        @Path("attachment_id") attachmentId: Long,
    )
}
