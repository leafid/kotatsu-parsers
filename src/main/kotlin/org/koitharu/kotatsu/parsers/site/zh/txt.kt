package org.koitharu.kotatsu.parsers.site.zh

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.EnumSet

@MangaSourceParser("BILIMANGA", "BiliManga", "zh")
internal class BiliManga(
    context: MangaLoaderContext,
) : PagedMangaParser(
    context,
    MangaParserSource.BILIMANGA,
    pageSize = 20,
) {

    // 域名配置
    override val configKeyDomain = ConfigKey.Domain("www.bilimanga.net")

    // 基础URL
    private val baseUrl get() = "https://$domain"

    // 支持的排序方式
    override val availableSortOrders: Set<SortOrder> =
        EnumSet.of(SortOrder.POPULARITY)

    // 过滤能力配置
    override val filterCapabilities: MangaListFilterCapabilities =
        MangaListFilterCapabilities(
            isSearchSupported = false,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions =
        MangaListFilterOptions()

    // ====================
    // 关键修改：重写 intercept，而不是 webClient.addInterceptor(...)
    // ====================
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url
        val host = url.host.lowercase()

        // 需要添加 Referer 的域名列表（主站 + 图片 CDN）
        val needProtectedHeaders = host.contains("bilimanga") ||
            host.contains("motiezw") ||
            host.contains("bilicdn") ||
            host.contains("biliapi")

        val newRequestBuilder: Request.Builder = originalRequest.newBuilder()
            // 使用 context.getDefaultUserAgent()，而不是 context.defaultUserAgent
            .header("User-Agent", context.getDefaultUserAgent())
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
            )
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("Connection", "keep-alive")
            .header("Cache-Control", "no-cache")

        if (needProtectedHeaders) {
            newRequestBuilder
                .header("Referer", "$baseUrl/")
                .header("Origin", baseUrl)
                .header("X-Requested-With", "XMLHttpRequest")
        }

        val newRequest = newRequestBuilder.build()
        return chain.proceed(newRequest)
    }

    // ====================
    // 1. 榜单列表页解析
    // ====================
    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val url = "$baseUrl/top/weekvisit/$page.html"
        val doc = webClient.httpGet(url).parseHtml()
        return parseRankingList(doc)
    }

    private fun parseRankingList(doc: Document): List<Manga> {
        return doc.select("ol#list_content li.book-li a.book-layout").map { a ->
            val href = a.attrAsRelativeUrl("href")
            val mangaUrl = href.toAbsoluteUrl(domain)

            val title = a.selectFirst(".book-title")?.text().orEmpty()
            val coverUrl = a.selectFirst("img")?.let { img ->
                (img.attr("data-src").takeIf { it.isNotBlank() } ?: img.attr("src"))
                    .toAbsoluteUrl(domain)
            }.orEmpty()

            val author = a.selectFirst(".book-author")?.text()?.trim().orEmpty()

            Manga(
                id = generateUid(mangaUrl),
                url = mangaUrl,
                publicUrl = mangaUrl,
                coverUrl = coverUrl,
                title = title,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                authors = setOfNotNull(author.takeIf { it.isNotEmpty() }),
                state = null,
                source = source,
                contentRating = null,
                description = "",
                chapters = emptyList()
            )
        }
    }

    // ====================
    // 2. 漫画详情页解析
    // ====================
    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url).parseHtml()

        val description = doc.selectFirst("content")
            ?.html()
            ?.replace(Regex("<br\\s*/?>"), "\n")
            ?.stripHtml()
            .orEmpty()

        val tags: Set<MangaTag> = doc.select("span.tag-small-group em.tag-small a")
            .map { a ->
                val name = a.text().trim()
                MangaTag(
                    key = name,
                    title = name,
                    source = source,
                )
            }.toSet()

        val state = doc.selectFirst(".status-tag")?.text()?.let { statusText ->
            when {
                statusText.contains("连载", ignoreCase = true) -> MangaState.ONGOING
                statusText.contains("完结", ignoreCase = true) -> MangaState.FINISHED
                else -> null
            }
        }

        val chapters = loadChaptersFor(manga)

        return manga.copy(
            description = description,
            tags = tags,
            state = state,
            chapters = chapters
        )
    }

    // ====================
    // 3. 章节列表加载
    // ====================
    private suspend fun loadChaptersFor(manga: Manga): List<MangaChapter> {
        val mangaId = manga.url.substringAfter("/detail/").substringBefore(".html")
        val catalogUrl = "$baseUrl/read/$mangaId/catalog"

        val doc = webClient.httpGet(catalogUrl).parseHtml()
        val items = doc.select("li.chapter-li.jsChapter a.chapter-li-a")

        return items.reversed().mapIndexed { index, a ->
            val href = a.attrAsRelativeUrl("href")
            val url = href.toAbsoluteUrl(domain)

            val chapterIndex = a.selectFirst("span.chapter-index")?.text()?.trim()
            val title = chapterIndex.takeIf { !it.isNullOrBlank() } ?: "第${index + 1}话"

            MangaChapter(
                id = generateUid(url),
                title = title,
                number = (index + 1).toFloat(),
                volume = 0,
                url = url,
                scanlator = null,
                uploadDate = 0,
                branch = null,
                source = source
            )
        }
    }

    // ====================
    // 4. 章节图片解析
    // ====================
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url).parseHtml()

        return doc.select("div#acontentz img.imagecontent").mapIndexedNotNull { index, img ->
            val imageUrl = img.attr("data-src").takeIf { it.isNotBlank() }
                ?: img.attr("src")

            if (imageUrl.isBlank() ||
                imageUrl.contains("sloading", ignoreCase = true) ||
                imageUrl.contains("placeholder", ignoreCase = true)
            ) {
                return@mapIndexedNotNull null
            }

            val fullImageUrl = imageUrl.toAbsoluteUrl(domain)

            MangaPage(
                id = generateUid("$fullImageUrl#$index"),
                url = fullImageUrl,
                preview = null,
                source = source
            )
        }
    }

    private fun String.stripHtml(): String = this
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("\\s+"), " ")
        .trim()
}
