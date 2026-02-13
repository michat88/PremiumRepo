package com.Mangoporn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import kotlinx.coroutines.* // Wajib import ini

class MangoPorn : MainAPI() {
    override var mainUrl = "https://mangoporn.net"
    override var name = "MangoPorn"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = false

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

        val document = app.get(url).document
        
        val items = document.select("article.item").mapNotNull {
            toSearchResult(it)
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val titleElement = element.selectFirst("h3 > a") ?: return null
        val title = titleElement.text().trim()
        val url = titleElement.attr("href")
        
        val imgElement = element.selectFirst("div.poster img")
        val posterUrl = imgElement?.attr("data-wpfc-original-src")?.ifEmpty { 
            imgElement.attr("src") 
        }

        // ERROR FIX: 'addDuration' dihapus dari sini karena SearchResponse tidak mendukungnya
        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // ==============================
    // 2. SEARCH
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        val fixedQuery = query.replace(" ", "+")
        val url = "$mainUrl/?s=$fixedQuery"
        val document = app.get(url).document
        
        return document.select("article.item").mapNotNull {
            toSearchResult(it)
        }
    }

    // ==============================
    // 3. LOAD DETAIL
    // ==============================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

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

        // ERROR FIX: ActorData sudah benar, addDuration bisa dipakai di sini jika perlu (manual assignment)
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
            this.actors = actors
        }
    }

    // ==============================
    // 4. LOAD LINKS (FIXED PARALLEL)
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val potentialLinks = mutableListOf<String>()

        document.select("#playeroptionsul li a").forEach { link ->
            val href = link.attr("href")
            if (href.startsWith("http")) potentialLinks.add(href)
        }

        document.select("#playcontainer iframe").forEach { iframe ->
            val src = iframe.attr("src")
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

            // ERROR FIX: Menggunakan coroutineScope standar + Dispatchers.IO
            // Ini menggantikan ioSafe yang error
            coroutineScope {
                sortedLinks.map { link ->
                    // Launch parallel job untuk setiap link
                    launch(Dispatchers.IO) {
                        try {
                            loadExtractor(link, data, subtitleCallback, callback)
                        } catch (e: Exception) {
                            // Ignore error per link
                        }
                    }
                }
                // CoroutineScope otomatis menunggu semua child job (launch) selesai
            }
            return true
        }

        return false
    }
}
