package org.koitharu.kotatsu.parsers.site.zh

import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import java.util.EnumSet

@MangaSourceParser("BILIMANGA", "BiliManga", "zh")
internal class BiliManga(
    context: MangaLoaderContext,
) : PagedMangaParser(
    context,
    MangaParserSource.BILIMANGA,
    pageSize = 20,
), Interceptor {

    // 域名
    override val configKeyDomain = ConfigKey.Domain("www.bilimanga.net")

    // 使用桌面版 UA，降低被拦截概率
    override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    // 先只做“人气/点击”这一种排序
    override val availableSortOrders: Set<SortOrder> =
        EnumSet.of(SortOrder.POPULARITY)

    // 只开启最简单的功能
    override val filterCapabilities: MangaListFilterCapabilities =
        MangaListFilterCapabilities(
            isSearchSupported = false,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions =
        MangaListFilterOptions()

    // 简单的第一页列表缓存，返回上次已加载的数据以避免返回时闪烁刷新
    @Volatile
    private var firstPageCache: List<Manga>? = null

    // ===============
    // 1. 榜单列表页
    // /top/weekvisit/{page}.html
    // ===============
    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        // 如果是第一页并且已有缓存，则直接返回缓存数据
        if (page == paginator.firstPage) {
            val cached = firstPageCache
            if (cached != null) {
                return cached
            }
        }

        val url = "https://$domain/top/weekvisit/$page.html"
        val doc = webClient.httpGet(url).parseHtml()
        val list = parseRankingList(doc)

        // 只缓存第一页数据
        if (page == paginator.firstPage) {
            firstPageCache = list
        }

        return list
    }

    private fun parseRankingList(doc: Document): List<Manga> {
        return doc.select("ol#list_content li.book-li a.book-layout").map { a ->
            val href = a.attrAsRelativeUrl("href")          // /detail/145.html
            val mangaUrl = href.toAbsoluteUrl(domain)

            val title = a.selectFirst(".book-title")?.text().orEmpty()
            val coverUrl = a.selectFirst("img")?.src().orEmpty()
            val author = a.selectFirst(".book-author")?.text()
                ?.trim()
                .orEmpty()

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
            )
        }
    }

    // ================
    // 2. 详情页
    // https://www.bilimanga.net/detail/145.html
    // 解析简介 + 标签 + 状态，并顺便解析章节（通过 /read/{id}/catalog）
    // ================
    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url).parseHtml()

        // 简介：<content> ... </content>
        val description = doc.selectFirst("content")
            ?.html()   // 保留 <br>
            ?.replace("<br>", "\n")
            ?.replace("<br/>", "\n")
            ?.replace("<br />", "\n")
            ?.stripHtml()
            .orEmpty()

        // 标签：span.tag-small-group em.tag-small a
        val tagElements = doc.select("span.tag-small-group em.tag-small a")
        val tags: Set<MangaTag> = tagElements.map { a ->
            val name = a.text().trim()
            MangaTag(
                key = name,      // 直接用文字当 key 即可
                title = name,
                source = source,
            )
        }.toSet()

        // 连载状态（页面上如果有的话就解析一下）
        val state = doc.selectFirst(".status-tag")?.text()?.let {
            when {
                it.contains("连载") || it.contains("連載") -> MangaState.ONGOING
                it.contains("完结") || it.contains("完結") -> MangaState.FINISHED
                else -> null
            }
        }

        // 章节：转到 /read/{id}/catalog
        val chapters = loadChaptersFor(manga)

        return manga.copy(
            description = description,
            tags = tags,
            state = state,
            chapters = chapters,
        )
    }

    // 解析 /read/{id}/catalog 里的章节列表
    private suspend fun loadChaptersFor(manga: Manga): List<MangaChapter> {
        val mangaId = manga.url.substringAfter("/detail/")
            .substringBefore(".html")
        val catalogUrl = "https://$domain/read/$mangaId/catalog"

        val doc = webClient.httpGet(catalogUrl).parseHtml()

        val items = doc.select("li.chapter-li.jsChapter a.chapter-li-a")

        // 网站一般是“最新在上面”，我们这里转成从第一话开始的顺序
        val raw = items.mapIndexed { index, a ->
            val href = a.attrAsRelativeUrl("href")            // /read/145/10590.html
            val url = href.toAbsoluteUrl(domain)
            val title = a.selectFirst("span.chapter-index")
                ?.text()
                ?.trim()
                .orEmpty()
                .ifEmpty { "第${index + 1}话" }

            MangaChapter(
                id = generateUid(url),
                title = title,
                number = (index + 1).toFloat(),
                volume = 0,
                url = url,
                scanlator = null,
                uploadDate = 0,
                branch = null,
                source = source,
            )
        }

        return raw.asReversed()   // 反转一下：第1话在前
    }

    // ================
    // 3. 章节阅读页 -> 图片列表
    // https://www.bilimanga.net/read/145/10590.html
    // img.imagecontent，优先取 data-src，其次 src
    // ================
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url).parseHtml()

        return doc.select("div#acontentz img.imagecontent").mapIndexedNotNull { index, img ->
            // 1. 先拿真正的图片地址（data-src）
            val candidate = img.attr("data-src").takeIf { it.isNotBlank() }
                ?: img.attr("src")

            // 2. 过滤掉占位图 / 空串
            if (candidate.isNullOrBlank() || candidate.startsWith("/images/sloading")) {
                return@mapIndexedNotNull null
            }

            // 3. 处理相对 / 绝对 URL
            val url = candidate.toAbsoluteUrl(domain)

            MangaPage(
                id = generateUid(url + "#$index"),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    /**
     * 关键拦截器：
     *
     * - 为图片请求补充必要的头（主要是 Referer），避免部分服务端拒绝。
     * - 使用桌面版 UA，降低被拦截概率。
     * - 对 bilimanga 本身和图片 CDN 域名的请求，强制加上 Referer/Origin。
     */
    override fun intercept(chain: Interceptor.Chain): Response {
    val original = chain.request()
    val url = original.url
    val host = url.host.lowercase()

    // Add headers for Bilimanga site and its CDN domains to bypass Cloudflare
    val needsHeaders = host.contains("bilimanga.net") ||
                       host.contains("motiezw.com") || 
                       host.contains("bilimicro.top") // include known image/CDN hosts
    return if (needsHeaders) {
        val newRequest = original.newBuilder()
            // Simulate browser headers for Cloudflare
            .header("Referer", "https://${domain}/")  // Pretend origin is Bilimanga site
            .header("Origin", "https://${domain}")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 6 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.5735.130 Mobile Safari/537.36")
            .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Cache-Control", "no-cache")
            .build()
        chain.proceed(newRequest)
    } else {
        chain.proceed(original)
        }
    }
}

/**
 * 小工具：把简单的 HTML 文本里标签干掉
 *（非常粗暴的版本，够用就行）
 */
private fun String.stripHtml(): String =
    this.replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .trim()
