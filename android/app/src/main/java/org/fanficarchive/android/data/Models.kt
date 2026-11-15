package org.fanficarchive.android.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ServerInfo(
    @SerialName("site_name") val siteName: String = "Fanfic Archive",
    /** Public URL configured on the host (BASE_URL), when set. */
    @SerialName("base_url") val baseUrl: String? = null,
    @SerialName("api_version") val apiVersion: Int = 1,
    val auth: AuthCapabilities = AuthCapabilities(),
    val features: FeatureFlags = FeatureFlags(),
)

@Serializable
data class AuthCapabilities(
    val local: Boolean = false,
    val oidc: Boolean = false,
    @SerialName("oidc_provider_name") val oidcProviderName: String? = null,
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
data class LoginRequest(val username: String, val password: String)

@Serializable
data class UserMe(
    val id: Int,
    val name: String,
    val email: String? = null,
    @SerialName("is_admin") val isAdmin: Boolean = false,
)

@Serializable
data class UserRef(val id: Int, val name: String)

/** Server work visibility: shared library vs importer-only. */
const val VISIBILITY_PUBLIC = "public"
const val VISIBILITY_PRIVATE = "private"

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
    val fandoms: List<String> = emptyList(),
    @SerialName("added_by") val addedBy: UserRef? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_checked_at") val lastCheckedAt: String? = null,
    @SerialName("last_new_chapter_at") val lastNewChapterAt: String? = null,
    @SerialName("auto_update") val autoUpdate: Boolean = true,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("is_following") val isFollowing: Boolean = false,
    val visibility: String = VISIBILITY_PUBLIC,
) {
    val isPrivate: Boolean get() = visibility == VISIBILITY_PRIVATE
}

@Serializable
data class WorkListResponse(
    val works: List<WorkSummary> = emptyList(),
    val total: Int = 0,
    val limit: Int = 100,
    val offset: Int = 0,
    val sites: List<String> = emptyList(),
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
    @SerialName("comment_count") val commentCount: Int = 0,
)

@Serializable
data class WorkDetail(
    val id: Int,
    val title: String,
    val author: String? = null,
    @SerialName("author_url") val authorUrl: String? = null,
    val site: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    val status: String? = null,
    val rating: String? = null,
    val language: String? = null,
    @SerialName("language_code") val languageCode: String? = null,
    @SerialName("word_count") val wordCount: Int? = null,
    val chapters: Int = 0,
    @SerialName("added_by") val addedBy: UserRef? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("source_updated_at") val sourceUpdatedAt: String? = null,
    @SerialName("last_checked_at") val lastCheckedAt: String? = null,
    @SerialName("last_new_chapter_at") val lastNewChapterAt: String? = null,
    @SerialName("auto_update") val autoUpdate: Boolean = true,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("is_following") val isFollowing: Boolean = false,
    @SerialName("can_manage") val canManage: Boolean = false,
    @SerialName("comment_count") val commentCount: Int = 0,
    val visibility: String = VISIBILITY_PUBLIC,
    val fandoms: List<String> = emptyList(),
    val relationships: List<String> = emptyList(),
    val characters: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    @SerialName("summary_html") val summaryHtml: String? = null,
    @SerialName("chapter_list") val chapterList: List<ChapterSummary> = emptyList(),
) {
    val isPrivate: Boolean get() = visibility == VISIBILITY_PRIVATE
}

@Serializable
data class ChapterContent(
    val id: Int? = null,
    val number: Int,
    val title: String,
    @SerialName("word_count") val wordCount: Int? = null,
    @SerialName("work_id") val workId: Int,
    @SerialName("work_title") val workTitle: String,
    @SerialName("work_chapters") val workChapters: Int = 0,
    @SerialName("work_language") val workLanguage: String? = null,
    @SerialName("work_language_code") val workLanguageCode: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    @SerialName("content_html") val contentHtml: String = "",
    @SerialName("prev_number") val prevNumber: Int? = null,
    @SerialName("next_number") val nextNumber: Int? = null,
    @SerialName("removed_from_source") val removedFromSource: Boolean = false,
    @SerialName("imported_at") val importedAt: String? = null,
    @SerialName("imported_by") val importedBy: UserRef? = null,
    @SerialName("comment_count") val commentCount: Int = 0,
)

