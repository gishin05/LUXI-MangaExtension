package eu.kanade.tachiyomi.extension.en.asurascans

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class AsuraScans : ParsedHttpSource() {

    override val name = "Asura Scans"
    override val baseUrl = "https://asurascans.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(2)
        .build()

    private val dateFormat = SimpleDateFormat("MMMM d yyyy", Locale.US)

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/series?page=$page&order=popular", headers)
    }

    override fun popularMangaSelector() = "div.grid > a[href^=series/], div.grid > a[href^=/series/]"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val href = element.absUrl("href")
        setUrlWithoutDomain(href)
        title = element.selectFirst("span.block.font-bold, span.font-bold, h2, h3")
            ?.text()?.trim()
            ?: element.selectFirst("img")?.attr("alt")?.trim().orEmpty()
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
    }

    override fun popularMangaNextPageSelector() = "a:contains(Next):not(.pointer-events-none)"

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/series?page=$page&order=update", headers)
    }

    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
        if (query.isNotBlank()) {
            url.addQueryParameter("name", query.trim())
        }
        filters.forEach { filter ->
            when (filter) {
                is OrderFilter -> url.addQueryParameter("order", filter.toUriPart())
                is StatusFilter -> filter.toUriPart().takeIf { it.isNotEmpty() }?.let {
                    url.addQueryParameter("status", it)
                }
                is TypeFilter -> filter.toUriPart().takeIf { it.isNotEmpty() }?.let {
                    url.addQueryParameter("types", it)
                }
                else -> {}
            }
        }
        return GET(url.build(), headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()
    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("span.text-xl.font-bold, h1")?.text()?.trim().orEmpty()
        thumbnail_url = document.selectFirst("img[alt=poster], div.relative img")?.absUrl("src")
        description = document.selectFirst("span.font-medium.text-sm, div.col-span-12 p")
            ?.text()?.trim()
        genre = document.select("button.bg-\\[\\#343434\\], a[href*=genres]")
            .joinToString { it.text().trim() }
            .ifEmpty { null }
        author = document.selectFirst("h3:contains(Author) + h3")?.text()?.trim()
            ?.takeUnless { it == "_" || it == "-" }
        artist = document.selectFirst("h3:contains(Artist) + h3")?.text()?.trim()
            ?.takeUnless { it == "_" || it == "-" }
        val statusText = document.selectFirst("h3:contains(Status) + h3")?.text()?.trim().orEmpty()
        status = when {
            statusText.contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
            statusText.contains("Completed", ignoreCase = true) -> SManga.COMPLETED
            statusText.contains("Hiatus", ignoreCase = true) -> SManga.ON_HIATUS
            statusText.contains("Dropped", ignoreCase = true) -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    override fun chapterListSelector() = "div.scrollbar-thumb-themecolor a[href*=/chapter/], a[href*=/chapter/]"

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        val titleSpans = element.select("h3")
        name = titleSpans.firstOrNull()?.text()?.trim() ?: element.text().trim()
        val dateText = titleSpans.getOrNull(1)?.text()?.trim()
        date_upload = parseDate(dateText)
    }

    override fun pageListParse(document: Document): List<Page> {
        val domImages = document.select("div.w-full.mx-auto.center img, img[alt*=chapter page]")
            .mapNotNull { it.absUrl("src").takeIf { url -> url.isNotBlank() && !url.contains("EndDesign") } }
            .distinct()
        if (domImages.isNotEmpty()) {
            return domImages.mapIndexed { idx, url -> Page(idx, imageUrl = url) }
        }
        // Fallback: extract image URLs from Next.js hydration scripts
        val scriptText = document.select("script").joinToString("\n") { it.data() }
        val regex = Regex("""https://gg\.asuracomic\.net/storage/media/[^"\\]+\.(?:webp|jpg|jpeg|png)""")
        return regex.findAll(scriptText)
            .map { it.value }
            .distinct()
            .filterNot { it.contains("EndDesign") }
            .mapIndexed { idx, url -> Page(idx, imageUrl = url) }
            .toList()
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    private fun parseDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val cleaned = raw.replace(Regex("""(\d+)(st|nd|rd|th)"""), "$1").replace(",", "")
        return runCatching { dateFormat.parse(cleaned)?.time ?: 0L }.getOrDefault(0L)
    }

    override fun getFilterList() = FilterList(
        OrderFilter(),
        StatusFilter(),
        TypeFilter(),
    )

    private class OrderFilter : Filter.Select<String>(
        "Order by",
        arrayOf("Update", "Rating", "Bookmarks", "Name (A-Z)", "Name (Z-A)"),
    ) {
        fun toUriPart() = when (state) {
            1 -> "rating"
            2 -> "bookmarks"
            3 -> "az"
            4 -> "za"
            else -> "update"
        }
    }

    private class StatusFilter : Filter.Select<String>(
        "Status",
        arrayOf("All", "Ongoing", "Hiatus", "Completed", "Dropped"),
    ) {
        fun toUriPart() = when (state) {
            1 -> "1"
            2 -> "2"
            3 -> "3"
            4 -> "4"
            else -> ""
        }
    }

    private class TypeFilter : Filter.Select<String>(
        "Type",
        arrayOf("All", "Manhwa", "Manhua", "Manga"),
    ) {
        fun toUriPart() = when (state) {
            1 -> "1"
            2 -> "2"
            3 -> "3"
            else -> ""
        }
    }
}
