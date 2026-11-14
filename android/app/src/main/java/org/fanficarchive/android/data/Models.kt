package org.fanficarchive.android.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ServerInfo(
    @SerialName("site_name") val siteName: String = "Fanfic Archive",
    @SerialName("api_version") val apiVersion: Int = 1,
    val auth: AuthCapabilities = AuthCapabilities(),
    val features: FeatureFlags = FeatureFlags(),
)

@Serializable
data class AuthCapabilities(
    val local: Boolean = false,
    val oidc: Boolean = false,
)

@Serializable
data class FeatureFlags(
    val translation: Boolean = false,
    val comments: Boolean = true,
    val epub: Boolean = true,
)

@Serializable
data class HealthResponse(val ok: Boolean = false)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class UserMe(
    val id: Int,
    val name: String,
    val email: String? = null,
    @SerialName("is_admin") val isAdmin: Boolean = false,
)

@Serializable
data class UserRef(
    val id: Int,
    val name: String,
)

@Serializable
data class WorkSummary(
    val id: Int,
    val title: String,
    val author: String? = null,
    val site: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    val status: String? = null,
    val rating: String? = null,
    @SerialName("word_count") val wordCount: Int? = null,
    val chapters: Int = 0,
    @SerialName("added_by") val addedBy: UserRef? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_checked_at") val lastCheckedAt: String? = null,
    @SerialName("last_new_chapter_at") val lastNewChapterAt: String? = null,
    @SerialName("auto_update") val autoUpdate: Boolean = true,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("is_following") val isFollowing: Boolean = false,
)

@Serializable
data class WorkListResponse(
    val works: List<WorkSummary> = emptyList(),
    val total: Int = 0,
    val limit: Int = 100,
    val offset: Int = 0,
)

@Serializable
data class ChapterSummary(
    val id: Int? = null,
    val number: Int,
    val title: String,
    @SerialName("word_count") val wordCount: Int? = null,
    @SerialName("imported_at") val importedAt: String? = null,
    @SerialName("imported_by") val importedBy: UserRef? = null,
    @SerialName("removed_from_source") val removedFromSource: Boolean = false,
)

@Serializable
data class WorkDetail(
    val id: Int,
    val title: String,
    val author: String? = null,
    val site: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    val status: String? = null,
    val rating: String? = null,
    @SerialName("word_count") val wordCount: Int? = null,
    val chapters: Int = 0,
    @SerialName("added_by") val addedBy: UserRef? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_checked_at") val lastCheckedAt: String? = null,
    @SerialName("last_new_chapter_at") val lastNewChapterAt: String? = null,
    @SerialName("auto_update") val autoUpdate: Boolean = true,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("is_following") val isFollowing: Boolean = false,
    val fandoms: List<String> = emptyList(),
    val relationships: List<String> = emptyList(),
    val characters: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    @SerialName("summary_html") val summaryHtml: String? = null,
    @SerialName("chapter_list") val chapterList: List<ChapterSummary> = emptyList(),
)

@Serializable
data class ChapterContent(
    val id: Int? = null,
    val number: Int,
    val title: String,
    @SerialName("word_count") val wordCount: Int? = null,
    @SerialName("work_id") val workId: Int,
    @SerialName("work_title") val workTitle: String,
    @SerialName("source_url") val sourceUrl: String? = null,
    @SerialName("content_html") val contentHtml: String = "",
    @SerialName("prev_number") val prevNumber: Int? = null,
    @SerialName("next_number") val nextNumber: Int? = null,
    @SerialName("removed_from_source") val removedFromSource: Boolean = false,
)

@Serializable
data class FollowResponse(
    val ok: Boolean = true,
    @SerialName("is_following") val isFollowing: Boolean,
)

@Serializable
data class ImportRequest(val urls: List<String>)

@Serializable
data class Job(
    val id: Int,
    val kind: String,
    val url: String? = null,
    @SerialName("work_id") val workId: Int? = null,
    @SerialName("work_title") val workTitle: String? = null,
    @SerialName("requested_by") val requestedBy: UserRef? = null,
    val status: String,
    val message: String? = null,
    @SerialName("chapters_added") val chaptersAdded: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
)

@Serializable
data class ImportResponse(
    val jobs: List<Job> = emptyList(),
    val rejected: List<String> = emptyList(),
)

@Serializable
data class OkResponse(val ok: Boolean = true)

@Serializable
data class ApiError(val detail: String? = null)
