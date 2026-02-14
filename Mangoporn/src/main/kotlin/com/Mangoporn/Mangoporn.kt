package com.Mangoporn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import kotlinx.coroutines.* class MangoPorn : MainAPI() {
    override var mainUrl = "https://mangoporn.net"
    override var name = "MangoPorn"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = false

    // Header untuk menipu server agar mengira kita browser asli (sesuai log CURL kamu)
    private val standardHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    // ==============================
    // 1. MAIN PAGE CONFIGURATION
    // ==============================
    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "Recent Movies",
        "$mainUrl/trending/" to "Trending",
        "$mainUrl/ratings/" to "Top Rated",
        "$mainUrl/genres/porn-movies/" to "Porn Movies",
        "$mainUrl/xxxclips/" to "XXX Clips"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) {
            request.data
        } else {
            "${request.data}page/$page/"
        }

        // Menggunakan headers standar
        val document = app.get(url, headers = standardHeaders).document
        
        val items = document.select("article.item").mapNotNull {
            toSearchResult(it)
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val titleElement = element.selectFirst("h3 > a") ?: return null
        val title = titleElement.text().trim()
        
        // FIX: Menggunakan fixUrl agar url valid (https://...)
        val url = fixUrl(titleElement.attr("href"))
        
        val imgElement = element.selectFirst("div.poster img")
        val posterUrl = imgElement?.attr("data-wpfc-original-src")?.ifEmpty { 
            imgElement.attr("src") 
        }

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // ==============================
    // 2. SEARCH (PERBAIKAN UTAMA)
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        val fixedQuery = query.replace(" ", "+")
        val url = "$mainUrl/?s=$fixedQuery"
        
        // Request menggunakan Headers lengkap agar tidak diblokir
        val document = app.get(url, headers = standardHeaders).document
        
        // Kita coba cari selector standar
        // Jika kosong, kemungkinan situs memblokir bot atau struktur berubah di mode search
        return document.select("article.item").mapNotNull {
            toSearchResult(it)
        }
    }

    // ==============================
    // 3. LOAD DETAIL
    // ==============================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = standardHeaders).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"

        val description = document.selectFirst("div.wp-content p")?.text()?.trim()
        
        val imgElement = document.selectFirst("div.poster img")
        val poster = imgElement?.attr("data-wpfc-original-src")?.ifEmpty { 
            imgElement.attr("src") 
        }

        val tags = document.select(".sgeneros a, .persons a[href*='/genre/']").map { it.text() }
        
        val year = document.selectFirst(".textco a[href*='/year/']")?.text()?.toIntOrNull()
        
        val actors = document.select("#cast .persons a[href*='/pornstar/']").map { 
            ActorData(Actor(it.text(), null)) 
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
            this.actors = actors
        }
    }

    // ==============================
    // 4. LOAD LINKS (PARALLEL FIX)
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = standardHeaders).document

        val potentialLinks = mutableListOf<String>()

        document.select("#playeroptionsul li a").forEach { link ->
            val href = fixUrl(link.attr("href")) // Gunakan fixUrl
            if (href.startsWith("http")) potentialLinks.add(href)
        }

        document.select("#playcontainer iframe").forEach { iframe ->
            val src = fixUrl(iframe.attr("src")) // Gunakan fixUrl
            if (src.startsWith("http")) potentialLinks.add(src)
        }

        fun getServerPriority(url: String): Int {
            return when {
                url.contains("dood") -> 0
                url.contains("streamtape") -> 1
                url.contains("voe.sx") -> 2
                url.contains("vidhide") -> 5
                url.contains("filemoon") -> 6
                url.contains("mixdrop") -> 10
                url.contains("streamsb") -> 11
                else -> 20
            }
        }

        if (potentialLinks.isNotEmpty()) {
            val sortedLinks = potentialLinks.sortedBy { getServerPriority(it) }

            // Menggunakan coroutineScope untuk memproses link secara paralel
            coroutineScope {
                sortedLinks.map { link ->
                    launch(Dispatchers.IO) {
                        try {
                            loadExtractor(link, data, subtitleCallback, callback)
                        } catch (e: Exception) {
                            // Ignore error per link
                        }
                    }
                }
            }
            return true
        }
        return false
    }
}
