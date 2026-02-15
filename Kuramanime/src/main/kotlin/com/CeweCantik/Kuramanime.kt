package com.CeweCantik

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
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

    // Header yang meniru browser asli (PENTING)
    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "X-Requested-With" to "XMLHttpRequest",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Linux\""
    )

    // ==========================================
    // JSON DATA CLASSES (Untuk Home & Search)
    // ==========================================
    data class KuramaResponse(
        @JsonProperty("data") val data: List<KuramaAnime>? = null,
        @JsonProperty("ongoingAnimes") val ongoingAnimes: KuramaPage? = null,
        @JsonProperty("finishedAnimes") val finishedAnimes: KuramaPage? = null,
        @JsonProperty("movieAnimes") val movieAnimes: KuramaPage? = null,
    )

    data class KuramaPage(
        @JsonProperty("data") val data: List<KuramaAnime>? = null
    )

    data class KuramaAnime(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("image_portrait_url") val imagePortraitUrl: String? = null,
        @JsonProperty("image_landscape_url") val imageLandscapeUrl: String? = null,
        @JsonProperty("score") val score: Double? = null,
    )

    // ==========================================
    // 1. HOME (Menggunakan JSON API)
    // ==========================================
    override val mainPage = mainPageOf(
        "$mainUrl/quick/ongoing?order_by=updated&page=" to "Sedang Tayang",
        "$mainUrl/quick/finished?order_by=updated&page=" to "Selesai Tayang",
        "$mainUrl/quick/movie?order_by=updated&page=" to "Film Layar Lebar",
        "$mainUrl/quick/donghua?order_by=updated&page=" to "Donghua"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Gunakan ?need_json=true untuk Home agar cepat
        val url = request.data + page + "&need_json=true"
        val response = app.get(url, headers = commonHeaders).text
        val json = parseJson<KuramaResponse>(response)
        
        val animeList = json.data 
            ?: json.ongoingAnimes?.data 
            ?: json.finishedAnimes?.data 
            ?: json.movieAnimes?.data 
            ?: emptyList()

        val home = animeList.mapNotNull { anime ->
            toSearchResult(anime)
        }

        return newHomePageResponse(request.name, home)
    }

    private fun toSearchResult(anime: KuramaAnime): SearchResponse? {
        val title = anime.title ?: return null
        val id = anime.id ?: return null
        val slug = anime.slug ?: ""
        
        // URL Detail tetap normal (tanpa need_json)
        val url = "$mainUrl/anime/$id/$slug"
        val poster = anime.imagePortraitUrl ?: anime.imageLandscapeUrl

        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            if (anime.score != null) {
                addQuality("⭐ ${anime.score}")
            }
        }
    }

    // ==========================================
    // 2. SEARCH (Menggunakan JSON API)
    // ==========================================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/anime?search=$query&order_by=latest&need_json=true"
        val response = app.get(url, headers = commonHeaders).text
        val json = parseJson<KuramaResponse>(response)
        
        return json.data?.mapNotNull { toSearchResult(it) } ?: emptyList()
    }

    // ==========================================
    // 3. LOAD (DETAIL) - KEMBALI KE HTML
    // ==========================================
    // Halaman detail TIDAK support JSON, jadi kita parsing HTML
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = commonHeaders).document

        // Ambil Data dari Meta Tags (Lebih stabil)
        val rawTitle = document.select("meta[property=og:title]").attr("content")
        val title = rawTitle
            .replace(Regex("\\(Episode.*\\)"), "")
            .replace("Subtitle Indonesia - Kuramanime", "")
            .trim()

        val poster = document.select("meta[property=og:image]").attr("content")
        val description = document.select("meta[name=description]").attr("content")

        // Ambil Episode dari HTML (Sesuai temuan Python Script)
        // Selector: #animeEpisodes a
        val episodes = document.select("#animeEpisodes a").mapNotNull { ep ->
            val epUrl = fixUrl(ep.attr("href"))
            val epName = ep.text().trim()
            val epNum = Regex("\\d+").find(epName)?.value?.toIntOrNull()

            if (epUrl.contains("/episode/")) {
                newEpisode(epUrl) {
                    this.name = epName
                    this.episode = epNum
                }
            } else null
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            
            // FIX SCORE: Mengisi properti score secara langsung
            // Kita coba cari score dari halaman jika ada, atau biarkan null
            // (Halaman detail kadang tidak menampilkan score numerik di HTML dengan mudah)
            
            if (episodes.isNotEmpty()) {
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    // ==========================================
    // 4. LOAD LINKS (VIDEO)
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        // Header khusus untuk AJAX/Player
        val ajaxHeaders = commonHeaders + mapOf(
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty"
        )

        val document = app.get(data, headers = commonHeaders).document
        val serverOptions = document.select("select#changeServer option")

        if (serverOptions.isNotEmpty()) {
            serverOptions.forEach { option ->
                val serverValue = option.attr("value")
                val serverName = option.text()
                
                // Skip server VIP
                if (serverName.contains("vip", true)) return@forEach

                val serverUrl = "$data?server=$serverValue"
                try {
                    val doc = app.get(serverUrl, headers = ajaxHeaders).document
                    val iframe = doc.select("iframe").attr("src")
                    if (iframe.isNotBlank()) {
                        loadExtractor(iframe, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {}
            }
        } else {
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
