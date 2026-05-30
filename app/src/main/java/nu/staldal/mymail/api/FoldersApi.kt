package nu.staldal.mymail.api

import nu.staldal.mymail.model.Folder
import nu.staldal.mymail.model.FoldersGet200Response
import nu.staldal.mymail.model.FoldersIdPatchRequest
import nu.staldal.mymail.model.FoldersPostRequest
import retrofit2.http.*

interface FoldersApi {
    @GET("folders")
    suspend fun listFolders(): FoldersGet200Response

    @POST("folders")
    suspend fun createFolder(@Body request: FoldersPostRequest): Folder

    @PATCH("folders/{id}")
    suspend fun updateFolder(@Path("id") id: Long, @Body request: FoldersIdPatchRequest): Folder

    @DELETE("folders/{id}")
    suspend fun deleteFolder(@Path("id") id: Long)

    @POST("folders/{folder_id}/mark-all-read")
    suspend fun markAllMessagesInFolderAsRead(@Path("folder_id") folderId: Long)

    @DELETE("folders/{folder_id}/messages")
    suspend fun deleteAllMessagesInFolder(@Path("folder_id") folderId: Long)
}
