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
    // 1. HEADER SETTING (Sesuai Log CURL Kamu)
    // ==========================================
    // Kita buat headers ini mirip browser asli agar session/cookie tidak ditolak
    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Linux\"",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-User" to "?1",
        "Sec-Fetch-Dest" to "document"
    )

    // Helper function untuk mengambil header lengkap dengan referer
    private fun getHeader(referer: String = mainUrl): Map<String, String> {
        return commonHeaders + mapOf("Referer" to referer)
    }

    // ==========================================
    // 2. HALAMAN UTAMA (HOME)
    // ==========================================
    override val mainPage = mainPageOf(
        "$mainUrl/quick/ongoing?order_by=updated&page=" to "Sedang Tayang",
        "$mainUrl/quick/finished?order_by=updated&page=" to "Selesai Tayang",
        "$mainUrl/quick/movie?order_by=updated&page=" to "Film Layar Lebar",
        "$mainUrl/quick/donghua?order_by=updated&page=" to "Donghua"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        
        // Request halaman dengan header lengkap
        val document = app.get(url, headers = getHeader()).document
        
        val home = document.select("div.filter__gallery > a").mapNotNull { element ->
            toSearchResult(element)
        }

        return newHomePageResponse(request.name, home)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val title = element.selectFirst("h5")?.text()?.trim() ?: return null
        val href = fixUrl(element.attr("href"))
        
        // LOGIKA GAMBAR BARU (Berdasarkan CURL Log)
        // Log curl menunjukkan gambar ada di r2.nyomo.my.id
        // Kita cari data-setbg dulu
        var posterUrl = element.selectFirst(".product__sidebar__view__item")?.attr("data-setbg")
        
        // Fallback: Jika data-setbg kosong, cari style url
        if (posterUrl.isNullOrEmpty()) {
             val style = element.selectFirst(".product__sidebar__view__item")?.attr("style") ?: ""
             if (style.contains("url(")) {
                 posterUrl = style.substringAfter("url(").substringBefore(")")
                     .replace("\"", "").replace("'", "")
             }
        }

        // Fallback 2: Cari tag img biasa (untuk amannya)
        if (posterUrl.isNullOrEmpty()) {
            posterUrl = element.selectFirst("img")?.attr("src")
        }

        val epText = element.selectFirst(".ep")?.text()?.trim()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            if (epText != null) {
                addQuality(epText)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/anime?search=$query&order_by=latest"
        val document = app.get(url, headers = getHeader()).document
        return document.select("div.filter__gallery > a").mapNotNull {
            toSearchResult(it)
        }
    }

    // ==========================================
    // 3. DETAIL ANIME & LIST EPISODE
    // ==========================================
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = getHeader()).document

        // Bersihkan Judul
        val rawTitle = document.select("meta[property=og:title]").attr("content")
        val title = rawTitle
            .replace(Regex("\\(Episode\\s+\\d+\\).*"), "")
            .replace(Regex("Episode\\s+\\d+.*"), "")
            .replace("Subtitle Indonesia", "", true)
            .replace("- Kuramanime", "")
            .trim()

        val poster = document.select("meta[property=og:image]").attr("content")
        val description = document.select("div.content__tags").text()
            .ifEmpty { document.select("meta[name=description]").attr("content") }

        // MENCARI EPISODE
        val episodes = ArrayList<Episode>()
        
        // Menggunakan selector yang terbukti berhasil di Script Python kemarin
        var epElements = document.select("#animeEpisodes a")
        if (epElements.isEmpty()) {
            epElements = document.select(".filter__gallery a[href*='/episode/']")
        }

        epElements.forEach { ep ->
            val epUrl = fixUrl(ep.attr("href"))
            val epText = ep.text().trim()
            val epNum = Regex("(?i)(?:Ep|Episode)\\s*(\\d+)").find(epText)?.groupValues?.get(1)?.toIntOrNull() 
                ?: Regex("\\d+").find(epText)?.value?.toIntOrNull()

            if (epUrl.contains("/episode/")) {
                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epText
                        this.episode = epNum
                    }
                )
            }
        }
        
        // Reverse agar episode terbaru ada di paling atas (standar CloudStream)
        // Hapus .reversed() jika ingin urutan 1, 2, 3...
        // episodes.reverse() 

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            
            if (episodes.isNotEmpty()) {
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    // ==========================================
    // 4. LOAD VIDEO (SERVER)
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        // Di sini kita perlu header khusus AJAX karena log curl menunjukkan ada XMLHttpRequest
        val ajaxHeaders = getHeader(data) + mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty"
        )

        val document = app.get(data, headers = getHeader(data)).document
        
        // Cek Dropdown Server
        val serverOptions = document.select("select#changeServer option")

        if (serverOptions.isNotEmpty()) {
            serverOptions.forEach { option ->
                val serverValue = option.attr("value")
                val serverName = option.text()

                if (serverName.contains("vip", true)) return@forEach

                val serverUrl = "$data?server=$serverValue"
                try {
                    // Gunakan header AJAX untuk request server (karena sifatnya partial load)
                    val doc = app.get(serverUrl, headers = ajaxHeaders).document
                    
                    val iframe = doc.select("iframe").attr("src")
                    if (iframe.isNotBlank()) {
                        loadExtractor(iframe, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
        } else {
            // Fallback
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}
