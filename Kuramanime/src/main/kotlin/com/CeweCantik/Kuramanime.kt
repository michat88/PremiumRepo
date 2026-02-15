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

    // Helper untuk header agar tidak dianggap bot
    private fun getHeader(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "$mainUrl/"
        )
    }

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
        // Tambahkan header saat request
        val document = app.get(url, headers = getHeader()).document
        
        // Selector utama
        val home = document.select("div.filter__gallery > a").mapNotNull { element ->
            toSearchResult(element)
        }

        return newHomePageResponse(request.name, home)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val title = element.selectFirst("h5")?.text()?.trim() ?: return null
        val href = fixUrl(element.attr("href"))
        
        // PERBAIKAN GAMBAR:
        // Ambil elemen div yang memiliki class 'set-bg' DI DALAM elemen 'a' saat ini
        val imageDiv = element.selectFirst("div.set-bg")
        var posterUrl = imageDiv?.attr("data-setbg")

        // Jika data-setbg kosong, coba cari dari style
        if (posterUrl.isNullOrEmpty()) {
            val style = imageDiv?.attr("style") ?: ""
            if (style.contains("url(")) {
                posterUrl = style.substringAfter("url('").substringBefore("')")
                    .ifEmpty { style.substringAfter("url(").substringBefore(")") }
            }
        }

        // Eps info
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
    // BAGIAN 2: DETAIL ANIME & LIST EPISODE
    // ==========================================
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = getHeader()).document

        // Ambil Judul Bersih
        val rawTitle = document.select("meta[property=og:title]").attr("content")
        val title = rawTitle
            .replace(Regex("\\(Episode\\s+\\d+\\).*"), "")
            .replace(Regex("Episode\\s+\\d+.*"), "")
            .replace("Subtitle Indonesia", "", true)
            .replace("- Kuramanime", "")
            .trim()

        val poster = document.select("meta[property=og:image]").attr("content")
        val description = document.select("div.content__tags").text() // Mengambil deskripsi dari tags/konten
            .ifEmpty { document.select("meta[name=description]").attr("content") }

        // PERBAIKAN LIST EPISODE
        // Kita cari semua link di dalam container episode
        val episodes = ArrayList<Episode>()
        
        // Selector 1: Button episode biasa
        var epElements = document.select("#animeEpisodes a")
        
        // Selector 2 (Cadangan): Jika ID animeEpisodes tidak ketemu, cari class filter gallery
        if (epElements.isEmpty()) {
            epElements = document.select(".filter__gallery a[href*='/episode/']")
        }

        epElements.forEach { ep ->
            val epUrl = fixUrl(ep.attr("href"))
            val epText = ep.text().trim()
            
            // Logika ekstraksi nomor episode yang lebih kuat
            val epNum = Regex("(?i)(?:Ep|Episode)\\s*(\\d+)").find(epText)?.groupValues?.get(1)?.toIntOrNull() 
                ?: Regex("\\d+").find(epText)?.value?.toIntOrNull()

            // Hanya masukkan jika URL valid
            if (epUrl.contains("/episode/")) {
                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epText
                        this.episode = epNum
                    }
                )
            }
        }
        
        // Reverse agar episode 1 ada di bawah (urutan nonton yang benar)
        // Atau biarkan default (terbaru di atas). Biasanya CloudStream suka terbaru di atas.
        // episodes.reverse() 

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            
            // PENTING: Jika list episode kosong, CloudStream akan otomatis menampilkan "Segera Hadir"
            if (episodes.isNotEmpty()) {
                addEpisodes(DubStatus.Subbed, episodes)
            }
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
        
        val document = app.get(data, headers = getHeader()).document
        
        // Cek dropdown server
        val serverOptions = document.select("select#changeServer option")

        if (serverOptions.isNotEmpty()) {
            serverOptions.forEach { option ->
                val serverValue = option.attr("value")
                val serverName = option.text()

                // Skip yang ribet
                if (serverName.contains("vip", true)) return@forEach

                // Request ke server URL
                val serverUrl = "$data?server=$serverValue"
                try {
                    val doc = app.get(serverUrl, headers = getHeader()).document
                    val iframe = doc.select("iframe").attr("src")
                    if (iframe.isNotBlank()) {
                        loadExtractor(iframe, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
        } else {
            // Fallback: Jika tidak ada dropdown, coba cari iframe langsung di halaman ini
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
