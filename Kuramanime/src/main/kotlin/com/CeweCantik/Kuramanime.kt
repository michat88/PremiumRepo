package com.CeweCantik

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class Kuramanime : MainAPI() {
    override var mainUrl = "https://v8.kuramanime.blog"
    override var name = "Kuramanime"
    override var lang = "id"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // Header "Sakti" dari log CURL kamu
    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/javascript, */*; q=0.01", // Minta JSON
        "X-Requested-With" to "XMLHttpRequest", // Wajib untuk API
        "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Linux\""
    )

    // ==========================================
    // DATA CLASSES (Mapping JSON)
    // ==========================================
    data class KuramaResponse(
        @JsonProperty("data") val data: List<KuramaAnime>? = null,
        @JsonProperty("current_page") val currentPage: Int? = null,
        // Untuk handle respon root yang punya key berbeda
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
        @JsonProperty("synopsis") val synopsis: String? = null,
        @JsonProperty("image_portrait_url") val imagePortraitUrl: String? = null, // Gambar Tegak
        @JsonProperty("image_landscape_url") val imageLandscapeUrl: String? = null, // Gambar Lebar
        @JsonProperty("posts") val posts: List<KuramaPost>? = null, // List Episode
        @JsonProperty("score") val score: Double? = null,
        @JsonProperty("type") val type: String? = null,
    )

    data class KuramaPost(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("episode") val episode: String? = null, // Bisa string atau int di JSON
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("anime_id") val animeId: Int? = null
    )

    // ==========================================
    // 1. HALAMAN UTAMA (HOME)
    // ==========================================
    override val mainPage = mainPageOf(
        "$mainUrl/quick/ongoing?order_by=updated&page=" to "Sedang Tayang",
        "$mainUrl/quick/finished?order_by=updated&page=" to "Selesai Tayang",
        "$mainUrl/quick/movie?order_by=updated&page=" to "Film Layar Lebar",
        "$mainUrl/quick/donghua?order_by=updated&page=" to "Donghua"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Tambahkan need_json=true agar server membalas dengan JSON
        val url = request.data + page + "&need_json=true"
        
        // Request
        val response = app.get(url, headers = commonHeaders).text
        
        // Parsing JSON
        val json = parseJson<KuramaResponse>(response)
        
        // Ambil data (support format direct page atau root object)
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

    // Ubah anime object dari JSON ke SearchResponse CloudStream
    private fun toSearchResult(anime: KuramaAnime): SearchResponse? {
        val title = anime.title ?: return null
        val id = anime.id ?: return null
        val slug = anime.slug ?: ""
        
        // Buat URL manual: /anime/{id}/{slug}
        val url = "$mainUrl/anime/$id/$slug"
        
        // Ambil gambar kualitas tinggi dari JSON
        val poster = anime.imagePortraitUrl ?: anime.imageLandscapeUrl

        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            // Tampilkan info tambahan di kartu
            if (anime.score != null) {
                addQuality("⭐ ${anime.score}")
            }
        }
    }

    // ==========================================
    // 2. PENCARIAN (SEARCH)
    // ==========================================
    override suspend fun search(query: String): List<SearchResponse> {
        // Search API juga support JSON
        val url = "$mainUrl/anime?search=$query&order_by=latest&need_json=true"
        val response = app.get(url, headers = commonHeaders).text
        val json = parseJson<KuramaResponse>(response)
        
        return json.data?.mapNotNull { toSearchResult(it) } ?: emptyList()
    }

    // ==========================================
    // 3. DETAIL ANIME & EPISODE (LOAD)
    // ==========================================
    override suspend fun load(url: String): LoadResponse {
        // Request Detail dengan JSON
        // Contoh: .../anime/4484/seihantai...?need_json=true
        val jsonUrl = "$url?need_json=true"
        
        // Parsing JSON
        // Perhatikan: Kadang detail page JSON-nya langsung object Anime, kadang dibungkus.
        // Kita coba parse aman.
        val responseText = app.get(jsonUrl, headers = commonHeaders).text
        
        // Manual parsing sedikit jika struktur detail berbeda dengan list
        // Tapi biasanya struktur "KuramaAnime" di atas sudah mencakup detail (ada 'posts')
        // Kita coba parse sebagai KuramaAnime langsung, atau cari wrapper.
        // *Fallback logic*: Jika gagal parse JSON, kita pakai metode HTML sebagai cadangan.
        
        try {
            // Coba anggap response adalah objek anime langsung (karena biasanya detail API begitu)
            // Atau mungkin dibungkus keys tertentu.
            // Berdasarkan curl user, 'posts' ada di dalam objek anime.
            // Kita coba parse generic dulu
            
            // NOTE: Karena saya tidak melihat raw JSON response untuk *Halaman Detail*, 
            // Saya asumsikan field-nya mirip dengan item di Home (KuramaAnime).
            // Jika error, blok 'catch' akan menangani dengan HTML parsing.
            val anime = parseJson<KuramaAnime>(responseText)
            
            val title = anime.title ?: "Unknown Title"
            val poster = anime.imagePortraitUrl ?: anime.imageLandscapeUrl
            val synopsis = anime.synopsis ?: ""
            
            // Ambil Episode dari 'posts' array di JSON
            val episodes = anime.posts?.mapNotNull { post ->
                val epNum = post.episode?.toString()?.toIntOrNull()
                val epTitle = post.title ?: "Episode $epNum"
                
                // URL Episode: /anime/{anime_id}/{slug}/episode/{episode_num}
                // Kita perlu slug. Jika di detail JSON tidak ada slug (jarang terjadi), kita bisa extract dari URL input.
                val safeSlug = anime.slug ?: url.split("/").getOrElse(5) { "" }
                val epUrl = "$mainUrl/anime/${anime.id}/$safeSlug/episode/$epNum"

                newEpisode(epUrl) {
                    this.name = epTitle
                    this.episode = epNum
                }
            }?.reversed() ?: emptyList() // Reverse biar ep terbaru di atas

            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = synopsis
                this.rating = anime.score?.toInt()
                if (episodes.isNotEmpty()) {
                    addEpisodes(DubStatus.Subbed, episodes)
                }
            }

        } catch (e: Exception) {
            // FALLBACK KE HTML PARSING (Metode Lama yang sudah diperbaiki headernya)
            // Ini jalan kalau format JSON detail beda dengan dugaan kita
            val document = app.get(url, headers = commonHeaders).document
            
            val title = document.select("meta[property=og:title]").attr("content")
                .replace(Regex("\\(Episode.*\\)"), "")
                .replace("Subtitle Indonesia - Kuramanime", "").trim()
            val poster = document.select("meta[property=og:image]").attr("content")
            val description = document.select("meta[name=description]").attr("content")

            val episodes = document.select("#animeEpisodes a, .filter__gallery a[href*='/episode/']").mapNotNull { ep ->
                val epUrl = fixUrl(ep.attr("href"))
                val epName = ep.text().trim()
                val epNum = Regex("\\d+").find(epName)?.value?.toIntOrNull()
                
                if(epUrl.contains("/episode/")) {
                    newEpisode(epUrl) {
                        this.name = epName
                        this.episode = epNum
                    }
                } else null
            }.reversed()

            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = description
                if (episodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    // ==========================================
    // 4. LOAD VIDEO
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        // Gunakan header AJAX untuk load video player
        val document = app.get(data, headers = commonHeaders).document
        val serverOptions = document.select("select#changeServer option")

        if (serverOptions.isNotEmpty()) {
            serverOptions.forEach { option ->
                val serverValue = option.attr("value")
                val serverName = option.text()
                if (serverName.contains("vip", true)) return@forEach

                val serverUrl = "$data?server=$serverValue"
                try {
                    // Request ulang dengan header yang benar
                    val doc = app.get(serverUrl, headers = commonHeaders).document
                    val iframe = doc.select("iframe").attr("src")
                    if (iframe.isNotBlank()) {
                        loadExtractor(iframe, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {}
            }
        } else {
            // Fallback iframe langsung
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
