package org.fanficarchive.android.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable
data class OfflineChapter(
    val number: Int,
    val title: String,
    val contentHtml: String = "",
    val wordCount: Int? = null,
    val sourceUrl: String? = null,
)

@Serializable
data class OfflineWorkSnapshot(
    val workId: Int,
    val baseUrl: String,
    val downloadedAt: Long = System.currentTimeMillis(),
    val title: String,
    val author: String? = null,
    val site: String? = null,
    val sourceUrl: String? = null,
    val status: String? = null,
    val rating: String? = null,
    val language: String? = null,
    val languageCode: String? = null,
    val wordCount: Int? = null,
    val visibility: String = VISIBILITY_PUBLIC,
    val summaryHtml: String? = null,
    val fandoms: List<String> = emptyList(),
    val relationships: List<String> = emptyList(),
    val characters: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val chapterList: List<ChapterSummary> = emptyList(),
    val chapters: List<OfflineChapter> = emptyList(),
    val coverUrl: String? = null,
) {
    fun toWorkSummary(localCoverUri: String? = null): WorkSummary = WorkSummary(
        id = workId,
        title = title,
        author = author,
        site = site,
        sourceUrl = sourceUrl,
        status = status,
        rating = rating,
        wordCount = wordCount,
        chapters = chapters.size.coerceAtLeast(chapterList.size),
        fandoms = fandoms,
        visibility = visibility,
        coverUrl = localCoverUri ?: coverUrl,
    )

    fun toWorkDetail(localCoverUri: String? = null): WorkDetail = WorkDetail(
        id = workId,
        title = title,
        author = author,
        site = site,
        sourceUrl = sourceUrl,
        status = status,
        rating = rating,
        language = language,
        languageCode = languageCode,
        wordCount = wordCount,
        chapters = chapters.size.coerceAtLeast(chapterList.size),
        visibility = visibility,
        fandoms = fandoms,
        relationships = relationships,
        characters = characters,
        tags = tags,
        warnings = warnings,
        summaryHtml = summaryHtml,
        chapterList = chapterList.ifEmpty {
            chapters.map { ChapterSummary(number = it.number, title = it.title, wordCount = it.wordCount) }
        },
        coverUrl = localCoverUri ?: coverUrl,
        fromOffline = true,
    )

    fun toChapterContent(number: Int): ChapterContent? {
        val chapter = chapters.firstOrNull { it.number == number } ?: return null
        val numbers = chapters.map { it.number }.sorted()
        val index = numbers.indexOf(number)
        return ChapterContent(
            number = chapter.number,
            title = chapter.title,
            wordCount = chapter.wordCount,
            workId = workId,
            workTitle = title,
            workChapters = chapters.size,
            workLanguage = language,
            workLanguageCode = languageCode,
            sourceUrl = chapter.sourceUrl,
            contentHtml = chapter.contentHtml,
            prevNumber = numbers.getOrNull(index - 1),
            nextNumber = numbers.getOrNull(index + 1),
            fromOffline = true,
        )
    }
}

class OfflineStore(context: Context) {
    private val root = File(context.filesDir, "offline")
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private fun serverDir(baseUrl: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(baseUrl.trimEnd('/').lowercase().toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
        return File(root, digest).also { it.mkdirs() }
    }

    private fun workFile(baseUrl: String, workId: Int): File =
        File(serverDir(baseUrl), "work-$workId.json")

    private fun coverFile(baseUrl: String, workId: Int): File =
        File(serverDir(baseUrl), "work-$workId.cover")

    /** file:// URI for a cached cover, if present. */
    fun localCoverUri(baseUrl: String, workId: Int): String? {
        val file = coverFile(baseUrl, workId)
        return if (file.isFile && file.length() > 0L) file.toURI().toString() else null
    }

    suspend fun saveWork(snapshot: OfflineWorkSnapshot) = withContext(Dispatchers.IO) {
        val file = workFile(snapshot.baseUrl, snapshot.workId)
        file.writeText(json.encodeToString(snapshot))
    }

    suspend fun saveCover(baseUrl: String, workId: Int, bytes: ByteArray) = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext
        coverFile(baseUrl, workId).writeBytes(bytes)
    }

    suspend fun loadWork(baseUrl: String, workId: Int): OfflineWorkSnapshot? = withContext(Dispatchers.IO) {
        val file = workFile(baseUrl, workId)
        if (!file.isFile) return@withContext null
        runCatching { json.decodeFromString<OfflineWorkSnapshot>(file.readText()) }.getOrNull()
    }

    suspend fun isDownloaded(baseUrl: String, workId: Int): Boolean = withContext(Dispatchers.IO) {
        workFile(baseUrl, workId).isFile
    }

    suspend fun listDownloaded(baseUrl: String): List<OfflineWorkSnapshot> = withContext(Dispatchers.IO) {
        val dir = serverDir(baseUrl)
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("work-") && it.name.endsWith(".json") }
            ?.mapNotNull { file ->
                runCatching { json.decodeFromString<OfflineWorkSnapshot>(file.readText()) }.getOrNull()
            }
            ?.sortedByDescending { it.downloadedAt }
            .orEmpty()
    }

    suspend fun downloadedWorkIds(baseUrl: String): Set<Int> = withContext(Dispatchers.IO) {
        listDownloaded(baseUrl).map { it.workId }.toSet()
    }

    suspend fun deleteWork(baseUrl: String, workId: Int) = withContext(Dispatchers.IO) {
        workFile(baseUrl, workId).delete()
        coverFile(baseUrl, workId).delete()
        Unit
    }
}
