package com.CeweCantik

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Kuramanime : MainAPI() {
    override var mainUrl = "https://v8.kuramanime.blog"
    override var name = "Kuramanime"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    // User-Agent dari log CURL kamu untuk menghindari deteksi bot
    private val commonUserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    private fun Element.getImageUrl(): String? {
        // Kuramanime sering menggunakan data-setbg untuk lazy loading gambar
        return this.attr("data-setbg").ifEmpty { 
            this.attr("src") 
        }
    }

    // Mengambil Halaman Utama
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val headers = mapOf(
            "User-Agent" to commonUserAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Referer" to "$mainUrl/"
        )

        val document = app.get(mainUrl, headers = headers).document

        val homePageList = ArrayList<HomePageList>()

        // Bagian: Sedang Tayang (Ongoing)
        val ongoing = document.select("div.product__sidebar__view__item").mapNotNull {
            val title = it.selectFirst("h5 a")?.text()?.trim() ?: return@mapNotNull null
            val href = it.selectFirst("h5 a")?.attr("href") ?: return@mapNotNull null
            val image = it.selectFirst(".product__sidebar__view__item__pic")?.getImageUrl()
            val episodeText = it.selectFirst(".ep")?.text()?.trim() // e.g., "Ep 12"

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = image
                addQuality(episodeText ?: "")
            }
        }

        // Bagian: Anime Terbaru / Updated
        val latest = document.select("div.product__item").mapNotNull {
            val title = it.selectFirst(".product__item__text h5 a")?.text()?.trim() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val image = it.selectFirst(".product__item__pic")?.getImageUrl()
            
            // Mencoba ambil rating atau episode
            val statusText = it.selectFirst(".ep")?.text() ?: ""
            
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = image
                addQuality(statusText)
            }
        }

        if (ongoing.isNotEmpty()) homePageList.add(HomePageList("Sedang Tayang", ongoing))
        if (latest.isNotEmpty()) homePageList.add(HomePageList("Terbaru", latest))

        return newHomePageResponse(homePageList)
    }

    // Fitur Pencarian
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/anime?search=$query&order_by=latest"
        val headers = mapOf(
            "User-Agent" to commonUserAgent,
            "Referer" to mainUrl
        )

        val document = app.get(url, headers = headers).document

        return document.select("div.product__item").mapNotNull {
            val title = it.selectFirst(".product__item__text h5 a")?.text()?.trim() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val image = it.selectFirst(".product__item__pic")?.getImageUrl()
            
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = image
            }
        }
    }

    // Memuat Detail Anime
    override suspend fun load(url: String): LoadResponse {
        val headers = mapOf(
            "User-Agent" to commonUserAgent,
            "Referer" to "$mainUrl/anime"
        )
        
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst(".anime__details__title h3")?.text()?.trim() ?: "Unknown Title"
        val poster = document.selectFirst(".anime__details__pic")?.getImageUrl()
        val synopsis = document.selectFirst(".anime__details__text p")?.text()?.trim()
        
        // Parsing Info (Type, Status, dll)
        var type = TvType.Anime
        var year: Int? = null
        var status = ShowStatus.Ongoing
        
        document.select(".anime__details__widget ul li").forEach { li ->
            val text = li.text()
            when {
                text.contains("Tipe", true) -> {
                    if (text.contains("Movie", true)) type = TvType.AnimeMovie
                    if (text.contains("OVA", true)) type = TvType.OVA
                }
                text.contains("Musim", true) -> {
                     Regex("(\\d{4})").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
                         year = it
                     }
                }
                text.contains("Status", true) -> {
                    if (text.contains("Selesai", true) || text.contains("Finished", true)) {
                        status = ShowStatus.Completed
                    }
                }
            }
        }

        // Mengambil Episode
        // Kuramanime biasanya memiliki list episode di bagian bawah dengan ID tertentu
        val episodes = document.select("#episodeLists a").mapNotNull {
            val epTitle = it.text().trim()
            val epHref = it.attr("href")
            // Ekstrak nomor episode dari teks, misal "Episode 1"
            val epNum = Regex("Episode\\s+(\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(epHref) {
                this.name = epTitle
                this.episode = epNum
            }
        }.reversed() // Biasanya urutannya terbalik (terbaru di atas), kita balik biar urut dari 1

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = poster
            this.plot = synopsis
            this.year = year
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // Memuat Link Video
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf(
            "User-Agent" to commonUserAgent,
            "Referer" to "$mainUrl/"
        )

        val document = app.get(data, headers = headers).document

        // Cari iframe atau opsi server
        // Kuramanime sering menggunakan <select> untuk ganti server atau tab
        // Kita cari script atau elemen yang mengandung link embed
        
        // Strategi 1: Cari elemen <select id="change-server">
        document.select("#change-server option").forEach { option ->
            val serverValue = option.attr("value")
            // Decode value jika base64 atau ambil link langsung
            if (serverValue.isNotEmpty()) {
                // Seringkali value adalah hash, perlu hit endpoint API atau parsing script
                // Jika value adalah URL langsung:
                if (serverValue.startsWith("http")) {
                    loadExtractor(serverValue, "$mainUrl/", subtitleCallback, callback)
                }
            }
        }

        // Strategi 2: Cari iframe langsung di halaman
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty() && !src.contains("facebook") && !src.contains("chat")) {
                 loadExtractor(src, "$mainUrl/", subtitleCallback, callback)
            }
        }
        
        // Strategi 3: Parsing Script (Kuramadrive sering ada di sini)
        val scriptContent = document.select("script").joinToString { it.html() }
        val urlRegex = Regex("""file:\s*["'](https?://.*?)["']""")
        urlRegex.findAll(scriptContent).forEach { match ->
             val url = match.groupValues[1]
             loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
        }

        return true
    }
}
