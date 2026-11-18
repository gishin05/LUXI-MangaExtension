package eu.kanade.tachiyomi.extension.en.comix

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

/** Public-endpoint Comix source; users handle any site challenge in WebView. */
class Comix : HttpSource() {

    override val name = "Comix"
    override val baseUrl = "https://comix.to"
    override val lang = "en"
    override val supportsLatest = true

    private val apiUrl = "$baseUrl/api/v2"
    private val json: Json by injectLazy()

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(2)
        .build()

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("order[views_30d]", "desc")
            .addQueryParameter("limit", PAGE_LIMIT.toString())
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val dto = json.decodeFromString<MangaPageResponse>(response.body.string())
        val items = dto.result?.items.orEmpty().map { it.toSManga() }
        val pagination = dto.result?.pagination
        val hasNext = pagination != null && pagination.currentPage < pagination.lastPage
        return MangasPage(items, hasNext)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("order[chapter_updated_at]", "desc")
            .addQueryParameter("limit", PAGE_LIMIT.toString())
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("limit", PAGE_LIMIT.toString())
            .addQueryParameter("page", page.toString())
        if (query.isNotBlank()) {
            url.addQueryParameter("keyword", query.trim())
        }
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> url.addQueryParameter("order[${filter.toUriPart()}]", "desc")
                is TypeFilter -> filter.toUriPart().takeIf { it.isNotEmpty() }?.let {
                    url.addQueryParameter("types[]", it)
                }
                else -> {}
            }
        }
        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request {
        val hashId = manga.url.removePrefix("/title/").substringBefore("-")
        return GET("$apiUrl/manga/$hashId", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val dto = json.decodeFromString<SingleMangaResponse>(response.body.string())
        return dto.result.toSManga()
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun chapterListRequest(manga: SManga): Request {
        val hashId = manga.url.removePrefix("/title/").substringBefore("-")
        return GET("$apiUrl/manga/$hashId/chapters?limit=500&order[number]=desc", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val dto = json.decodeFromString<ChapterListResponse>(response.body.string())
        return dto.result?.items.orEmpty().map { ch ->
            SChapter.create().apply {
                url = "/chapters/${ch.chapterId}"
                val num = ch.number?.let {
                    if (it % 1.0 == 0.0) "Ch. ${it.toInt()}" else "Ch. $it"
                } ?: "Chapter"
                val title = ch.name?.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
                name = "$num$title"
                chapter_number = ch.number?.toFloat() ?: -1f
                date_upload = (ch.updatedAt ?: ch.createdAt ?: 0L) * 1000L
                scanlator = ch.scanlationGroup?.name
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        return GET("$apiUrl${chapter.url}", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val dto = json.decodeFromString<ChapterDetailResponse>(response.body.string())
        return dto.result.images.mapIndexed { idx, img ->
            Page(idx, imageUrl = img.url)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun MangaItem.toSManga(): SManga = SManga.create().apply {
        val slugPart = slug?.let { "-$it" }.orEmpty()
        url = "/title/$hashId$slugPart"
        title = this@toSManga.title
        description = synopsis
        thumbnail_url = poster?.large ?: poster?.medium
        status = when (this@toSManga.status) {
            "releasing" -> SManga.ONGOING
            "finished" -> SManga.COMPLETED
            "on_hiatus" -> SManga.ON_HIATUS
            "discontinued" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    override fun getFilterList() = FilterList(
        SortFilter(),
        TypeFilter(),
    )

    private class SortFilter : Filter.Select<String>(
        "Order by",
        arrayOf("Popular (30d)", "Latest Update", "Most Followed", "Rating"),
    ) {
        fun toUriPart() = when (state) {
            1 -> "chapter_updated_at"
            2 -> "follows_total"
            3 -> "rated_avg"
            else -> "views_30d"
        }
    }

    private class TypeFilter : Filter.Select<String>(
        "Type",
        arrayOf("All", "Manga", "Manhwa", "Manhua"),
    ) {
        fun toUriPart() = when (state) {
            1 -> "manga"
            2 -> "manhwa"
            3 -> "manhua"
            else -> ""
        }
    }

    @Serializable
    private data class MangaPageResponse(val result: MangaPageResult? = null)

    @Serializable
    private data class MangaPageResult(
        val items: List<MangaItem> = emptyList(),
        val pagination: Pagination? = null,
    )

    @Serializable
    private data class Pagination(
        @SerialName("current_page") val currentPage: Int = 1,
        @SerialName("last_page") val lastPage: Int = 1,
    )

    @Serializable
    private data class SingleMangaResponse(val result: MangaItem)

    @Serializable
    private data class MangaItem(
        @SerialName("hash_id") val hashId: String,
        val slug: String? = null,
        val title: String,
        val synopsis: String? = null,
        val status: String? = null,
        val poster: Poster? = null,
    )

    @Serializable
    private data class Poster(
        val large: String? = null,
        val medium: String? = null,
    )

    @Serializable
    private data class ChapterListResponse(val result: ChapterListResult? = null)

    @Serializable
    private data class ChapterListResult(val items: List<ChapterItem> = emptyList())

    @Serializable
    private data class ChapterItem(
        @SerialName("chapter_id") val chapterId: Long,
        val number: Double? = null,
        val name: String? = null,
        @SerialName("created_at") val createdAt: Long? = null,
        @SerialName("updated_at") val updatedAt: Long? = null,
        @SerialName("scanlation_group") val scanlationGroup: ScanlationGroup? = null,
    )

    @Serializable
    private data class ScanlationGroup(val name: String? = null)

    @Serializable
    private data class ChapterDetailResponse(val result: ChapterDetailResult)

    @Serializable
    private data class ChapterDetailResult(val images: List<PageImage> = emptyList())

    @Serializable
    private data class PageImage(val url: String)

    companion object {
        private const val PAGE_LIMIT = 24
    }
}
