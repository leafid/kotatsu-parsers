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

    // 域名
    override val configKeyDomain = ConfigKey.Domain("www.bilimanga.net")

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

    // ===============
    // 1. 榜单列表页
    // /top/weekvisit/{page}.html
    // ===============
    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val url = "https://$domain/top/weekvisit/$page.html"
        val doc = webClient.httpGet(url).parseHtml()
        return parseRankingList(doc)
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

        return doc.select("img.imagecontent").mapIndexedNotNull { index, img ->
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
}
/**
 * 小工具：把简单的 HTML 文本里标签干掉
 *（非常粗暴的版本，够用就行）
 */
private fun String.stripHtml(): String =
    this.replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .trim()
