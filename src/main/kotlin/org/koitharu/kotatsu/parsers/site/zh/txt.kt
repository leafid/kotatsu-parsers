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
            val href = a.attrAsRelativeUrl("href")           // 例如 /detail/145.html
            val mangaUrl = href.toAbsoluteUrl(domain)

            val title = a.selectFirst(".book-title")?.text().orEmpty()
            val coverUrl = a.selectFirst("img")?.src().orEmpty()

            // 作者选择器只是占位，后续可以对着页面再改
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
     * 2. 从详情页 URL 里提取漫画 id（例如 145）
     * 3. 请求 /read/{id}/catalog，解析章节 + 卷
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

        // 从 URL 里提取漫画 ID（优先第一个数字串，例如 /detail/145.html -> 145）
        val mangaId = Regex("(\\d+)").find(manga.url)?.groupValues?.get(1)

        // 拉取章节目录：/read/{id}/catalog
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

    /**
     * 解析 /read/{id}/catalog 页面：
     * - <li class="chapter-li jsChapter"> … <span class="chapter-index">第２擊</span>
     * - 上方最近的 <div class="vloume-info"><h3>英雄大全</h3> 是卷信息
     */
    private suspend fun loadCatalogChapters(mangaId: String): List<MangaChapter> {
        val url = "https://$domain/read/$mangaId/catalog"
        val doc = webClient.httpGet(url).parseHtml()

        val chapterLis = doc.select("li.chapter-li.jsChapter")
        if (chapterLis.isEmpty()) return emptyList()

        // 把每个卷名映射成一个卷号：1, 2, 3...
        val volumeIndexByName = LinkedHashMap<String, Int>()
        var nextVolumeIndex = 1

        return chapterLis.mapIndexed { index, li ->
            val a = li.selectFirstOrThrow("a.chapter-li-a")
            val href = a.attrAsRelativeUrl("href")           // 例如 /read/145/10581.html
            val chapterUrl = href.toAbsoluteUrl(domain)

            val title = li.selectFirst(".chapter-index")?.text()?.trim().orEmpty()

            // 找到最近的上级 <div class="vloume-info"><h3>卷名</h3>…</div>
            val parents = li.parents()
            val volumeElement = parents.firstOrNull { it.hasClass("vloume-info") }
            val volumeName = volumeElement
                ?.selectFirst("h3")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            val volumeNumber = volumeName?.let { name ->
                volumeIndexByName.getOrPut(name) { nextVolumeIndex++ }
            } ?: 0

            MangaChapter(
                id = generateUid(chapterUrl),
                title = title,                      // 例如：第２擊
                number = (index + 1).toFloat(),     // 简单按顺序编号
                volume = volumeNumber,              // 0 表示没有卷 / 未识别
                url = chapterUrl,
                scanlator = null,
                uploadDate = 0,
                branch = volumeName,                // 把卷名放在 branch 里（比如 “英雄大全”）
                source = source,
            )
        }
    }

    /**
     * 阅读页：解析章节里的图片列表
     * 这里的选择器 .chapter-img img 是占位，之后你可以根据
     * /read/145/10581.html 的实际结构再改。
     */
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url).parseHtml()

        return doc.select(".chapter-img img").mapIndexedNotNull { index, img ->
            val raw = img.src()                 // String?（当前版本可能可空）
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
