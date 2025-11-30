package org.koitharu.kotatsu.parsers.site.zh

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.EnumSet
import java.util.LinkedHashMap

@MangaSourceParser("BILIMANGA", "BiliManga", "zh")
internal class BiliManga(
    context: MangaLoaderContext,
) : PagedMangaParser(
    context,
    MangaParserSource.BILIMANGA,   // 记得在 MangaParserSource 里加 BILIMANGA
    pageSize = 20,
) {

    // 域名配置
    override val configKeyDomain = ConfigKey.Domain("www.bilimanga.net")

    // 先只支持一个排序：人气（周点击）
    override val availableSortOrders: Set<SortOrder> =
        EnumSet.of(SortOrder.POPULARITY)

    // 你的 fork 版本里构造比较简单，就只开搜索开关这一项
    override val filterCapabilities: MangaListFilterCapabilities =
        MangaListFilterCapabilities(
            isSearchSupported = false,
        )

    // 暂时没有额外筛选项
    override suspend fun getFilterOptions(): MangaListFilterOptions =
        MangaListFilterOptions()

    /**
     * 列表页：周点击榜
     * /top/weekvisit/{page}.html
     */
    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val url = "https://$domain/top/weekvisit/$page.html"
        val doc = webClient.httpGet(url).parseHtml()
        return parseRankingList(doc)
    }

    /** 解析榜单列表 li.book-li */
    private fun parseRankingList(doc: Document): List<Manga> =
        doc.select("ol#list_content li.book-li a.book-layout").map { a ->
            val href = a.attrAsRelativeUrl("href")           // /detail/145.html
            val mangaUrl = href.toAbsoluteUrl(domain)

            val title = a.selectFirst(".book-title")?.text().orEmpty()
            val coverUrl = a.selectFirst("img")?.src().orEmpty()

            // 作者选择器先随便写一个，后面可以再对着页面改
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
            )
        }

    /**
     * 详情页：
     * 1. 解析简介、状态
     * 2. 从详情页 URL 中解析漫画 id（例如 /detail/145.html -> 145）
     * 3. 请求 /read/{id}/catalog 解析章节 + 卷
     */
    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url).parseHtml()

        val description = doc.selectFirst(".book-desc")?.text().orEmpty()

        val state = doc.selectFirst(".status-tag")?.text()?.let {
            when {
                it.contains("连载") -> MangaState.ONGOING
                it.contains("完结") -> MangaState.FINISHED
                else -> null
            }
        }

        // 从 URL 中提取漫画 ID（优先匹配 /detail/145 这种）
        val mangaId = extractMangaId(manga.url)

        val chapters = if (mangaId != null) {
            runCatching { loadCatalogChapters(mangaId) }.getOrElse { emptyList() }
        } else {
            emptyList()
        }

        return manga.copy(
            description = description,
            state = state,
            chapters = chapters,
        )
    }

    /** 从详情页 URL 中提取漫画 ID，例如 /detail/145.html -> 145 */
    private fun extractMangaId(url: String): String? {
        // 优先匹配 /detail/145 /detail/145.html
        val detailMatch = Regex("/detail/(\\d+)").find(url)
        if (detailMatch != null) {
            return detailMatch.groupValues[1]
        }
        // 兜底：抓第一个数字串
        return Regex("(\\d+)").find(url)?.groupValues?.get(1)
    }

    /**
     * 解析 /read/{id}/catalog：
     *
     * 章节：
     *   <li class="chapter-li jsChapter">
     *     <a href="/read/145/10581.html" class="chapter-li-a ">
     *       <span class="chapter-index ">第２擊</span>
     *     </a>
     *   </li>
     *
     * 卷：
     *   <div class="vloume-info">
     *     <div class="chapter-bar"><h3>英雄大全</h3></div>
     *     ...
     *   </div>
     *
     * 思路：按文档顺序遍历 div.vloume-info 和 li.chapter-li.jsChapter，
     * 看到卷就更新 currentVolumeName，看到章节就挂在当前卷下面。
     */
    private suspend fun loadCatalogChapters(mangaId: String): List<MangaChapter> {
        val url = "https://$domain/read/$mangaId/catalog"
        val doc = webClient.httpGet(url).parseHtml()

        // 如果整个页面连一个章节 li 都没有，就直接返回空
        if (doc.select("li.chapter-li.jsChapter").isEmpty()) {
            return emptyList()
        }

        val result = mutableListOf<MangaChapter>()

        // 卷名 -> 卷号（1、2、3...）
        val volumeIndexByName = LinkedHashMap<String, Int>()
        var nextVolumeIndex = 1
        var currentVolumeName: String? = null

        // 混合选择卷和章节，保证按照页面顺序处理
        val elements = doc.select("div.vloume-info, li.chapter-li.jsChapter")

        var chapterIndex = 0

        for (el in elements) {
            when {
                // 卷信息块
                el.hasClass("vloume-info") -> {
                    val name = el.selectFirst("h3")
                        ?.text()
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                    currentVolumeName = name
                }

                // 章节 li
                el.hasClass("chapter-li") && el.hasClass("jsChapter") -> {
                    val a = el.selectFirstOrThrow("a.chapter-li-a")
                    val href = a.attrAsRelativeUrl("href")           // /read/145/10581.html
                    val chapterUrl = href.toAbsoluteUrl(domain)

                    val title = a.selectFirst(".chapter-index")
                        ?.text()
                        ?.trim()
                        .orEmpty()

                    // 当前卷对应的卷号，没有卷名就用 0
                    val volumeNumber = currentVolumeName?.let { name ->
                        volumeIndexByName.getOrPut(name) { nextVolumeIndex++ }
                    } ?: 0

                    chapterIndex += 1

                    val chapter = MangaChapter(
                        id = generateUid(chapterUrl),
                        title = title,                      // 第２擊
                        number = chapterIndex.toFloat(),    // 1, 2, 3...
                        volume = volumeNumber,              // 0 表示没卷/未知
                        url = chapterUrl,
                        uploadDate = 0,                     // 页面里没时间，就先写 0
                        branch = null,
                        scanlator = null,
                        source = source,
                    )
                    result += chapter
                }
            }
        }

        return result
    }

    /**
     * 阅读页：解析章节里的图片列表
     * 这里的选择器 .chapter-img img 是占位，之后可以根据
     * /read/145/10581.html 的真实结构再改。
     */
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url).parseHtml()

        return doc.select(".chapter-img img").mapIndexedNotNull { index, img ->
            val raw = img.src()                 // String?（当前版本里可能是可空）
            val src = raw?.toAbsoluteUrl(domain) ?: return@mapIndexedNotNull null

            MangaPage(
                id = generateUid(src + "#$index"),
                url = src,
                preview = null,
                source = source,
            )
        }
    }
}
