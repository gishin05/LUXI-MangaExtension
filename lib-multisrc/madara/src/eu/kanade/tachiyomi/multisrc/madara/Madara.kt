package eu.kanade.tachiyomi.multisrc.madara

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Base source for WP-Manga (Madara) sites. */
abstract class Madara(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
    private val dateFormat: SimpleDateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.US),
) : ParsedHttpSource() {

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(2)
        .build()

    protected open val mangaSubString = "manga"
    protected open val useLoadMoreRequest = false
    protected open val useNewChapterEndpoint = true

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/$mangaSubString/page/$page/?m_orderby=views", headers)
    }

    override fun popularMangaSelector() = "div.page-item-detail, div.c-tabs-item__content"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val link = element.selectFirst("div.post-title a, h3 a, h5 a")!!
        title = link.text().trim()
        setUrlWithoutDomain(link.absUrl("href"))
        thumbnail_url = element.selectFirst("img")?.let { imageFromElement(it) }
    }

    override fun popularMangaNextPageSelector() = "div.nav-previous a, a.nextpostslink, a.page-numbers.next"

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/$mangaSubString/page/$page/?m_orderby=latest", headers)
    }

    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/page/$page/".toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .addQueryParameter("post_type", "wp-manga")
        filters.forEach { filter ->
            when (filter) {
                is OrderByFilter -> url.addQueryParameter("m_orderby", filter.toUriPart())
                is StatusFilter -> filter.state.filter { it.state }.forEach {
                    url.addQueryParameter("status[]", it.id)
                }
                else -> {}
            }
        }
        return GET(url.build(), headers)
    }

    override fun searchMangaSelector() = "div.c-tabs-item__content, div.page-item-detail"
    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("div.post-title h1, h1")?.text()?.trim().orEmpty()
        author = document.select("div.author-content a").joinToString { it.text().trim() }.ifEmpty { null }
        artist = document.select("div.artist-content a").joinToString { it.text().trim() }.ifEmpty { null }
        genre = document.select("div.genres-content a").joinToString { it.text().trim() }.ifEmpty { null }
        description = document.select("div.description-summary div.summary__content p, div.summary__content")
            .joinToString("\n\n") { it.text().trim() }
            .trim()
            .ifEmpty { null }
        thumbnail_url = document.selectFirst("div.summary_image img")?.let { imageFromElement(it) }
        status = parseStatus(
            document.select("div.post-content_item:contains(Status) div.summary-content").text().trim(),
        )
    }

    protected open fun parseStatus(status: String): Int = when {
        status.contains("OnGoing", ignoreCase = true) ||
            status.contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
        status.contains("Completed", ignoreCase = true) -> SManga.COMPLETED
        status.contains("Hiatus", ignoreCase = true) -> SManga.ON_HIATUS
        status.contains("Canceled", ignoreCase = true) -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    override fun chapterListRequest(manga: SManga): Request {
        return if (useNewChapterEndpoint) {
            POST("${getMangaUrl(manga).trimEnd('/')}/ajax/chapters/", headers, FormBody.Builder().build())
        } else {
            super.chapterListRequest(manga)
        }
    }

    override fun chapterListSelector() = "li.wp-manga-chapter"

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        val link = element.selectFirst("a")!!
        name = link.text().trim()
        setUrlWithoutDomain(link.absUrl("href"))
        date_upload = parseChapterDate(
            element.selectFirst("span.chapter-release-date i, span.chapter-release-date a")?.text()?.trim(),
        )
    }

    override fun pageListParse(document: Document): List<Page> {
        return document.select("div.reading-content img.wp-manga-chapter-img, div.page-break img")
            .mapIndexedNotNull { idx, img ->
                val url = imageFromElement(img) ?: return@mapIndexedNotNull null
                Page(idx, imageUrl = url)
            }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    protected open fun imageFromElement(element: Element): String? {
        return listOf("data-src", "data-lazy-src", "data-cfsrc", "src")
            .firstNotNullOfOrNull { attr ->
                element.absUrl(attr).trim().takeIf { it.isNotEmpty() && !it.startsWith("data:") }
            }
    }

    protected open fun parseChapterDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        val lower = date.lowercase(Locale.US)
        if ("ago" in lower) {
            val number = Regex("""\d+""").find(lower)?.value?.toIntOrNull() ?: return 0L
            val cal = Calendar.getInstance()
            when {
                "min" in lower -> cal.add(Calendar.MINUTE, -number)
                "hour" in lower -> cal.add(Calendar.HOUR_OF_DAY, -number)
                "day" in lower -> cal.add(Calendar.DAY_OF_MONTH, -number)
                "week" in lower -> cal.add(Calendar.WEEK_OF_YEAR, -number)
                "month" in lower -> cal.add(Calendar.MONTH, -number)
                "year" in lower -> cal.add(Calendar.YEAR, -number)
            }
            return cal.timeInMillis
        }
        return runCatching { dateFormat.parse(date)?.time ?: 0L }.getOrDefault(0L)
    }

    override fun getFilterList() = FilterList(
        OrderByFilter(),
        StatusFilter(),
    )

    private class OrderByFilter : Filter.Select<String>(
        "Order by",
        arrayOf("Latest", "A-Z", "Rating", "Trending", "Most Views", "New"),
    ) {
        fun toUriPart() = when (state) {
            0 -> "latest"
            1 -> "alphabet"
            2 -> "rating"
            3 -> "trending"
            4 -> "views"
            else -> "new-manga"
        }
    }

    private class StatusCheckBox(name: String, val id: String) : Filter.CheckBox(name)

    private class StatusFilter : Filter.Group<StatusCheckBox>(
        "Status",
        listOf(
            StatusCheckBox("Ongoing", "on-going"),
            StatusCheckBox("Completed", "end"),
            StatusCheckBox("On Hold", "on-hold"),
            StatusCheckBox("Canceled", "canceled"),
        ),
    )

    override fun popularMangaParse(response: Response): MangasPage = super.popularMangaParse(response)
}
