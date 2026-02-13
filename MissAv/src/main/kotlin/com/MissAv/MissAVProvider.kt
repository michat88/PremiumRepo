package com.MissAv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MissAVProvider : MainAPI() {
    override var mainUrl = "https://missav.ws/id"
    override var name = "MissAV"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = false

    private fun String.toUrl(): String {
        return if (this.startsWith("http")) this else "https://missav.ws$this"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document
        val homePageList = ArrayList<HomePageList>()

        val sections = document.select("div.sm:container.mx-auto.mb-5.px-4")

        sections.forEach { section ->
            var title = section.selectFirst("h2.text-nord6")?.text()?.trim()
                ?: section.selectFirst("h2")?.text()?.trim()
            
            if (title.isNullOrEmpty() || title.equals("Acak", ignoreCase = true)) return@forEach

            val items = section.select("div.thumbnail.group").mapNotNull { element ->
                toSearchResult(element)
            }

            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(title, items))
            }
        }

        return newHomePageResponse(homePageList)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val linkElement = element.selectFirst("a.text-secondary") ?: return null
        val url = linkElement.attr("href").toUrl()
        val title = linkElement.text().trim()
        val imgElement = element.selectFirst("img")
        val posterUrl = imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") }
        
        // PERBAIKAN FINAL: Menghapus logika duration yang error
        // Kita cukup ambil Judul, URL, dan Poster saja agar tidak error
        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/legacy?keyword=$query"
        val document = app.get(url).document
        
        return document.select("div.thumbnail.group").mapNotNull { element ->
            toSearchResult(element)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.text-base.text-nord6")?.text()?.trim() 
            ?: document.selectFirst("h1")?.text()?.trim() 
            ?: "Unknown Title"

        val description = document.selectFirst("div.text-secondary.break-all")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")

        val tags = document.select("div.text-secondary a[href*='/genres/']").map { it.text() }
        
        val actors = document.select("div.text-secondary a[href*='/actresses/'], div.text-secondary a[href*='/actors/']")
            .map { element ->
                ActorData(Actor(element.text(), null))
            }

        val year = document.selectFirst("time")?.text()?.trim()?.take(4)?.toIntOrNull()

        // Kita coba ambil durasi dari metadata (dalam detik) jika tersedia di halaman detail
        val durationSeconds = document.selectFirst("meta[property=og:video:duration]")
            ?.attr("content")?.toLongOrNull()
        
        // Konversi detik ke menit (Cloudstream biasanya pakai menit untuk LoadResponse)
        val durationMinutes = durationSeconds?.div(60)?.toInt()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.actors = actors
            this.year = year
            this.duration = durationMinutes 
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val text = app.get(data).text
        val regex = """nineyu\.com\\/([0-9a-fA-F-]+)\\/seek""".toRegex()
        val match = regex.find(text)
        
        if (match != null) {
            val uuid = match.groupValues[1]
            val videoUrl = "https://surrit.com/$uuid/playlist.m3u8"

            callback.invoke(
                newExtractorLink(
                    source = "MissAV",
                    name = "MissAV (Surrit)",
                    url = videoUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = data
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        return false
    }
}
