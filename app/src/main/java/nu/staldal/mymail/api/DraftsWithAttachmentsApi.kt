package nu.staldal.mymail.api

import nu.staldal.mymail.model.DraftsPost201Response
import okhttp3.MultipartBody
import retrofit2.http.*

interface DraftsWithAttachmentsApi {
    @Multipart
    @POST("drafts-with-attachments")
    suspend fun saveNewDraftWithAttachments(
        @Part message: MultipartBody.Part?,
        @Part attachments: List<MultipartBody.Part>,
    ): DraftsPost201Response

    @Multipart
    @PUT("drafts-with-attachments/{id}")
    suspend fun replaceDraftContentWithAttachments(
        @Path("id") id: Long,
        @Part message: MultipartBody.Part?,
        @Part attachments: List<MultipartBody.Part>,
    )
}
