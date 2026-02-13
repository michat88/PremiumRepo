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

    // ==============================
    // 1. KONFIGURASI KATEGORI (JANGAN DIUBAH - SUDAH PERFECT)
    // ==============================
    override val mainPage = mainPageOf(
        "https://missav.ws/dm628/id/uncensored-leak" to "Kebocoran Tanpa Sensor",
        "https://missav.ws/dm590/id/release" to "Keluaran Terbaru",
        "https://missav.ws/dm515/id/new" to "Recent Update",
        "https://missav.ws/dm68/id/genres/Wanita%20Menikah/Ibu%20Rumah%20Tangga" to "Wanita menikah"
    )

    private fun String.toUrl(): String {
        return if (this.startsWith("http")) this else "https://missav.ws$this"
    }

    // ==============================
    // 2. HOME PAGE (JANGAN DIUBAH - SUDAH PERFECT)
    // ==============================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) {
            request.data
        } else {
            val separator = if (request.data.contains("?")) "&" else "?"
            "${request.data}${separator}page=$page"
        }

        val document = app.get(url).document
        
        val items = document.select("div.thumbnail.group").mapNotNull { element ->
            toSearchResult(element)
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // Helper: Mengubah elemen HTML video menjadi data SearchResponse
    private fun toSearchResult(element: Element): SearchResponse? {
        val linkElement = element.selectFirst("a.text-secondary") ?: return null
        val url = linkElement.attr("href").toUrl()
        val title = linkElement.text().trim()
        val imgElement = element.selectFirst("img")
        
        val posterUrl = imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") }

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // ==============================
    // 3. PENCARIAN (PERBAIKAN DI SINI)
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/$query"
        val document = app.get(url).document
        
        return document.select("div.thumbnail.group").mapNotNull { element ->
            toSearchResult(element)
        }
    }

    // ==============================
    // 4. DETAIL VIDEO (JANGAN DIUBAH - SUDAH PERFECT)
    // ==============================
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

        val durationSeconds = document.selectFirst("meta[property=og:video:duration]")
            ?.attr("content")?.toIntOrNull()
        val durationMinutes = durationSeconds?.div(60)

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.actors = actors
            this.year = year
            this.duration = durationMinutes
        }
    }

    // ==============================
    // 5. PLAYER + SUBTITLE (DITAMBAHKAN FITUR SUBTITLECAT)
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Kita ambil document agar bisa ambil Judul untuk pencarian subtitle
        val document = app.get(data).document
        val text = document.html() // Mengambil raw text untuk regex player
        
        // 1. Logika Player (Original)
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

            // 2. Logika Subtitle (BARU DITAMBAHKAN)
            // Mencoba mengambil Kode Video dari Judul (misal: SSIS-669) agar pencarian akurat
            val title = document.selectFirst("h1.text-base.text-nord6")?.text()?.trim() ?: ""
            // Regex mencari pola kode umum (Huruf-Angka), contoh: ABC-123
            val codeRegex = """([A-Za-z]{2,5}-[0-9]{3,5})""".toRegex()
            val codeMatch = codeRegex.find(title)
            
            // Jika ketemu kode (SSIS-123), cari pakai kode. Jika tidak, cari pakai judul full.
            val query = codeMatch?.value ?: title
            
            if (query.isNotBlank()) {
                fetchSubtitleCat(query, subtitleCallback)
            }
            
            return true
        }

        return false
    }

    // ==============================
    // 6. HELPER SUBTITLE (BARU)
    // ==============================
    private suspend fun fetchSubtitleCat(query: String, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            // URL Search SubtitleCat
            val searchUrl = "https://www.subtitlecat.com/index.php?search=$query"
            val doc = app.get(searchUrl).document

            // Parsing HTML sesuai data yang diberikan user
            // Mencari elemen <div class="sub-single">
            doc.select("div.sub-single").forEach { element ->
                // Span ke-2 adalah Nama Bahasa (Span ke-1 itu Bendera)
                val lang = element.select("span").getOrNull(1)?.text()?.trim() ?: "Unknown"
                
                // Link download ada di class "green-link"
                val href = element.selectFirst("a.green-link")?.attr("href") ?: ""

                if (href.isNotEmpty()) {
                    // Link bersifat relative, tambahkan domain utama
                    val fullUrl = "https://www.subtitlecat.com$href"
                    
                    subtitleCallback.invoke(
                        SubtitleFile(lang, fullUrl)
                    )
                }
            }
        } catch (e: Exception) {
            // Error handling diam (silent fail) agar video tetap jalan meski sub gagal
            e.printStackTrace()
        }
    }
}
