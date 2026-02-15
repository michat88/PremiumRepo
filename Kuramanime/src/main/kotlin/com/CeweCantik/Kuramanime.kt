package com.CeweCantik

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.* // Import ini mencakup newExtractorLink, Qualities, ExtractorLinkType
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

class Kuramanime : MainAPI() {
    override var mainUrl = "https://v8.kuramanime.blog"
    override var name = "Kuramanime"
    override var lang = "id"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // Header Browser Asli
    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "X-Requested-With" to "XMLHttpRequest",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Linux\""
    )

    // ==========================================
    // 1. HOME (HTML PARSING)
    // ==========================================
    override val mainPage = mainPageOf(
        "$mainUrl/quick/ongoing?order_by=updated&page=" to "Sedang Tayang",
        "$mainUrl/quick/finished?order_by=updated&page=" to "Selesai Tayang",
        "$mainUrl/quick/movie?order_by=updated&page=" to "Film Layar Lebar",
        "$mainUrl/quick/donghua?order_by=updated&page=" to "Donghua"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val document = app.get(url, headers = commonHeaders).document
        
        val home = document.select("div.product__sidebar__view__item, .product__item").mapNotNull { element ->
            toSearchResult(element)
        }

        return newHomePageResponse(request.name, home)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val linkElement = element.selectFirst("a") ?: element.parent() as? Element ?: return null
        val title = linkElement.selectFirst("h5")?.text()?.trim() ?: return null
        val href = fixUrl(linkElement.attr("href"))
        
        var posterUrl = element.attr("data-setbg")
        if (posterUrl.isNullOrEmpty()) {
            val style = element.attr("style")
            if (style.contains("url(")) {
                posterUrl = style.substringAfter("url(").substringBefore(")").replace("\"", "").replace("'", "")
            }
        }
        
        if (posterUrl.isNullOrEmpty()) {
            posterUrl = element.selectFirst("img")?.attr("src") ?: ""
        }

        val epText = element.selectFirst(".ep")?.text()?.trim()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            if (!epText.isNullOrEmpty()) {
                addQuality(epText)
            }
        }
    }

    // ==========================================
    // 2. SEARCH (HTML PARSING)
    // ==========================================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/anime?search=$query&order_by=latest"
        val document = app.get(url, headers = commonHeaders).document
        
        return document.select("div.product__sidebar__view__item, .product__item").mapNotNull {
            toSearchResult(it)
        }
    }

    // ==========================================
    // 3. LOAD (DETAIL ANIME)
    // ==========================================
    override suspend fun load(url: String): LoadResponse {
        // Trik: Selalu gunakan URL episode untuk parsing list episode
        val fixUrl = if (url.contains("/episode/")) url else "$url/episode/1"
        
        val document = app.get(fixUrl, headers = commonHeaders).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.replace(Regex("\\(Episode.*\\)"), "")
            ?.replace("Subtitle Indonesia", "")
            ?.replace("- Kuramanime", "")
            ?.trim() ?: "Unknown Title"

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val description = document.selectFirst("meta[name=description]")?.attr("content")

        // Mengambil daftar episode
        val episodes = document.select("#animeEpisodes a").mapNotNull { ep ->
            val epUrl = fixUrl(ep.attr("href"))
            val epName = ep.text().trim()
            val epNum = Regex("\\d+").find(epName)?.value?.toIntOrNull()

            if (epUrl.contains("/episode/")) {
                newEpisode(epUrl) {
                    this.name = epName
                    this.episode = epNum
                }
            } else null
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            
            if (episodes.isNotEmpty()) {
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    // ==========================================
    // 4. LOAD LINKS (VIDEO PLAYER & DOWNLOAD)
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        // Request ke halaman episode
        val document = app.get(data, headers = commonHeaders).document
        
        // -----------------------------------------------------------
        // CARA 1: AMBIL VIDEO LANGSUNG DARI HTML (MP4 DIRECT)
        // -----------------------------------------------------------
        document.select("video#player source").forEach { source ->
            val src = source.attr("src")
            val size = source.attr("size") // Contoh: "720", "480"
            
            if (src.contains(".mp4")) {
                // Gunakan newExtractorLink yang sekarang sudah di-import dengan benar dari 'utils'
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Kurama Direct ${size}p",
                        url = src,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = size.toIntOrNull() ?: Qualities.Unknown.value
                    }
                )
            }
        }

        // -----------------------------------------------------------
        // CARA 2: AMBIL LINK DOWNLOAD (BACKUP)
        // -----------------------------------------------------------
        document.select("#animeDownloadLink a").forEach { link ->
            val href = link.attr("href")
            loadExtractor(href, data, subtitleCallback, callback)
        }

        // -----------------------------------------------------------
        // CARA 3: CEK SERVER LAIN (JIKA ADA)
        // -----------------------------------------------------------
        val serverOptions = document.select("select#changeServer option")
        if (serverOptions.isNotEmpty()) {
            serverOptions.forEach { option ->
                val serverValue = option.attr("value")
                val serverName = option.text()
                
                // Skip Kuramadrive (karena sudah dicover di Cara 1 & 2)
                if (serverValue.contains("kuramadrive")) return@forEach

                val serverUrl = "$data?server=$serverValue"
                try {
                    val doc = app.get(serverUrl, headers = commonHeaders).document
                    val iframe = doc.select("iframe").attr("src")
                    if (iframe.isNotBlank()) {
                        loadExtractor(iframe, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {}
            }
        }

        return true
    }
}
