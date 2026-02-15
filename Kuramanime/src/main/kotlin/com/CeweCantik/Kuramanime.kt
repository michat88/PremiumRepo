package com.CeweCantik

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Kuramanime : MainAPI() {
    override var mainUrl = "https://v8.kuramanime.blog"
    override var name = "Kuramanime"
    override var lang = "id"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // ==========================================
    // BAGIAN 1: HALAMAN UTAMA (HOME)
    // ==========================================
    override val mainPage = mainPageOf(
        "$mainUrl/quick/ongoing?order_by=updated&page=" to "Sedang Tayang",
        "$mainUrl/quick/finished?order_by=updated&page=" to "Selesai Tayang",
        "$mainUrl/quick/movie?order_by=updated&page=" to "Film Layar Lebar",
        "$mainUrl/quick/donghua?order_by=updated&page=" to "Donghua"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val document = app.get(url).document
        
        val home = document.select(".filter__gallery > a").mapNotNull { element ->
            toSearchResult(element)
        }

        return newHomePageResponse(request.name, home)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val title = element.selectFirst("h5.sidebar-title-h5")?.text()?.trim() ?: return null
        var href = fixUrl(element.attr("href"))
        
        if (href.contains("/episode/")) {
            val epsIndex = href.indexOf("/episode/")
            href = href.substring(0, epsIndex)
        }

        val imageDiv = element.selectFirst(".product__sidebar__view__item")
        val posterUrl = imageDiv?.attr("data-setbg")
        val epText = element.selectFirst(".ep")?.text()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            // PERBAIKAN ERROR 1: Cek null sebelum addQuality
            if (epText != null) {
                addQuality(epText)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/anime?search=$query&order_by=latest"
        val document = app.get(url).document
        return document.select(".filter__gallery > a").mapNotNull {
            toSearchResult(it)
        }
    }

    // ==========================================
    // BAGIAN 2: DETAIL ANIME & LIST EPISODE
    // ==========================================
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.select("meta[property=og:title]").attr("content")
            .replace("Subtitle Indonesia - Kuramanime", "")
            .replace(Regex("\\(Episode.*\\)"), "")
            .trim()

        val poster = document.select("meta[property=og:image]").attr("content")
        val description = document.select("meta[name=description]").attr("content")

        // PERBAIKAN ERROR 2: Menggunakan newEpisode()
        val episodes = document.select("#animeEpisodes a.ep-button").mapNotNull { ep ->
            val epUrl = fixUrl(ep.attr("href"))
            val epName = ep.text().trim()
            val epNum = Regex("\\d+").find(epName)?.value?.toIntOrNull()

            // Gunakan builder pattern newEpisode
            newEpisode(epUrl) {
                this.name = epName
                this.episode = epNum
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            
            // PERBAIKAN ERROR 3: Menambahkan DubStatus (Subbed) sebagai parameter pertama
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ==========================================
    // BAGIAN 3: MENGAMBIL VIDEO (LOAD LINKS)
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        val document = app.get(data).document
        val serverOptions = document.select("select#changeServer option")

        serverOptions.forEach { option ->
            val serverName = option.text()
            val serverValue = option.attr("value")
            
            if (serverValue == "kuramadrive" && serverName.contains("vip", true)) return@forEach

            val serverUrl = "$data?server=$serverValue"
            
            try {
                val serverPage = app.get(serverUrl).document
                val iframeSrc = serverPage.select("iframe").attr("src")
                
                if (iframeSrc.isNotEmpty()) {
                    loadExtractor(iframeSrc, data, subtitleCallback, callback)
                } 
            } catch (e: Exception) {
                // Ignore error
            }
        }

        return true
    }
}
