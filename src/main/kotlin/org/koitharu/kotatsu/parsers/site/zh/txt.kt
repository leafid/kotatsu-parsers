package org.koitharu.kotatsu.parsers.site.zh

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParserSource
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

    // 只暴露一个排序（其实就是周点击榜）
    override val availableSortOrders: Set<SortOrder> =
        EnumSet.of(SortOrder.POPULARITY)

    // 当成排行榜用，不开搜索和其他筛选
    override val filterCapabilities: MangaListFilterCapabilities =
        MangaListFilterCapabilities(
            isSearchSupported = false,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions =
        MangaListFilterOptions()

    /**
     * 榜单页面：
     *   https://www.bilimanga.net/top/weekvisit/{page}.html
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

    /** 解析 ol#list_content li.book-li 里的漫画条目 */
    private fun parseRankingList(doc: Document): List<Manga> =
        doc.select("ol#list_content li.book-li a.book-layout").map { a ->
            val href = a.attrAsRelativeUrl("href")           // /detail/145.html
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
     * 详情页：
     *   https://www.bilimanga.net/detail/145.html
     *
     * 补：
     * - 简介（content / book-intro / book-desc）
     * - 标签（span.tag-small-group em.tag-small a）
     * - 状态（连载 / 完结）
     * - 章节列表（从 /read/{id}/catalog 抓）
     */
    override suspend fun getDetails(manga: Manga): Manga {
        // --- 1. 详情页本体 ---
        val detailDoc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        // 简介
        val description = detailDoc.selectFirst("div.book-intro content")
            ?.text()
            ?.takeIf { it.isNotBlank() }
            ?: detailDoc.selectFirst("div.book-intro")
                ?.text()
                ?.takeIf { it.isNotBlank() }
            ?: detailDoc.selectFirst(".book-desc")
                ?.text()
                .orEmpty()

        // 标签
        val tags = detailDoc
            .select("span.tag-small-group em.tag-small a")
            .mapNotNullToSet<MangaTag> { a ->
                val name = a.text().trim()
                if (name.isEmpty()) return@mapNotNullToSet null

                val href = a.attr("href")
                val key = href.substringAfter("lastupdate_", missingDelimiterValue = name)
                    .substringBefore('_')
                    .ifEmpty { name }

                MangaTag(
                    key = key,
                    title = name,
                    source = source,
                )
            }

        // 状态
        val state = detailDoc.selectFirst(".status-tag, .book-status")?.text()?.let {
            when {
                it.contains("連載") || it.contains("连载") -> MangaState.ONGOING
                it.contains("完結") || it.contains("完结") -> MangaState.FINISHED
                else -> null
            }
        }

        // --- 2. 章节目录 /read/{id}/catalog ---
        val idPart = manga.url.substringAfterLast('/').substringBefore(".html")
        val catalogUrl = "https://$domain/read/$idPart/catalog"
        val catalogDoc = webClient.httpGet(catalogUrl).parseHtml()

        val chapterLis = catalogDoc.select("li.chapter-li.jsChapter")

        val chapters = chapterLis.mapIndexed { index, li ->
            val a = li.selectFirstOrThrow("a.chapter-li-a")
            val href = a.attrAsRelativeUrl("href")      // /read/145/10580.html
            val chapterUrl = href.toAbsoluteUrl(domain)

            val title = a.selectFirst(".chapter-index")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: a.text().trim()

            MangaChapter(
                id = generateUid(chapterUrl),
                title = title,
                number = (index + 1).toFloat(),
                volume = 0,
                url = chapterUrl,
                scanlator = null,
                uploadDate = 0,
                branch = null,
                source = source,
            )
        }

        return manga.copy(
            description = description,
            tags = tags,
            state = state,
            chapters = chapters,
        )
    }

    /**
     * 单话阅读页：
     *   https://www.bilimanga.net/read/145/10580.html
     *
     * 图片都在：
     *   <div id="acontentz" class="bcontent">
     *     <img src="https://i.motiezw.com/0/145/10580/228882.avif"
     *          data-src="https://i.motiezw.com/0/145/10580/228882.avif"
     *          class="imagecontent lazyloaded">
     *     <img src="/images/sloading.svg"
     *          data-src="https://i.motiezw.com/0/145/10580/228889.avif"
     *          class="imagecontent lazyload">
     *   </div>
     */
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

        return doc.select("#acontentz img.imagecontent")
            .mapIndexedNotNull { index, img ->
                val dataSrc = img.attr("data-src")
                val srcAttr = img.attr("src")

                // 真实图片 URL：优先 data-src，src 里要排除占位图
                val raw = when {
                    dataSrc.isNotBlank() -> dataSrc
                    srcAttr.isNotBlank() && !srcAttr.contains("/images/sloading.svg") -> srcAttr
                    else -> return@mapIndexedNotNull null
                }

                val url = if (raw.startsWith("http://") || raw.startsWith("https://")) {
                    raw
                } else {
                    raw.toAbsoluteUrl(domain)
                }

                MangaPage(
                    id = generateUid("$url#$index"),
                    url = url,
                    preview = null,
                    source = source,
                )
            }
    }
}
