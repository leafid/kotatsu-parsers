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
    MangaParserSource.BILIMANGA,   // 记得在 MangaParserSource 枚举里加 BILIMANGA
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
            val href = a.attrAsRelativeUrl("href")           // /detail/24.html
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
     * 详情页：先做简单版，只补一点简介和状态
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
     * 阅读页：解析章节里的图片列表
     * 选择器 .chapter-img img 只是占位，后续可以根据实际 HTML 再改
     */
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url).parseHtml()

        return doc.select(".chapter-img img").mapIndexedNotNull { index, img ->
            val raw = img.src()                 // String?（在你这个版本里可能是可空）
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
