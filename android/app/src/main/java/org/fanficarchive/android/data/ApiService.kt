package org.fanficarchive.android.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
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
        @Query("mine") mine: String? = null,
        @Query("followed") followed: String? = null,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
    ): WorkListResponse

    @GET("api/works/{id}")
    suspend fun getWork(@Path("id") id: Int): WorkDetail

    @GET("api/works/{id}/chapters/{number}")
    suspend fun getChapter(
        @Path("id") id: Int,
        @Path("number") number: Int,
    ): ChapterContent

    @POST("api/works/{id}/follow")
    suspend fun follow(@Path("id") id: Int): FollowResponse

    @POST("api/works/{id}/unfollow")
    suspend fun unfollow(@Path("id") id: Int): FollowResponse

    @POST("api/works/{id}/check")
    suspend fun check(@Path("id") id: Int): Job

    @POST("api/imports")
    suspend fun imports(@Body body: ImportRequest): ImportResponse

    @GET("api/jobs")
    suspend fun jobs(@Query("limit") limit: Int = 50): List<Job>
}
