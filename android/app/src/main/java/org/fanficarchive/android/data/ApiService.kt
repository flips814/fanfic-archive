package org.fanficarchive.android.data

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {
    @GET("healthz")
    suspend fun health(): HealthResponse

    @GET("api/info")
    suspend fun info(): ServerInfo

    @POST("api/login")
    suspend fun login(@Body body: LoginRequest): UserMe

    @POST("api/logout")
    suspend fun logout(): OkResponse

    @GET("api/me")
    suspend fun me(): UserMe

    @GET("api/works")
    suspend fun listWorks(
        @Query("q") q: String? = null,
        @Query("site") site: String? = null,
        @Query("status") status: String? = null,
        @Query("visibility") visibility: String? = null,
        @Query("mine") mine: String? = null,
        @Query("followed") followed: String? = null,
        @Query("added_by") addedBy: Int? = null,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
    ): WorkListResponse

    @GET("api/works/{id}")
    suspend fun getWork(@Path("id") id: Int): WorkDetail

    @GET("api/reading-progress")
    suspend fun listReadingProgress(): ReadingProgressListResponse

    @GET("api/works/{id}/reading-progress")
    suspend fun getReadingProgress(@Path("id") id: Int): ReadingProgressResponse

    @PUT("api/works/{id}/reading-progress")
    suspend fun putReadingProgress(
        @Path("id") id: Int,
        @Body body: ReadingProgressUpdateRequest,
    ): ReadingProgressResponse

    @DELETE("api/works/{id}/reading-progress")
    suspend fun deleteReadingProgress(@Path("id") id: Int): OkResponse

    @GET("api/works/{id}/chapters/{number}")
    suspend fun getChapter(@Path("id") id: Int, @Path("number") number: Int): ChapterContent

    @POST("api/works/{id}/follow")
    suspend fun follow(@Path("id") id: Int): FollowResponse

    @POST("api/works/{id}/unfollow")
    suspend fun unfollow(@Path("id") id: Int): FollowResponse

    @POST("api/works/{id}/check")
    suspend fun check(@Path("id") id: Int): Job

    @POST("api/works/{id}/auto-update")
    suspend fun toggleAutoUpdate(@Path("id") id: Int): AutoUpdateResponse

    @DELETE("api/works/{id}")
    suspend fun deleteWork(@Path("id") id: Int): OkResponse

    @POST("api/works/{id}/visibility")
    suspend fun setVisibility(@Path("id") id: Int, @Body body: VisibilityUpdateRequest): VisibilityUpdateResponse

    @POST("api/imports")
    suspend fun imports(@Body body: ImportRequest): ImportResponse

    @GET("api/jobs")
    suspend fun jobs(
        @Query("kind") kind: String? = null,
        @Query("status") status: String? = null,
        @Query("user_id") userId: Int? = null,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
    ): JobListResponse

    @GET("api/users")
    suspend fun users(): UsersResponse

    @GET("api/works/{id}/comments")
    suspend fun comments(
        @Path("id") id: Int,
        @Query("chapter_id") chapterId: Int? = null,
    ): CommentListResponse

    @POST("api/works/{id}/comments")
    suspend fun createComment(@Path("id") id: Int, @Body body: CommentCreateRequest): CommentItem

    @POST("api/comments/{id}/delete")
    suspend fun deleteComment(@Path("id") id: Int): OkResponse

    @GET("api/notifications")
    suspend fun notifications(@Query("limit") limit: Int = 50): StoryNotificationList

    @GET("api/settings")
    suspend fun getSettings(): NotificationSettings

    @POST("api/settings")
    suspend fun updateSettings(@Body body: NotificationSettings): NotificationSettings

    // Existing web translation endpoints (session cookie auth), same as the site UI.
    @GET("translate/languages")
    suspend fun translateLanguages(@Query("source") source: String? = null): TranslateLanguagesResponse

    @GET("works/{id}/chapters/{number}/translation")
    suspend fun getChapterTranslation(
        @Path("id") id: Int,
        @Path("number") number: Int,
        @Query("to") to: String,
    ): TranslationStatus

    @FormUrlEncoded
    @POST("works/{id}/chapters/{number}/translation")
    suspend fun requestChapterTranslation(
        @Path("id") id: Int,
        @Path("number") number: Int,
        @Field("to") to: String,
    ): TranslationStatus

    @GET("works/{id}/translation")
    suspend fun getWorkTranslation(
        @Path("id") id: Int,
        @Query("to") to: String,
    ): TranslationStatus

    @FormUrlEncoded
    @POST("works/{id}/translation")
    suspend fun requestWorkTranslation(
        @Path("id") id: Int,
        @Field("to") to: String,
    ): TranslationStatus
}
