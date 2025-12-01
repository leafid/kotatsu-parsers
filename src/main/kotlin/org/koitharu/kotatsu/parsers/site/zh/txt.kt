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
    // 核心：配置全局请求拦截器（添加必要的请求头）
    // ====================
    init {
        // 注册全局拦截器，为所有请求添加必要的头信息
        webClient.addInterceptor(HeadersInterceptor())
    }

    /**
     * 请求头拦截器：
     * - 为所有请求添加必要的Referer、Origin、User-Agent等头
     * - 确保图片CDN能正常返回图片，不会被拦截
     */
    private inner class HeadersInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()
            val url = originalRequest.url
            val host = url.host.lowercase()

            // 需要添加Referer的域名列表（主站 + 图片CDN）
            val needProtectedHeaders = host.contains("bilimanga") ||
                host.contains("motiezw") ||
                host.contains("bilicdn") ||
                host.contains("biliapi")

            val newRequestBuilder: Request.Builder = originalRequest.newBuilder()
                // 基础浏览器UA，避免被识别为爬虫
                .header("User-Agent", context.defaultUserAgent)
                // 接受的内容类型
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Accept-Encoding", "gzip, deflate, br")
                // 保持连接
                .header("Connection", "keep-alive")
                // 不缓存
                .header("Cache-Control", "no-cache")

            // 为受保护的域名添加Referer和Origin
            if (needProtectedHeaders) {
                newRequestBuilder
                    .header("Referer", baseUrl + "/")
                    .header("Origin", baseUrl)
                    // 添加X-Requested-With，模拟AJAX请求
                    .header("X-Requested-With", "XMLHttpRequest")
            }

            // 构建新请求并执行
            val newRequest = newRequestBuilder.build()
            return chain.proceed(newRequest)
        }
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
                // 优先取data-src，其次src，并补全绝对URL
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

        // 解析简介
        val description = doc.selectFirst("content")
            ?.html()
            ?.replace(Regex("<br\\s*/?>"), "\n") // 匹配所有br标签
            ?.stripHtml()
            .orEmpty()

        // 解析标签
        val tags: Set<MangaTag> = doc.select("span.tag-small-group em.tag-small a")
            .map { a ->
                val name = a.text().trim()
                MangaTag(
                    key = name,
                    title = name,
                    source = source,
                )
            }.toSet()

        // 解析连载状态
        val state = doc.selectFirst(".status-tag")?.text()?.let { statusText ->
            when {
                statusText.contains("连载", ignoreCase = true) -> MangaState.ONGOING
                statusText.contains("完结", ignoreCase = true) -> MangaState.FINISHED
                else -> null
            }
        }

        // 加载章节列表
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

        // 反转章节顺序（最新的在最后）
        return items.reversed().mapIndexed { index, a ->
            val href = a.attrAsRelativeUrl("href")
            val url = href.toAbsoluteUrl(domain)

            val chapterIndex = a.selectFirst("span.chapter-index")?.text()?.trim()
            val title = chapterIndex.takeIf { it.isNullOrBlank().not() } ?: "第${index + 1}话"

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
            // 优先获取data-src，其次src
            val imageUrl = img.attr("data-src").takeIf { it.isNotBlank() }
                ?: img.attr("src")

            // 过滤占位图和空URL
            if (imageUrl.isBlank() ||
                imageUrl.contains("sloading", ignoreCase = true) ||
                imageUrl.contains("placeholder", ignoreCase = true)) {
                return@mapIndexedNotNull null
            }

            // 补全绝对URL
            val fullImageUrl = imageUrl.toAbsoluteUrl(domain)

            MangaPage(
                id = generateUid("$fullImageUrl#$index"),
                url = fullImageUrl,
                preview = null,
                source = source
            )
        }
    }

    /**
     * 增强版HTML清理工具
     */
    private fun String.stripHtml(): String = this
        .replace(Regex("<[^>]+>"), "") // 移除所有HTML标签
        .replace("&nbsp;", " ")       // 替换空格实体
        .replace("&amp;", "&")        // 替换&实体
        .replace("&lt;", "<")         // 替换<实体
        .replace("&gt;", ">")         // 替换>实体
        .replace(Regex("\\s+"), " ")  // 合并多个空格
        .trim()
}
