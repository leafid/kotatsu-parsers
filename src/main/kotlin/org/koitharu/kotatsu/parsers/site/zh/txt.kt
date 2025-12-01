package org.koitharu.kotatsu.parsers.site.zh

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.AbstractMangaParser
import org.koitharu.kotatsu.parsers.core.MangaParserSource
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

/**
 * 哔哩漫画（bilimanga.net）周点击榜解析器
 * 核心解析 /top/weekvisit/ 页面的周点击榜列表
 */
@MangaSourceParser("BILIMANGA", "BiliManga", "zh")
internal class BiliManga(context: MangaLoaderContext) :
    AbstractMangaParser(context, MangaParserSource.BILIMANGA) {

    // 配置网站域名
    override val configKeyDomain = ConfigKey.Domain("www.bilimanga.net")

    // 仅支持周点击榜排序（核心需求）
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.POPULARITY)

    // 筛选能力：暂仅支持周点击榜，关闭搜索/标签等筛选
    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = false,
            isTagsSupported = false,
            isStatesSupported = false,
            isTypesSupported = false
        )

    /**
     * 核心方法：解析周点击榜列表
     * page：页码（对应 /top/weekvisit/{page}.html）
     */
    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter
    ): List<Manga> {
        // 周点击榜固定URL：/top/weekvisit/{page}.html
        val url = buildString {
            append("https://")
            append(domain)
            append("/top/weekvisit/")
            append(page)
            append(".html")
        }

        // 请求页面并解析HTML
        val doc = webClient.httpGet(url).parseHtml()

        // 解析核心列表：<li class="book-li"> 节点
        return doc.select("li.book-li").map { li ->
            // 1. 提取详情页链接（相对路径转绝对路径）
            val aTag = li.selectFirstOrThrow("a.book-layout")
            val href = aTag.attrAsRelativeUrl("href")
            val mangaUrl = href.toAbsoluteUrl(domain)

            // 2. 提取封面图片URL（优先取src，兜底data-src）
            val imgTag = li.selectFirst(".book-cover img")
            val coverUrl = imgTag?.attr("src") ?: imgTag?.attr("data-src") ?: ""

            // 3. 提取排名（top-number）
            val rank = li.selectFirst(".book-cover .top-number")?.text()?.toIntOrNull() ?: 0

            // 4. 提取标题
            val title = li.selectFirst(".book-cell .book-title")?.text().orEmpty()

            // 5. 提取简介
            val description = li.selectFirst(".book-cell .book-intro")?.text().orEmpty()

            // 6. 提取作者
            val author = li.selectFirst(".book-cell .book-meta-l .book-author")?.text().orEmpty()
                .trim() // 去除多余空格

            // 构建Manga对象（Kotatsu框架标准模型）
            Manga(
                id = generateUid(mangaUrl), // 基于URL生成唯一ID
                url = mangaUrl, // 漫画详情页绝对URL
                publicUrl = mangaUrl,
                coverUrl = coverUrl, // 封面图片URL
                title = title, // 漫画标题
                altTitles = emptySet(), // 暂无别名
                rating = RATING_UNKNOWN, // 暂不解析评分
                tags = emptySet(), // 暂不解析标签
                authors = setOfNotNull(author.takeIf { it.isNotEmpty() }), // 作者（去空）
                state = null, // 暂不解析连载状态
                source = source, // 漫画源标识
                description = description, // 简介
                // 扩展字段：存储排名（可选）
                extras = mapOf("rank" to rank.toString())
            )
        }
    }

    /**
     * 可选：解析漫画详情页（如需扩展可实现）
     * 此处仅做基础实现，可根据需求补充章节、状态等信息
     */
    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url).parseHtml()
        // 可补充解析：连载状态、完整简介、标签、章节列表等
        return manga.copy(
            // 示例：解析连载状态（需根据实际页面结构调整）
            state = doc.selectFirst(".status-tag")?.text()?.let {
                when {
                    it.contains("连载") -> MangaState.ONGOING
                    it.contains("完结") -> MangaState.FINISHED
                    else -> null
                }
            }
        )
    }

    /**
     * 可选：解析章节图片（如需扩展可实现）
     * 此处仅占位，需根据实际章节页结构补充
     */
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url).parseHtml()
        // 示例：解析图片列表（需根据实际章节页调整选择器）
        return doc.select(".chapter-img img").mapIndexed { i, img ->
            MangaPage(
                id = generateUid(img.attr("src")),
                url = img.attr("src").toAbsoluteUrl(domain),
                preview = null,
                source = source
            )
        }
    }

    /**
     * 拦截器：为Bilimanga及相关域名添加请求头，以绕过Cloudflare检测
     */
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val url = original.url
        val host = url.host.lowercase()

        // 针对BiliManga主站及其图片CDN域名，添加模拟浏览器的HTTP头以绕过Cloudflare
        val needsHeaders = host.contains("bilimanga.net") ||
            host.contains("motiezw.com") ||
            host.contains("bilimicro.top")  // 包括已知的图片/CDN域名

        return if (needsHeaders) {
            val newRequest = original.newBuilder()
                // 模拟浏览器Headers（Referer和Origin设为主站，用于通过Cloudflare和图片加载）
                .header("Referer", "https://${domain}/")
                .header("Origin", "https://${domain}")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 6 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.5735.130 Mobile Safari/537.36")
                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Cache-Control", "no-cache")
                .build()
            chain.proceed(newRequest)
        } else {
            chain.proceed(original)
        }
    }
}
