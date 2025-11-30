package org.koitharu.kotatsu.parsers.site.zh

import org.json.JSONArray
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.MangaParserSource
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.util.EnumSet

@MangaSourceParser("MANGACOPY", "MangaCopy", "zh")
internal class Mangacopy(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGACOPY, pageSize = 50) {

    override val configKeyDomain = ConfigKey.Domain("www.mangacopy.com")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey) // 跟其它源一样，用可配置 UA
    }

    override val availableSortOrders: Set<SortOrder> =
        EnumSet.of(SortOrder.POPULARITY)

    // 老 Filter API，先只声明支持普通列表，不支持搜索过滤
    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = false,
        )

    // 这里简单返回空选项，后面想加 tag / 状态再扩展
    private val emptyFilterOptions = suspendLazy {
        MangaListFilterOptions(
            availableTags = emptySet(),
            availableStates = EnumSet.noneOf(MangaState::class.java),
            availableContentTypes = EnumSet.of(
                ContentType.MANGA,
                ContentType.MANHWA,
                ContentType.MANHUA,
                ContentType.COMICS,
            ),
        )
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions =
        emptyFilterOptions.get()

    /**
     * 发现页列表：
     * https://www.mangacopy.com/comics?ordering=-datetime_updated&offset=0&limit=50
     * 第二页 offset=50，再往后 +50。
     */
    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val offset = (page - 1) * pageSize
        val url = buildString {
            append("https://")
            append(domain)
            append("/comics?ordering=-datetime_updated")
            append("&offset=")
            append(offset)
            append("&limit=")
            append(pageSize)
        }

        val doc = webClient.httpGet(url).parseHtml()
        return parseMangaList(doc)
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        // row.exemptComic-box 下面的每个 div.col-auto.exemptComic_Item
        val cards = doc.select("div.row.exemptComic-box div.col-auto.exemptComic_Item")
        return cards.mapNotNull { card ->
            val linkEl = card.selectFirst("div.exemptComic_Item-img a") ?: return@mapNotNull null
            val href = linkEl.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val fullUrl = "https://$domain$href"

            val imgEl = linkEl.selectFirst("img")
            val coverUrl = imgEl?.attr("data-src").takeIf { !it.isNullOrBlank() }
                ?: imgEl?.attr("src").orEmpty()

            val title = card.selectFirst("div.exemptComicItem-txt p.twoLines")?.text().orEmpty()

            val author = card.selectFirst("span.exemptComicItem-txt-span a")?.text()
                ?.takeIf { it.isNotBlank() }

            Manga(
                id = generateUid(fullUrl),
                url = fullUrl,           // 直接用绝对地址
                publicUrl = fullUrl,
                coverUrl = coverUrl,
                title = title,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                authors = setOfNotNull(author),
                state = null,
                source = source,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url).parseHtml()

        // 简单尝试读简介（如果取不到就空字符串）
        val description = doc.selectFirst(".comicParticulars-left-txt p")
            ?.text()
            .orEmpty()

        // 作者： <li><span>作者：</span><span class="comicParticulars-right-txt"><a>xxx</a></span></li>
        val detailAuthor = doc.select("li span:contains(作者：)")
            .firstOrNull()
            ?.parent()
            ?.selectFirst("span.comicParticulars-right-txt a")
            ?.text()
            ?.takeIf { it.isNotBlank() }

        val authors = if (detailAuthor != null) {
            setOf(detailAuthor)
        } else {
            manga.authors
        }

        // 章节列表：所有 /comic/xxx/chapter/yyy 的 <a>
        val chapterLinks = doc.select("a[href^=\"/comic/\"][href*=\"/chapter/\"]")

        val chapters = chapterLinks.mapIndexed { index, a ->
            val chHref = a.attr("href")
            val fullChUrl = if (chHref.startsWith("http")) chHref else "https://$domain$chHref"

            val chTitle = a.attr("title").takeIf { it.isNotBlank() }
                ?: a.text().takeIf { it.isNotBlank() }

            MangaChapter(
                id = generateUid(fullChUrl),
                title = chTitle,
                number = (index + 1).toFloat(),
                volume = 0,
                url = fullChUrl,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source,
            )
        }

        // 网站好像没有明显的完结标记，这里先不给 state
        return manga.copy(
            description = description,
            state = manga.state,
            authors = authors,
            chapters = chapters,
        )
    }

    /**
     * 章节页：
     * <ul class="comicContent-list comic-size-1">
     *   <li><img data-src="真实图片地址" src="loading 占位图" ...></li>
     * </ul>
     *
     * 只用 data-src，不用懒加载占位的 src。
     */
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url).parseHtml()
        val imgs = doc.select("ul.comicContent-list img")

        return imgs.mapNotNull { img ->
            val imgUrl = img.attr("data-src")
                .takeIf { it.isNotBlank() }
                ?: img.attr("src").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val id = generateUid(imgUrl)
            MangaPage(
                id = id,
                url = imgUrl,
                preview = null,
                source = source,
            )
        }
    }
}
