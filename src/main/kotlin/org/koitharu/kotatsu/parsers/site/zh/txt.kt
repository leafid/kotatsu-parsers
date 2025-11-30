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

	// 域名配置
	override val configKeyDomain = ConfigKey.Domain("www.bilimanga.net")

	// 排序方式：先只支持人气（周点击）
	override val availableSortOrders: Set<SortOrder> =
		EnumSet.of(SortOrder.POPULARITY)

	// 先不支持搜索/标签/状态这些复杂筛选
	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = false,
		)

	// 暂时没有额外筛选项
	override suspend fun getFilterOptions(): MangaListFilterOptions =
		MangaListFilterOptions()

	/**
	 * 榜单页：周点击榜
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
				title = title,
				altTitles = emptySet(),
				url = mangaUrl,
				publicUrl = mangaUrl,
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = coverUrl,
				tags = emptySet(),
				state = null,
				authors = setOfNotNull(author.takeIf { it.isNotEmpty() }),
				chapters = emptyList(),
				source = source,
			)
		}

	/**
	 * 详情页 + 章节目录：
	 *  - 详情：https://www.bilimanga.net/detail/145.html
	 *  - 目录：https://www.bilimanga.net/read/145/catalog
	 */
	override suspend fun getDetails(manga: Manga): Manga {
		// 1. 详情页（简介 / 状态）
		val detailDoc = webClient
			.httpGet(manga.url.toAbsoluteUrl(domain))
			.parseHtml()

		val description = detailDoc.selectFirst(".book-desc")?.text().orEmpty()

		val state = detailDoc.selectFirst(".status-tag")?.text()?.let {
			when {
				it.contains("連載") || it.contains("连载") -> MangaState.ONGOING
				it.contains("完結") || it.contains("完结") -> MangaState.FINISHED
				else -> null
			}
		}

		// 2. 从 /detail/145.html 提取 id = 145
		val id = manga.url.substringAfterLast("/").substringBefore(".")
		val catalogUrl = "https://$domain/read/$id/catalog"

		// 3. 目录页：解析 li.chapter-li.jsChapter -> a.chapter-li-a
		val catalogDoc = webClient.httpGet(catalogUrl).parseHtml()

		val chapterElements = catalogDoc.select("li.chapter-li.jsChapter a.chapter-li-a")

		// 通常目录是“最新在上面”，所以用 reversed = true 让第 1 话排在前面
		val chapters = chapterElements.mapChapters(true) { index, a ->
			val chapterHref = a.attrAsRelativeUrl("href")   // /read/145/10590.html
			val chapterUrl = chapterHref.toAbsoluteUrl(domain)

			val titleText = a.selectFirst(".chapter-index")
				?.text()
				?.trim()
				.orEmpty()

			MangaChapter(
				id = generateUid(chapterUrl),
				title = if (titleText.isNotEmpty()) titleText else "第${index + 1}话",
				number = index + 1f,
				volume = 0, // 先不管卷的信息，后面需要再用 vloume-info 做分组
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
	 * 阅读页：解析章节图片
	 * 例：/read/145/10590.html
	 * 选择器 .chapter-img img 后面你可以根据真实 HTML 再微调
	 */
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

		return doc.select(".chapter-img img").mapIndexedNotNull { index, img ->
			// 注意：img.src() 在这个版本里是 String?，要用 ?. 调用，再判空
			val src = img.src()
				?.takeIf { it.isNotBlank() }
				?.toAbsoluteUrl(domain)
				?: return@mapIndexedNotNull null

			MangaPage(
				id = generateUid("$src#$index"),
				url = src,
				preview = null,
				source = source,
			)
		}
	}
}
