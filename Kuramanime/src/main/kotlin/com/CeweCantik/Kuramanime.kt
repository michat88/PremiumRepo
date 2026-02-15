package com.CeweCantik

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.extractors.FilemoonV2
import com.lagradost.cloudstream3.extractors.Filesim
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.nodes.Element
import org.jsoup.Jsoup

class Kuramanime : MainAPI() {
    override var mainUrl = "https://v8.kuramanime.blog"
    override var name = "Kuramanime"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    
    // Auth default dari file referensi, akan diupdate otomatis oleh fetchAuth()
    private var authorization: String? = "KFhElffuFYZZHAqqBqlGewkwbaaFUtJS"
    
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val commonUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // ================= HELPER FUNCTIONS =================
    
    private fun getHeaders(referer: String = mainUrl): Map<String, String> {
        return mapOf(
            "User-Agent" to commonUserAgent,
            "Referer" to referer,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Upgrade-Insecure-Requests" to "1"
        )
    }

    private fun Element.getImageUrl(): String? {
        return this.attr("data-setbg").ifEmpty { this.attr("src") }
    }

    private fun fixTitle(title: String): String {
        return title.trim()
    }

    // ================= MAIN PAGE & SEARCH =================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePageList = ArrayList<HomePageList>()

        // Mengambil Ongoing (Sedang Tayang)
        // Menggunakan /quick/ongoing karena lebih ringan dan spesifik
        val ongoingDoc = app.get("$mainUrl/quick/ongoing?order_by=latest&page=$page", headers = getHeaders()).document
        val ongoingList = ongoingDoc.select("div.product__item").mapNotNull { it.toSearchResult() }

        // Mengambil Terbaru (Updated)
        val latestDoc = app.get("$mainUrl/anime?order_by=updated&page=$page", headers = getHeaders()).document
        val latestList = latestDoc.select("div.product__item").mapNotNull { it.toSearchResult() }

        if (ongoingList.isNotEmpty()) homePageList.add(HomePageList("Sedang Tayang", ongoingList))
        if (latestList.isNotEmpty()) homePageList.add(HomePageList("Baru Diupdate", latestList))

        if (homePageList.isEmpty()) throw ErrorLoadingException("Gagal memuat data dari $mainUrl")