@Serializable
data class TranslateLanguage(
    val code: String,
    val name: String,
)

@Serializable
data class TranslateLanguagesResponse(
    val source: String? = null,
    val languages: List<TranslateLanguage> = emptyList(),
)

@Serializable
data class TranslationStatus(
    val status: String = "none",
    val target: String? = null,
    @SerialName("target_name") val targetName: String? = null,
    val source: String? = null,
    @SerialName("source_name") val sourceName: String? = null,
    val title: String? = null,
    @SerialName("content_html") val contentHtml: String? = null,
    val progress: Int? = null,
    val error: String? = null,
)

@Serializable
data class FollowResponse(val ok: Boolean = true, @SerialName("is_following") val isFollowing: Boolean)

@Serializable
data class AutoUpdateResponse(val ok: Boolean = true, @SerialName("auto_update") val autoUpdate: Boolean)

@Serializable
data class ImportRequest(
    val urls: List<String>,
    val visibility: String = VISIBILITY_PUBLIC,
)

@Serializable
data class VisibilityUpdateRequest(val visibility: String)

@Serializable
data class VisibilityUpdateResponse(
    val ok: Boolean = true,
    val visibility: String = VISIBILITY_PUBLIC,
)

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
    val visibility: String = VISIBILITY_PUBLIC,
) {
    val isPrivate: Boolean get() = visibility == VISIBILITY_PRIVATE
}

@Serializable
data class JobListResponse(
    val jobs: List<Job> = emptyList(),
    val total: Int = 0,
    val limit: Int = 50,
    val offset: Int = 0,
)

@Serializable
data class ImportResponse(val jobs: List<Job> = emptyList(), val rejected: List<String> = emptyList())

@Serializable
data class ArchiveUser(
    val id: Int,
    val name: String,
    val email: String? = null,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("works_imported") val worksImported: Int = 0,
    @SerialName("chapters_imported") val chaptersImported: Int = 0,
    val following: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_login_at") val lastLoginAt: String? = null,
)

@Serializable
data class UsersResponse(
    val users: List<ArchiveUser> = emptyList(),
    @SerialName("auto_updater_chapters") val autoUpdaterChapters: Int = 0,
)

@Serializable
data class CommentItem(
    val id: Int,
    @SerialName("work_id") val workId: Int,
    @SerialName("chapter_id") val chapterId: Int? = null,
    @SerialName("parent_id") val parentId: Int? = null,
    val body: String? = null,
    val spoiler: Boolean = false,
    val deleted: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("edited_at") val editedAt: String? = null,
    val user: UserRef? = null,
    @SerialName("can_edit") val canEdit: Boolean = false,
    @SerialName("can_delete") val canDelete: Boolean = false,
    val replies: List<CommentItem> = emptyList(),
)

@Serializable
data class CommentListResponse(
    val comments: List<CommentItem> = emptyList(),
    val count: Int = 0,
)

@Serializable
data class CommentCreateRequest(
    val body: String,
    @SerialName("chapter_id") val chapterId: Int? = null,
    @SerialName("parent_id") val parentId: Int? = null,
    val spoiler: Boolean = false,
)

@Serializable
data class NotificationSettings(
    @SerialName("notify_email") val notifyEmail: Boolean = true,
    @SerialName("notify_email_address") val notifyEmailAddress: String? = null,
    @SerialName("discord_webhook_url") val discordWebhookUrl: String? = null,
    @SerialName("apprise_urls") val appriseUrls: String? = null,
)

@Serializable
data class StoryNotification(
    val id: String,
    val kind: String,
    val title: String,
    val body: String,
    @SerialName("work_id") val workId: Int? = null,
    @SerialName("work_title") val workTitle: String? = null,
    val actor: UserRef? = null,
    @SerialName("job_id") val jobId: Int? = null,
    @SerialName("chapters_added") val chaptersAdded: Int = 0,
    val status: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class StoryNotificationList(
    val notifications: List<StoryNotification> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class OkResponse(val ok: Boolean = true)

@Serializable
data class ApiError(val detail: String? = null)
