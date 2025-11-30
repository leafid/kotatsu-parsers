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
    MangaParserSource.BILIMANGA,
    pageSize = 20,
) {

    override val configKeyDomain = ConfigKey.Domain("www.bilimanga.net")

    override val availableSortOrders: Set<SortOrder> =
        EnumSet.of(SortOrder.POPULARITY)

    override val filterCapabilities: MangaListFilterCapabilities =
        MangaListFilterCapabilities(
            isSearchSupported = false,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions =
        MangaListFilterOptions()

    /** 榜单页：周点击榜 /top/weekvisit/{page}.html */
    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val url = "https://$domain/top/weekvisit/$page.html"
        val doc = webClient.httpGet(url).parseHtml()
        return parseRankingList(doc)
    }

    /** 解析榜单 li.book-li */
    private fun parseRankingList(doc: Document): List<Manga> =
        doc.select("ol#list_content li.book-li a.book-layout").map { a ->
            val href = a.attrAsRelativeUrl("href") // /detail/145.html
            val mangaUrl = href.toAbsoluteUrl(domain)

            val title = a.selectFirst(".book-title")?.text().orEmpty()
            val coverUrl = a.selectFirst("img")?.src().orEmpty()
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
     * 详情页 + 章节目录：
     *  - 详情：https://www.bilimanga.net/detail/145.html
     *  - 目录：https://www.bilimanga.net/read/145/catalog
     */
    override suspend fun getDetails(manga: Manga): Manga {
        // 1. 先从详情页拿简介、状态
        val detailDoc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        val description = detailDoc.selectFirst(".book-desc")?.text().orEmpty()

        val state = detailDoc.selectFirst(".status-tag")?.text()?.let {
            when {
                it.contains("連載") || it.contains("连载") -> MangaState.ONGOING
                it.contains("完結") || it.contains("完结") -> MangaState.FINISHED
                else -> null
            }
        }

        // 2. 从 url 里提取 id（例：/detail/145.html -> 145）
        val id = manga.url.substringAfterLast("/").substringBefore(".")
        val catalogUrl = "https://$domain/read/$id/catalog"

        // 3. 打开目录页，解析章节 li.chapter-li.jsChapter
        val catalogDoc = webClient.httpGet(catalogUrl).parseHtml()

        // 如果想以后支持“按卷”分组，可以用 div.vloume-info 去包一层
        val chapterElements = catalogDoc.select("li.chapter-li.jsChapter a.chapter-li-a")

        // 一般目录是【最新在上面】，所以 reversed = true
        val chapters = chapterElements.mapChapters(reversed = true) { index, a ->
            val chapterUrl = a.attrAsRelativeUrl("href").toAbsoluteUrl(domain) // /read/145/10590.html
            val titleText = a.selectFirst(".chapter-index")?.text()?.trim().orEmpty()

            MangaChapter(
                id = generateUid(chapterUrl),
                title = if (titleText.isNotEmpty()) titleText else "第${index + 1}话",
                number = index + 1f,
                volume = 0,             // 先不区分卷，有需要再用 vloume-info 做分组
                url = chapterUrl,
                scanlator = null,
                uploadDate = 0,
                branch = null,
                source = source,
            )
        }

        return manga.copy(
            description = description,
            state = state,
            chapters = chapters,
        )
    }

    /**
     * 阅读页：解析图片
     * 例：/read/145/10590.html
     * 选择器根据实际 HTML 调整，这里先用 .chapter-img img 作为占位
     */
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url).parseHtml()

        return doc.select(".chapter-img img").mapIndexed { index, img ->
            // 注意这里不要再用 raw?.xxx 了，直接当非空字符串处理
            val src = img.src().toAbsoluteUrl(domain)

            MangaPage(
                id = generateUid(src + "#$index"),
                url = src,
                preview = null,
                source = source,
            )
        }
    }
}
