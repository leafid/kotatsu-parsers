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
    MangaParserSource.BILIMANGA,   // 记得在 MangaParserSource 里有这个常量
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

    // =============== 列表页（周点击榜） =================

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

            // 作者（如果取不到就空）
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
                authors = if (author.isNotEmpty()) setOf(author) else emptySet(),
                state = null,
                source = source,
                contentRating = null,
            )
        }

    // =============== 详情页（简介 + 标签 + 章节） =================

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url).parseHtml()

        // 简介在 <content> 里，是带 <br> 的 HTML 文本
        val rawDesc = doc.selectFirst("content")?.html().orEmpty()
        val description = rawDesc.cleanHtml()

        // 标签：span.tag-small-group a
        val tagElements = doc.select("span.tag-small-group a")
        val tags: Set<MangaTag> = tagElements
            .mapNotNull { a ->
                val name = a.text().trim()
                if (name.isEmpty()) return@mapNotNull null
                MangaTag(
                    key = a.attr("href"), // 随便存一个唯一 key，这里用 filter 链接
                    title = name,
                    source = source,
                )
            }
            .toSet()

        // 连载状态（页面上如果有的话）
        val state = doc.selectFirst(".status-tag")?.text()?.let {
            when {
                it.contains("连载") || it.contains("連載") -> MangaState.ONGOING
                it.contains("完结") || it.contains("完結") -> MangaState.FINISHED
                else -> null
            }
        }

        // 章节列表在 /read/{id}/catalog 里单独的页面，这里也一起抓
        val chapters = loadChapters(manga.url)

        return manga.copy(
            description = description,
            state = state,
            tags = tags,
            chapters = chapters,
        )
    }

    /**
     * 根据详情页 URL 推出漫画 id，然后请求
     *   /read/{id}/catalog
     * 解析章节 li.chapter-li.jsChapter
     */
    private suspend fun loadChapters(detailUrl: String): List<MangaChapter> {
        // 例如：https://www.bilimanga.net/detail/145.html
        val id = detailUrl
            .substringAfterLast('/')
            .substringBefore('.')

        val catalogUrl = "https://$domain/read/$id/catalog"
        val doc = webClient.httpGet(catalogUrl).parseHtml()

        // 卷：<div class="vloume-info"> 里有 h3 卷名
        val volumeNames: MutableMap<Int, String> = mutableMapOf()
        doc.select("div.vloume-info").forEachIndexed { index, v ->
            val name = v.selectFirst("h3")?.text()?.trim().orEmpty()
            if (name.isNotEmpty()) {
                // 卷号从 1 开始
                volumeNames[index + 1] = name
            }
        }

        // 章节：li.chapter-li.jsChapter
        // 站点是从最新到最旧，通常需要反转一下让 1 话排前面
        val chapterEls = doc.select("li.chapter-li.jsChapter a.chapter-li-a")

        if (chapterEls.isEmpty()) return emptyList()

        val list = chapterEls.mapIndexed { index, a ->
            val href = a.attrAsRelativeUrl("href")           // /read/145/10580.html
            val url = href.toAbsoluteUrl(domain)
            val title = a.selectFirst("span.chapter-index")?.text()?.trim()
                ?: a.text().trim()

            // 暂时把所有章节 volume 设为 0（如果你想按卷来分，可以根据 DOM 结构再细分）
            MangaChapter(
                id = generateUid(url),
                title = title,
                number = (index + 1).toFloat(),   // 简单按顺序编号
                volume = 0,
                url = url,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source,
            )
        }

        // 反转顺序，让第 1 话在前面
        return list.asReversed()
    }

    // =============== 阅读页（章节图片） =================

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url).parseHtml()

        // 图片都在 <div id="acontentz" class="bcontent"> 下面
        val container = doc.selectFirst("div#acontentz.bcontent")
            ?: return emptyList()

        val imgs = container.select("img.imagecontent")

        if (imgs.isEmpty()) return emptyList()

        return imgs.mapIndexedNotNull { index, img ->
            // 优先 data-src；如果没有就用 src
            val dataSrc = img.attr("data-src")
            val srcAttr = img.attr("src")
            val raw = when {
                dataSrc.isNotBlank() -> dataSrc
                srcAttr.isNotBlank() && !srcAttr.endsWith("/images/sloading.svg") -> srcAttr
                else -> null
            } ?: return@mapIndexedNotNull null

            val url = raw.toAbsoluteUrl(domain)

            MangaPage(
                id = generateUid(url + "#$index"),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    // =============== 工具方法 =================

    /** 把带 <br> 的简介 HTML 转成纯文本（换行保留） */
    private fun String.cleanHtml(): String =
        this.replace("<br>", "\n")
            .replace("<br/>", "\n")
            .replace("<br />", "\n")
            .replace(Regex("<[^>]+>"), "")
            .trim()
}
