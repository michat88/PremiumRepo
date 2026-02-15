package com.CeweCantik

import com.lagradost.cloudstream3.*
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

    // User-Agent diambil dari log CURL agar terlihat seperti browser asli
    private val commonUserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    // Helper untuk mengambil url gambar dari atribut data-setbg atau src biasa
    private fun Element.getImageUrl(): String? {
        return this.attr("data-setbg").ifEmpty { 
            this.attr("src") 
        }
    }

    // ==============================
    // 1. MAIN PAGE (Halaman Utama)
    // ==============================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val headers = mapOf(
            "User-Agent" to commonUserAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        )

        val document = app.get(mainUrl, headers = headers).document
        val homePageList = ArrayList<HomePageList>()

        // Bagian: Sedang Tayang (Ongoing) - CSS selector dari struktur umum Kuramanime
        val ongoing = document.select("div.product__sidebar__view__item").mapNotNull {
            val title = it.selectFirst("h5 a")?.text()?.trim() ?: return@mapNotNull null
            val href = it.selectFirst("h5 a")?.attr("href") ?: return@mapNotNull null
            val image = it.selectFirst(".product__sidebar__view__item__pic")?.getImageUrl()
            val episodeText = it.selectFirst(".ep")?.text()?.trim() 

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

    // ==============================
    // 2. SEARCH (Pencarian)
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        // Menggunakan parameter search sesuai log CURL
        val url = "$mainUrl/anime?search=$query&order_by=latest"
        val headers = mapOf(
            "User-Agent" to commonUserAgent,
            "Referer" to mainUrl
        )

        val document = app.get(url, headers = headers).document

        // Parsing berdasarkan HTML yang kamu kirim (div.product__item)
        return document.select("div.product__item").mapNotNull {
            val title = it.selectFirst(".product__item__text h5 a")?.text()?.trim() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val image = it.selectFirst(".product__item__pic")?.getImageUrl()
            
            // Mengambil tipe (TV/Movie) dan Kualitas (BD/HD) dari tag ul li
            val tags = it.select(".product__item__text ul li").map { tag -> tag.text() }
            val quality = tags.find { q -> q.contains("HD") || q.contains("BD") || q.contains("WEB") } ?: ""
            
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = image
                addQuality(quality)
            }
        }
    }

    // ==============================
    // 3. LOAD (Detail Anime)
    // ==============================
    override suspend fun load(url: String): LoadResponse {
        val headers = mapOf(
            "User-Agent" to commonUserAgent,
            "Referer" to "$mainUrl/anime"
        )
        
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst(".anime__details__title h3")?.text()?.trim() ?: "Unknown Title"
        val poster = document.selectFirst(".anime__details__pic")?.getImageUrl()
        val synopsis = document.selectFirst(".anime__details__text p")?.text()?.trim()
        
        var type = TvType.Anime
        var year: Int? = null
        var status = ShowStatus.Ongoing
        
        // Parsing Widget Info
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

        // Mengambil Episode List
        val episodes = document.select("#episodeLists a").mapNotNull {
            val epTitle = it.text().trim()
            val epHref = it.attr("href")
            // Regex untuk mengambil nomor episode (misal: "Episode 12" -> 12)
            val epNum = Regex("Episode\\s+(\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(epHref) {
                this.name = epTitle
                this.episode = epNum
            }
        }.reversed() // Dibalik agar urut dari Episode 1

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = poster
            this.plot = synopsis
            this.year = year
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ==============================
    // 4. LOAD LINKS (Ekstrak Video)
    // ==============================
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

        // 1. Cek opsi server di dropdown (biasanya ada select #change-server)
        document.select("#change-server option").forEach { option ->
            val serverValue = option.attr("value")
            if (serverValue.startsWith("http")) {
                loadExtractor(serverValue, "$mainUrl/", subtitleCallback, callback)
            }
        }

        // 2. Cek iframe langsung
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            // Filter link yang bukan video player (iklan/chat)
            if (src.isNotEmpty() && !src.contains("chat") && !src.contains("disqus")) {
                 loadExtractor(src, "$mainUrl/", subtitleCallback, callback)
            }
        }
        
        // 3. Cek script (KuramaDrive sering tersembunyi di sini)
        val scriptContent = document.select("script").joinToString { it.html() }
        val urlRegex = Regex("""file:\s*["'](https?://.*?)["']""")
        urlRegex.findAll(scriptContent).forEach { match ->
             val url = match.groupValues[1]
             loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
        }

        return true
    }
}