        return newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/anime?search=$query&order_by=latest"
        val document = app.get(url, headers = getHeaders()).document
        return document.select("div.product__item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".product__item__text h5 a")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val image = this.selectFirst(".product__item__pic")?.getImageUrl()
        val epText = this.selectFirst(".ep")?.text()?.trim() ?: ""
        
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = image
            addQuality(epText)
        }
    }

    // ================= LOAD DETAILS =================

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = getHeaders()).document

        val title = document.selectFirst(".anime__details__title h3")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst(".anime__details__pic")?.getImageUrl()
        val synopsis = document.selectFirst(".anime__details__text p")?.text()?.trim()

        var type = TvType.Anime
        var status = ShowStatus.Ongoing
        var year: Int? = null

        document.select(".anime__details__widget ul li").forEach { li ->
            val text = li.text()
            if (text.contains("Tipe", true) && text.contains("Movie", true)) type = TvType.AnimeMovie
            if (text.contains("Status", true) && (text.contains("Selesai", true) || text.contains("Finished", true))) status = ShowStatus.Completed
            if (text.contains("Musim", true)) {
                Regex("(\\d{4})").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { year = it }
            }
        }

        // Mengambil episode dari pagination atau list
        val episodes = ArrayList<Episode>()
        
        // Cek apakah ada pagination episode (Logic dari file referensi)
        val episodeListContent = document.select("#episodeLists").attr("data-content")
        if (episodeListContent.isNotEmpty()) {
             val epDoc = Jsoup.parse(episodeListContent)
             epDoc.select("a.btn").forEach { 
                 val epNum = Regex("(\\d+)").find(it.text())?.groupValues?.get(1)?.toIntOrNull()
                 episodes.add(newEpisode(it.attr("href")) {
                     this.name = it.text()
                     this.episode = epNum
                 })
             }
        } else {
            // Fallback ke list biasa jika data-content kosong
            document.select("#episodeLists a").forEach {
                val epNum = Regex("Episode\\s+(\\d+)").find(it.text())?.groupValues?.get(1)?.toIntOrNull()
                episodes.add(newEpisode(it.attr("href")) {
                    this.name = it.text()
                    this.episode = epNum
                })
            }
        }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = poster
            this.plot = synopsis
            this.year = year
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, episodes.reversed())
        }
    }

    // ================= LOAD LINKS (THE BEAST / LEVIATHAN LOGIC) =================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val req = app.get(data, headers = getHeaders())
        val doc = req.document
        val cookies = req.cookies

        val csrfToken = doc.selectFirst("meta[name=csrf-token]")?.attr("content") 
            ?: return false
        
        // 1. Ambil script 'data-kk' untuk mendapatkan kunci rahasia
        val dataKk = doc.selectFirst("div[data-kk]")?.attr("data-kk") 
            ?: doc.selectFirst("div.col-lg-12.mt-3")?.attr("data-kk") // Fallback selector
            ?: return false

        val assets = getAssets(dataKk)

        // 2. Dapatkan Token Halaman
        var headers = mapOf(
            "X-CSRF-TOKEN" to csrfToken,
            "X-Fuck-ID" to "${assets.MIX_AUTH_KEY}:${assets.MIX_AUTH_TOKEN}",
            "X-Request-ID" to randomId(),
            "X-Request-Index" to "0",
            "X-Requested-With" to "XMLHttpRequest",
            "User-Agent" to commonUserAgent,
            "Referer" to data
        )

        // URL untuk mendapatkan token dinamis
        val tokenUrl = "$mainUrl/${assets.MIX_PREFIX_AUTH_ROUTE_PARAM}${assets.MIX_AUTH_ROUTE_PARAM}"
        val tokenKey = app.get(tokenUrl, headers = headers, cookies = cookies).text

        // 3. Loop semua server dan POST request
        headers = mapOf(
            "X-CSRF-TOKEN" to csrfToken,
            "X-Requested-With" to "XMLHttpRequest",
            "User-Agent" to commonUserAgent,
            "Referer" to data
        )

        doc.select("select#changeServer option").forEach { source ->
            val server = source.attr("value")
            // Skip server kosong atau placeholder
            if (server.isBlank()) return@forEach

            // Construct link rahasia dengan parameter campuran
            val link = "$data?${assets.MIX_PAGE_TOKEN_KEY}=$tokenKey&${assets.MIX_STREAM_SERVER_KEY}=$server"
            
            try {
                // KuramaDrive butuh penanganan khusus (biasanya direct link)
                if (server.contains("kuramadrive", true) || server.contains("archive", true)) {
                    invokeLocalSource(link, server, headers, cookies, subtitleCallback, callback)
                } else {
                    // Server lain (Sunrong/Filemoon, StreamSB, dll) via iframe
                    val postRes = app.post(
                        link,
                        data = mapOf("authorization" to getAuth()),
                        referer = data,
                        headers = headers,
                        cookies = cookies
                    )
                    
                    val iframeSrc = postRes.document.select("div.iframe-container iframe").attr("src")
                    if (iframeSrc.isNotEmpty()) {
                        loadExtractor(iframeSrc, "$mainUrl/", subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                // Ignore failed servers
            }
        }
        return true
    }

    // ================= AUTH & ASSETS LOGIC =================

    private suspend fun invokeLocalSource(
        url: String,
        server: String,
        headers: Map<String, String>,
        cookies: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.post(
            url,
            data = mapOf("authorization" to getAuth()),
            headers = headers,
            cookies = cookies
        ).document

        doc.select("video#player > source").forEach {
            val link = it.attr("src")
            val quality = it.attr("size").toIntOrNull() ?: Qualities.Unknown.value
            callback.invoke(
                newExtractorLink(
                    name,
                    "$name $server",
                    link,
                    INFER_TYPE,
                    quality
                )
            )
        }
    }

    data class Assets(
        val MIX_PREFIX_AUTH_ROUTE_PARAM: String,
        val MIX_AUTH_ROUTE_PARAM: String,
        val MIX_AUTH_KEY: String,
        val MIX_AUTH_TOKEN: String,
        val MIX_PAGE_TOKEN_KEY: String,
        val MIX_STREAM_SERVER_KEY: String,
    )

    private suspend fun getAssets(bpjs: String): Assets {
        val jsUrl = "$mainUrl/assets/js/$bpjs.js"
        val env = app.get(jsUrl, headers = mapOf("User-Agent" to commonUserAgent)).text
        
        // Helper untuk parse variabel JS
        fun getVar(name: String): String {
            return env.substringAfter("$name: '").substringBefore("',")
        }

        return Assets(
            getVar("MIX_PREFIX_AUTH_ROUTE_PARAM"),
            getVar("MIX_AUTH_ROUTE_PARAM"),
            getVar("MIX_AUTH_KEY"),
            getVar("MIX_AUTH_TOKEN"),
            getVar("MIX_PAGE_TOKEN_KEY"),
            getVar("MIX_STREAM_SERVER_KEY")
        )
    }

    private suspend fun getAuth(): String {
        return fetchAuth() // Selalu fetch baru agar tidak expired
    }

    private suspend fun fetchAuth(): String {
        val url = "$mainUrl/storage/leviathan.js?v=942" // Versi v dari log curl kamu
        val res = app.get(url, headers = mapOf("User-Agent" to commonUserAgent)).text
        
        // Logic parsing auth array dari leviathan.js (Sesuai referensi)
        val authMatch = Regex("""=\s*\[(.*?)]""").find(res)
        val authList = authMatch?.groupValues?.get(1)
            ?.split(",")
            ?.map { it.trim().removeSurrounding("'").removeSurrounding("\"") }
            ?: throw ErrorLoadingException("Gagal parse Leviathan Auth")

        // Rekonstruksi token auth (Logic: last + 9 + 1 + first + 'i')
        // Pastikan array cukup panjang agar tidak crash
        if (authList.size < 10) return "KFhElffuFYZZHAqqBqlGewkwbaaFUtJS" 

        return "${authList.last()}${authList[9]}${authList[1]}${authList.first()}i"
    }

    private fun randomId(length: Int = 6): String {
        val allowedChars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }
}

// ================= CUSTOM EXTRACTORS (Included) =================

class Sunrong : FilemoonV2() {
    override var mainUrl = "https://sunrong.my.id"
    override var name = "Sunrong"
}

class Nyomo : StreamSB() {
    override var name: String = "Nyomo"
    override var mainUrl = "https://nyomo.my.id"
}

class Streamhide : Filesim() {
    override var name: String = "Streamhide"
    override var mainUrl: String = "https://streamhide.to"
}

open class Lbx : ExtractorApi() {
    override val name = "Linkbox"
    override val mainUrl = "https://lbx.to"
    private val realUrl = "https://www.linkbox.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Implementasi Linkbox sederhana jika diperlukan
        callback.invoke(
            newExtractorLink(name, name, url, INFER_TYPE)
        )
    }
}
