package com.SiaranIslam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class SiaranIslamProvider : MainAPI() {
    override var mainUrl = "https://raw.githubusercontent.com/michat88/Zaneta/main/SiaranIslam.m3u"
    override var name = "Siaran Islam"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Live)

    // Perbaikan: Hindari infinite scroll dengan mendaftarkan 1 kategori statis saja
    override val mainPage = listOf(
        MainPageData(name = "Siaran TV Islam", data = mainUrl, horizontalImages = false)
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val channels = mutableListOf<SearchResponse>()
        
        // Perbaikan: Hanya load halaman pertama. Jika minta halaman 2, kembalikan kosong.
        if (page > 1) {
            return newHomePageResponse(request.name, channels, hasNext = false)
        }

        try {
            // Download M3U
            val responseText = app.get(request.data).text
            val lines = responseText.lines()
            
            var currentName = ""
            var currentLogo = ""

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                if (trimmed.startsWith("#EXTINF:")) {
                    // Ambil Nama
                    currentName = trimmed.substringAfterLast(",").trim()
                    // Jika kosong, beri nama default
                    if (currentName.isEmpty()) currentName = "Siaran Live"

                    // Ambil Logo
                    val logoRegex = """tvg-logo="([^"]+)"""".toRegex()
                    val logoUrl = logoRegex.find(trimmed)?.groupValues?.get(1) ?: ""
                    
                    // Perbaikan: Hanya pakai logo jika itu URL yang valid (http/https)
                    currentLogo = if (logoUrl.startsWith("http")) logoUrl else ""

                } else if (trimmed.startsWith("http")) {
                    channels.add(
                        newLiveSearchResponse(
                            name = currentName,
                            url = trimmed, 
                            type = TvType.Live,
                            fix = false
                        ) {
                            this.posterUrl = currentLogo
                        }
                    )
                    currentName = ""
                    currentLogo = ""
                }
            }
        } catch (e: Exception) {
            // Tangkap error jika gagal donwload M3U
        }

        // Perbaikan: Set hasNext = false agar scroll tidak berputar terus
        return newHomePageResponse(request.name, channels, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        // Perbaikan: Beri nama channel dari judul (opsional, karena URL yang dikirim di sini)
        // Cloudstream akan menampilkan ini di halaman detail sebelum player terbuka.
        return newLiveStreamLoadResponse(
            name = "Live Streaming",
            url = url,
            dataUrl = url
        ) {
            this.plot = "Siaran langsung. Selamat menonton." // Tambahkan detail singkat
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Perbaikan: Deteksi tipe dengan lebih akurat
        val linkType = when {
            data.contains(".m3u8") -> ExtractorLinkType.M3U8
            data.contains(".mpd") -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }

        // Perbaikan: Tambahkan header User-Agent untuk mencegah blokir/buffering dari server tertentu
        val streamHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
        )

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "Server 1",
                url = data,
                type = linkType
            ) {
                this.quality = Qualities.Unknown.value
                this.headers = streamHeaders // Masukkan header
            }
        )
        return true
    }
}
