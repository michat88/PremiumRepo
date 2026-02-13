package com.Mangoporn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import kotlinx.coroutines.* // Penting untuk fitur Parallel Concurrency

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
        // Pagination logic: https://mangoporn.net/page/2/
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
        
        // PENTING: Handle Lazy Load WP Fastest Cache (data-wpfc-original-src)
        // Jika atribut wpfc tidak ada, fallback ke src biasa
        val imgElement = element.selectFirst("div.poster img")
        val posterUrl = imgElement?.attr("data-wpfc-original-src")?.ifEmpty { 
            imgElement.attr("src") 
        }

        // Kita coba ambil durasi jika ada
        val duration = element.selectFirst("span.duration")?.text()?.trim()

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = posterUrl
            addDuration(duration)
        }
    }

    // ==============================
    // 2. SEARCH
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        // Fix: Encode query space to + (standard WordPress search: ?s=wife+husband)
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

        // Mengambil Tags (Genre)
        val tags = document.select(".sgeneros a, .persons a[href*='/genre/']").map { it.text() }
        
        // Mengambil Tahun
        val year = document.selectFirst(".textco a[href*='/year/']")?.text()?.toIntOrNull()
        
        // Mengambil Aktor (Pornstars) yang ada di div #cast
        // Wajib dibungkus ActorData agar sesuai tipe data Cloudstream
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
    // 4. LOAD LINKS (OPTIMIZED: STRUCTURED CONCURRENCY)
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Kumpulkan semua potensi URL player dalam satu list
        val potentialLinks = mutableListOf<String>()

        // 1. Ambil dari List Player (#playeroptionsul)
        document.select("#playeroptionsul li a").forEach { link ->
            val href = link.attr("href")
            if (href.startsWith("http")) potentialLinks.add(href)
        }

        // 2. Ambil dari Iframe Fallback (#playcontainer) - Jaga-jaga kalau list kosong
        document.select("#playcontainer iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.startsWith("http")) potentialLinks.add(src)
        }

        // Definisi Prioritas Server (Angka lebih kecil = Eksekusi duluan)
        fun getServerPriority(url: String): Int {
            return when {
                // Tier 1: Server Cepat & Stabil (Direct/HLS)
                url.contains("dood") -> 0
                url.contains("streamtape") -> 1
                url.contains("voe.sx") -> 2
                
                // Tier 2: Server Menengah
                url.contains("vidhide") -> 5
                url.contains("filemoon") -> 6
                url.contains("player4me") -> 7
                
                // Tier 3: Server Lambat / Sering Captcha / Iklan Berat
                url.contains("mixdrop") -> 10
                url.contains("streamsb") -> 11
                url.contains("lulustream") -> 12
                
                // Tier 4: Default (Lainnya)
                else -> 20
            }
        }

        // --- EKSEKUSI PARALEL PRIORITAS TINGGI ---
        if (potentialLinks.isNotEmpty()) {
            // Urutkan link berdasarkan prioritas sebelum diluncurkan ke thread pool
            // Server prioritas 0 akan masuk antrean eksekusi pertama
            val sortedLinks = potentialLinks.sortedBy { getServerPriority(it) }

            // Menggunakan ioSafe (Structured Concurrency Wrapper milik Cloudstream)
            // Ini memastikan thread IO digunakan dan lifecycle terjaga
            Coroutines.ioSafe {
                sortedLinks.map { link ->
                    // Launch setiap link di coroutine terpisah secara paralel
                    launch {
                        try {
                            // Panggil loadExtractor dengan safety try-catch per link
                            // Jika satu link gagal, dia TIDAK akan membatalkan link lain (Isolation)
                            loadExtractor(link, data, subtitleCallback, callback)
                        } catch (e: Exception) {
                            // Silent fail agar user tidak terganggu notifikasi error internal
                        }
                    }
                }.joinAll() // Tunggu semua "launch" selesai sebelum keluar dari fungsi loadLinks
                // Ini memastikan "Collect All": kita tidak memotong proses di tengah jalan
            }
            return true
        }

        return false
    }
}
