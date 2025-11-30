package org.koitharu.kotatsu.parsers.site.zh

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
    MangaParserSource.BILIMANGA,   // 确保你已经在 MangaParserSource enum 里加了这个值
    pageSize = 20,                 // 每页 20 本，随便定
) {

    // 域名配置
    override val configKeyDomain = ConfigKey.Domain("www.bilimanga.net")

    // 先只支持一个排序：人气（周点击）
    override val availableSortOrders: Set<SortOrder> =
        EnumSet.of(SortOrder.POPULARITY)

    // 你的库版本里构造参数比较少，用最简单的就行
    override val filterCapabilities: MangaListFilterCapabilities =
        MangaListFilterCapabilities(
            isSearchSupported = false,
        )

    // 没有额外筛选项，直接空
    override suspend fun getFilterOptions(): MangaListFilterOptions =
        MangaListFilterOptions()

    /**
     * 这里是真正拉“周点击榜”列表的地方
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
            val href = a.attrAsRelativeUrl("href")           // /detail/24.html
            val mangaUrl = href.toAbsoluteUrl(domain)

            val title = a.selectFirst(".book-title")?.text().orEmpty()
            val coverUrl = a.selectFirst("img")?.src().orEmpty()

            // 作者元素可能在别处，你后面可以再对着改选择器
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
     * 详情页：先做一个最简单版本，只补一点状态和简介，
     * 以后你要解析章节列表，再在这里加就行
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

        return manga.copy(
            description = description,
            state = state,
        )
    }

    /**
     * 章节阅读页：解析图片列表
     * （选择器先随便写一个，能编译就行，之后你再对着实际 HTML 改）
     */
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
    val doc = webClient.httpGet(chapter.url).parseHtml()

    // src() 可能为 null，用 mapIndexedNotNull 做一次过滤
    return doc.select(".chapter-img img").mapIndexedNotNull { index, img ->
        val raw = img.src()                       // String?
        val src = raw?.toAbsoluteUrl(domain) ?: return@mapIndexedNotNull null

        MangaPage(
            id = generateUid(src + "#$index"),
            url = src,
            preview = null,
            source = source,
        )
    }
}

