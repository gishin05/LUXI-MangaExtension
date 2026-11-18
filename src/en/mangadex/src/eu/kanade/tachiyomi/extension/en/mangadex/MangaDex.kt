package eu.kanade.tachiyomi.extension.en.mangadex

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class MangaDex : HttpSource() {

    override val name = "MangaDex"
    override val baseUrl = "https://mangadex.org"
    override val lang = "en"
    override val supportsLatest = true

    private val apiUrl = "https://api.mangadex.org"
    private val uploadsUrl = "https://uploads.mangadex.org"
    private val json: Json by injectLazy()

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(3)
        .build()

    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override fun popularMangaRequest(page: Int): Request {
        val offset = (page - 1) * PAGE_LIMIT
        val url = "$apiUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("limit", PAGE_LIMIT.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("includes[]", "cover_art")
            .addQueryParameter("order[followedCount]", "desc")
            .addQueryParameter("contentRating[]", "safe")
            .addQueryParameter("contentRating[]", "suggestive")
            .addQueryParameter("contentRating[]", "erotica")
            .addQueryParameter("availableTranslatedLanguage[]", lang)
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val dto = json.decodeFromString<MangaListDto>(response.body.string())
        val mangas = dto.data.map { it.toSManga() }
        val hasNext = dto.offset + dto.limit < dto.total
        return MangasPage(mangas, hasNext)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val offset = (page - 1) * PAGE_LIMIT
        val url = "$apiUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("limit", PAGE_LIMIT.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("includes[]", "cover_art")
            .addQueryParameter("order[latestUploadedChapter]", "desc")
            .addQueryParameter("contentRating[]", "safe")
            .addQueryParameter("contentRating[]", "suggestive")
            .addQueryParameter("contentRating[]", "erotica")
            .addQueryParameter("availableTranslatedLanguage[]", lang)
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val offset = (page - 1) * PAGE_LIMIT
        val url = "$apiUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("limit", PAGE_LIMIT.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("includes[]", "cover_art")
        if (query.isNotBlank()) {
            url.addQueryParameter("title", query.trim())
        }
        var hasContentRating = false
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> url.addQueryParameter("order[${filter.toUriPart()}]", "desc")
                is StatusFilter -> filter.state.filter { it.state }.forEach {
                    url.addQueryParameter("status[]", it.id)
                }
                is DemographicFilter -> filter.state.filter { it.state }.forEach {
                    url.addQueryParameter("publicationDemographic[]", it.id)
                }
                is ContentRatingFilter -> filter.state.filter { it.state }.forEach {
                    hasContentRating = true
                    url.addQueryParameter("contentRating[]", it.id)
                }
                else -> {}
            }
        }
        if (!hasContentRating) {
            url.addQueryParameter("contentRating[]", "safe")
            url.addQueryParameter("contentRating[]", "suggestive")
        }
        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = manga.url.removePrefix("/title/").substringBefore("/")
        val url = "$apiUrl/manga/$id".toHttpUrl().newBuilder()
            .addQueryParameter("includes[]", "cover_art")
            .addQueryParameter("includes[]", "author")
            .addQueryParameter("includes[]", "artist")
            .build()
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val dto = json.decodeFromString<SingleMangaDto>(response.body.string())
        return dto.data.toSManga()
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun chapterListRequest(manga: SManga): Request {
        val id = manga.url.removePrefix("/title/").substringBefore("/")
        val url = "$apiUrl/manga/$id/feed".toHttpUrl().newBuilder()
            .addQueryParameter("translatedLanguage[]", lang)
            .addQueryParameter("includes[]", "scanlation_group")
            .addQueryParameter("order[volume]", "desc")
            .addQueryParameter("order[chapter]", "desc")
            .addQueryParameter("limit", "500")
            .addQueryParameter("contentRating[]", "safe")
            .addQueryParameter("contentRating[]", "suggestive")
            .addQueryParameter("contentRating[]", "erotica")
            .addQueryParameter("contentRating[]", "pornographic")
            .build()
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val dto = json.decodeFromString<ChapterListDto>(response.body.string())
        return dto.data.mapNotNull { ch ->
            if (ch.attributes.externalUrl != null && ch.attributes.pages == 0) {
                return@mapNotNull null
            }
            SChapter.create().apply {
                url = "/chapter/${ch.id}"
                val vol = ch.attributes.volume?.let { "Vol. $it " }.orEmpty()
                val num = ch.attributes.chapter?.let { "Ch. $it" } ?: "Oneshot"
                val title = ch.attributes.title?.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
                name = "$vol$num$title".trim()
                chapter_number = ch.attributes.chapter?.toFloatOrNull() ?: -1f
                date_upload = parseDate(ch.attributes.publishAt)
                scanlator = ch.relationships
                    .firstOrNull { it.type == "scanlation_group" }
                    ?.attributes?.name
            }
        }
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    override fun pageListRequest(chapter: SChapter): Request {
        val id = chapter.url.removePrefix("/chapter/")
        return GET("$apiUrl/at-home/server/$id", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val dto = json.decodeFromString<AtHomeDto>(response.body.string())
        val hash = dto.chapter.hash
        return dto.chapter.data.mapIndexed { index, file ->
            Page(index, imageUrl = "${dto.baseUrl}/data/$hash/$file")
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun MangaDto.toSManga(): SManga = SManga.create().apply {
        url = "/title/$id"
        title = attributes.title["en"]
            ?: attributes.title.values.firstOrNull()
            ?: "Untitled"
        description = attributes.description["en"]
            ?: attributes.description.values.firstOrNull()
        status = when (attributes.status) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        genre = attributes.tags
            .mapNotNull { it.attributes?.name?.get("en") }
            .joinToString()
            .ifEmpty { null }
        author = relationships.filter { it.type == "author" }
            .mapNotNull { it.attributes?.name }
            .joinToString()
            .ifEmpty { null }
        artist = relationships.filter { it.type == "artist" }
            .mapNotNull { it.attributes?.name }
            .joinToString()
            .ifEmpty { null }
        val coverFile = relationships.firstOrNull { it.type == "cover_art" }?.attributes?.fileName
        thumbnail_url = coverFile?.let { "$uploadsUrl/covers/$id/$it.512.jpg" }
    }

    private fun parseDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return runCatching { isoDateFormat.parse(raw)?.time ?: 0L }.getOrDefault(0L)
    }

    override fun getFilterList() = FilterList(
        SortFilter(),
        StatusFilter(),
        DemographicFilter(),
        ContentRatingFilter(),
    )

    private class SortFilter : Filter.Select<String>(
        "Order by",
        arrayOf("Relevance", "Followed Count", "Latest Uploaded", "Rating", "Created At"),
    ) {
        fun toUriPart() = when (state) {
            1 -> "followedCount"
            2 -> "latestUploadedChapter"
            3 -> "rating"
            4 -> "createdAt"
            else -> "relevance"
        }
    }

    private class IdCheckBox(name: String, val id: String, defaultState: Boolean = false) :
        Filter.CheckBox(name, defaultState)

    private class StatusFilter : Filter.Group<IdCheckBox>(
        "Status",
        listOf(
            IdCheckBox("Ongoing", "ongoing"),
            IdCheckBox("Completed", "completed"),
            IdCheckBox("Hiatus", "hiatus"),
            IdCheckBox("Cancelled", "cancelled"),
        ),
    )

    private class DemographicFilter : Filter.Group<IdCheckBox>(
        "Demographic",
        listOf(
            IdCheckBox("Shounen", "shounen"),
            IdCheckBox("Shoujo", "shoujo"),
            IdCheckBox("Seinen", "seinen"),
            IdCheckBox("Josei", "josei"),
        ),
    )

    private class ContentRatingFilter : Filter.Group<IdCheckBox>(
        "Content Rating",
        listOf(
            IdCheckBox("Safe", "safe", true),
            IdCheckBox("Suggestive", "suggestive", true),
            IdCheckBox("Erotica", "erotica", false),
        ),
    )

    @Serializable
    private data class MangaListDto(
        val data: List<MangaDto> = emptyList(),
        val limit: Int = 20,
        val offset: Int = 0,
        val total: Int = 0,
    )

    @Serializable
    private data class SingleMangaDto(
        val data: MangaDto,
    )

    @Serializable
    private data class MangaDto(
        val id: String,
        val attributes: MangaAttributes,
        val relationships: List<Relationship> = emptyList(),
    )

    @Serializable
    private data class MangaAttributes(
        val title: Map<String, String> = emptyMap(),
        val description: Map<String, String> = emptyMap(),
        val status: String? = null,
        val tags: List<TagDto> = emptyList(),
    )

    @Serializable
    private data class TagDto(
        val attributes: TagAttributes? = null,
    )

    @Serializable
    private data class TagAttributes(
        val name: Map<String, String> = emptyMap(),
    )

    @Serializable
    private data class Relationship(
        val id: String = "",
        val type: String = "",
        val attributes: RelationshipAttributes? = null,
    )

    @Serializable
    private data class RelationshipAttributes(
        val name: String? = null,
        val fileName: String? = null,
    )

    @Serializable
    private data class ChapterListDto(
        val data: List<ChapterDto> = emptyList(),
    )

    @Serializable
    private data class ChapterDto(
        val id: String,
        val attributes: ChapterAttributes,
        val relationships: List<Relationship> = emptyList(),
    )

    @Serializable
    private data class ChapterAttributes(
        val volume: String? = null,
        val chapter: String? = null,
        val title: String? = null,
        val publishAt: String? = null,
        val externalUrl: String? = null,
        val pages: Int = 0,
    )

    @Serializable
    private data class AtHomeDto(
        val baseUrl: String,
        val chapter: AtHomeChapter,
    )

    @Serializable
    private data class AtHomeChapter(
        val hash: String,
        val data: List<String> = emptyList(),
    )

    companion object {
        private const val PAGE_LIMIT = 20
    }
}
