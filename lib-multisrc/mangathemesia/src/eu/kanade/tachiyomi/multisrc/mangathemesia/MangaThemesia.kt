package eu.kanade.tachiyomi.multisrc.mangathemesia

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

/** Base source for MangaThemesia / MangaStream WordPress theme sites. */
abstract class MangaThemesia(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
    protected open val mangaUrlDirectory: String = "/manga",
    private val dateFormat: SimpleDateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.US),
) : ParsedHttpSource() {

    protected val json: Json by injectLazy()

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(2)
        .build()

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl$mangaUrlDirectory/?page=$page&order=popular", headers)
    }

    override fun popularMangaSelector() = "div.listupd div.bs div.bsx"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val link = element.selectFirst("a")!!
        title = link.attr("title").ifBlank { element.selectFirst("div.tt")?.text().orEmpty() }.trim()
        setUrlWithoutDomain(link.absUrl("href"))
        thumbnail_url = element.selectFirst("img")?.let { imageFromElement(it) }
    }

    override fun popularMangaNextPageSelector() = "div.hpage a.r, a.next.page-numbers"

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl$mangaUrlDirectory/?page=$page&order=update", headers)
    }

    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl$mangaUrlDirectory/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
        if (query.isNotBlank()) {
            url.addQueryParameter("s", query)
        }
        filters.forEach { filter ->
            when (filter) {
                is OrderByFilter -> url.addQueryParameter("order", filter.toUriPart())
                is StatusFilter -> url.addQueryParameter("status", filter.toUriPart())
                is TypeFilter -> url.addQueryParameter("type", filter.toUriPart())
                else -> {}
            }
        }
        return GET(url.build(), headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()
    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        thumbnail_url = document.selectFirst("div.thumb img")?.let { imageFromElement(it) }
        description = document.select("div.entry-content[itemprop=description] p")
            .joinToString("\n\n") { it.text().trim() }
            .ifEmpty { document.selectFirst("div.entry-content[itemprop=description]")?.text()?.trim() }
        genre = document.select("span.mgen a").joinToString { it.text().trim() }.ifEmpty { null }
        author = document.selectFirst("div.tsinfo div.imptdt:contains(Author) i, span:contains(Author)")
            ?.text()?.trim()?.takeUnless { it == "-" }
        artist = document.selectFirst("div.tsinfo div.imptdt:contains(Artist) i, span:contains(Artist)")
            ?.text()?.trim()?.takeUnless { it == "-" }
        status = parseStatus(
            document.selectFirst("div.tsinfo div.imptdt:contains(Status) i")?.text()?.trim().orEmpty(),
        )
    }

    protected open fun parseStatus(status: String): Int = when {
        status.contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
        status.contains("Completed", ignoreCase = true) -> SManga.COMPLETED
        status.contains("Hiatus", ignoreCase = true) -> SManga.ON_HIATUS
        status.contains("Dropped", ignoreCase = true) -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    override fun chapterListSelector() = "div#chapterlist ul li"

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        val link = element.selectFirst("a")!!
        name = element.selectFirst("span.chapternum")?.text()?.trim() ?: link.text().trim()
        setUrlWithoutDomain(link.absUrl("href"))
        date_upload = parseDate(element.selectFirst("span.chapterdate")?.text()?.trim())
    }

    override fun pageListParse(document: Document): List<Page> {
        val tsReaderScript = document.select("script")
            .firstOrNull { it.data().contains("ts_reader.run") }
            ?.data()
        if (tsReaderScript != null) {
            val jsonMatch = Regex("""ts_reader\.run\((\{.*?\})\);?""").find(tsReaderScript)
            if (jsonMatch != null) {
                val payload = runCatching {
                    json.decodeFromString<TsReaderPayload>(jsonMatch.groupValues[1])
                }.getOrNull()
                val images = payload?.sources?.firstOrNull()?.images.orEmpty()
                if (images.isNotEmpty()) {
                    return images.mapIndexed { index, url -> Page(index, imageUrl = url) }
                }
            }
        }
        return document.select("div#readerarea img")
            .mapIndexedNotNull { index, img ->
                val url = imageFromElement(img) ?: return@mapIndexedNotNull null
                Page(index, imageUrl = url)
            }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    protected open fun imageFromElement(element: Element): String? {
        return listOf("data-src", "data-lazy-src", "src")
            .firstNotNullOfOrNull { attr ->
                element.absUrl(attr).trim().takeIf { it.isNotEmpty() && !it.startsWith("data:") }
            }
    }

    protected open fun parseDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        return runCatching { dateFormat.parse(date)?.time ?: 0L }.getOrDefault(0L)
    }

    override fun getFilterList() = FilterList(
        OrderByFilter(),
        StatusFilter(),
        TypeFilter(),
    )

    private class OrderByFilter : Filter.Select<String>(
        "Order by",
        arrayOf("Default", "A-Z", "Z-A", "Latest Update", "Latest Added", "Popular"),
    ) {
        fun toUriPart() = when (state) {
            1 -> "title"
            2 -> "titlereverse"
            3 -> "update"
            4 -> "latest"
            5 -> "popular"
            else -> ""
        }
    }

    private class StatusFilter : Filter.Select<String>(
        "Status",
        arrayOf("All", "Ongoing", "Completed", "Hiatus"),
    ) {
        fun toUriPart() = when (state) {
            1 -> "ongoing"
            2 -> "completed"
            3 -> "hiatus"
            else -> ""
        }
    }

    private class TypeFilter : Filter.Select<String>(
        "Type",
        arrayOf("All", "Manga", "Manhwa", "Manhua", "Comic"),
    ) {
        fun toUriPart() = when (state) {
            1 -> "manga"
            2 -> "manhwa"
            3 -> "manhua"
            4 -> "comic"
            else -> ""
        }
    }

    @Serializable
    private data class TsReaderPayload(
        val sources: List<Source> = emptyList(),
    ) {
        @Serializable
        data class Source(
            val images: List<String> = emptyList(),
        )
    }
}
