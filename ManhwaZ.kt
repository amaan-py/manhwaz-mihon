package eu.kanade.tachiyomi.extension.en.manhwaz

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import okhttp3.HttpUrl
import java.net.URLEncoder
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class ManhwaZ : KeiSource() {

    override suspend fun getPopularManga(page: Int): MangasPage =
        fetchListing(if (page == 1) baseUrl else "$baseUrl/webtoon?page=$page")

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        fetchListing(if (page == 1) baseUrl else "$baseUrl/webtoon?page=$page")

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return fetchListing("$baseUrl/search?keyword=$encoded&page=$page")
    }

    private suspend fun fetchListing(url: String): MangasPage {
        val response = client.get(url)
        return response.use { res ->
            val doc = res.asJsoup()

            val mangas = doc.select(
                "a[href^=/webtoon/]:has(img), " +
                    ".item-summary a[href^=/webtoon/], " +
                    ".row.c-tabs-item__content a[href^=/webtoon/], " +
                    ".page-item-detail a[href^=/webtoon/]",
            ).mapNotNull { link ->
                val href = link.attr("href").ifBlank { return@mapNotNull null }
                if (href.contains("/chapter-")) return@mapNotNull null

                val title = link.select("img").attr("alt").ifBlank { link.text() }
                if (title.isBlank()) return@mapNotNull null

                SManga.create().apply {
                    url = href.removePrefix("/webtoon/").trim('/')
                    this.title = title.trim()
                    thumbnail_url = link.select("img").attr("data-src").ifBlank {
                        link.select("img").attr("data-lazy-src")
                    }.ifBlank {
                        link.select("img").attr("src")
                    }
                }
            }.distinctBy { it.url }

            val next = doc.select(
                "a[rel=next], .pagination a.next, a.page-numbers.next, " +
                    ".nav-links a.next",
            ).isNotEmpty()

            MangasPage(mangas, next)
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val response = client.get(url)
        return response.use { res ->
            val doc = res.asJsoup()
            val title = doc.selectFirst("h1")?.text()?.trim() ?: return@use null

            SManga.create().apply {
                this.url = url.encodedPath
                    .removePrefix("/webtoon/")
                    .trimEnd('/')
                this.title = title

                thumbnail_url =
                    doc.selectFirst("meta[property=og:image]")?.attr("content")
                        ?: doc.selectFirst("img")?.let {
                            it.attr("data-src").ifBlank {
                                it.attr("data-lazy-src")
                            }.ifBlank {
                                it.attr("src")
                            }
                        }

                description = doc.selectFirst(
                    ".summary__content, .description-summary, .summary",
                )?.text()

                author = doc.selectFirst(".author-content, .author a")?.text()
                artist = doc.selectFirst(".artist-content, .artist a")?.text()
                genre = doc.select(
                    ".genres-content a, .genres a",
                ).joinToString { it.text() }

                status = if (
                    doc.text().contains("Completed", ignoreCase = true)
                ) {
                    SManga.COMPLETED
                } else {
                    SManga.ONGOING
                }
            }
        }
    }

    override fun getMangaUrl(manga: SManga): String =
        "$baseUrl/webtoon/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String =
        if (chapter.url.startsWith("http")) chapter.url
        else "$baseUrl${chapter.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = client.get(getMangaUrl(manga))
        return response.use { res ->
            val doc = res.asJsoup()

            val updated = manga.copy().apply {
                if (fetchDetails) {
                    title = doc.selectFirst("h1")?.text() ?: title
                    thumbnail_url =
                        doc.selectFirst("meta[property=og:image]")?.attr("content")
                            ?: thumbnail_url
                    description = doc.selectFirst(
                        ".summary__content, .description-summary, .summary",
                    )?.text() ?: description
                    author = doc.selectFirst(
                        ".author-content, .author a",
                    )?.text() ?: author
                    artist = doc.selectFirst(
                        ".artist-content, .artist a",
                    )?.text() ?: artist

                    val genres = doc.select(
                        ".genres-content a, .genres a",
                    ).joinToString { it.text() }
                    if (genres.isNotBlank()) genre = genres

                    status = if (
                        doc.text().contains("Completed", ignoreCase = true)
                    ) {
                        SManga.COMPLETED
                    } else {
                        SManga.ONGOING
                    }
                }
            }

            val parsedChapters =
                if (fetchChapters) parseChapters(doc) else chapters

            SMangaUpdate(updated, parsedChapters)
        }
    }

    private fun parseChapters(doc: org.jsoup.nodes.Document): List<SChapter> =
        doc.select("a[href*=/chapter-]").mapNotNull { link ->
            val href = link.attr("href").ifBlank { return@mapNotNull null }

            val name = link.selectFirst(
                ".chapter-manhwa-title, .chapter-name",
            )?.text()?.trim() ?: link.text().trim()

            if (name.isBlank()) return@mapNotNull null

            SChapter.create().apply {
                url = href.removePrefix(baseUrl)
                this.name = name
                chapter_number = chapterNumber(name)
                date_upload = parseDate(link.parent()?.text().orEmpty())
            }
        }.distinctBy { it.url }
            .sortedByDescending { it.chapter_number }

    private fun chapterNumber(name: String): Float {
        val match =
            Regex("(?i)(?:chapter|ch\\.?)[\\s-]*([0-9]+(?:\\.[0-9]+)?)")
                .find(name)
                ?: Regex("([0-9]+(?:\\.[0-9]+)?)").find(name)

        return match?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
    }

    private fun parseDate(text: String): Long {
        val value = Regex("\\d{4}-\\d{2}-\\d{2}").find(text)?.value
            ?: return 0L

        return try {
            SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
                .parse(value)?.time ?: 0L
        } catch (_: ParseException) {
            0L
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = if (chapter.url.startsWith("http")) {
            chapter.url
        } else {
            "$baseUrl${chapter.url}"
        }

        val response = client.get(url)
        return response.use { res ->
            res.asJsoup().select("img").mapIndexedNotNull { index, image ->
                val src = image.attr("data-src").ifBlank {
                    image.attr("data-lazy-src")
                }.ifBlank {
                    image.attr("src")
                }

                if (src.isBlank() || !src.startsWith("http")) {
                    null
                } else {
                    Page(index, imageUrl = src)
                }
            }
        }
    }
}
