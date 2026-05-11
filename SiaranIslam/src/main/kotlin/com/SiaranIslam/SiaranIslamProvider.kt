package com.SiaranIslam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class SiaranIslamProvider : MainAPI() {
    // URL Raw menuju file SiaranIslam.m3u di repo Zaneta milikmu
    override var mainUrl = "https://raw.githubusercontent.com/michat88/Zaneta/main/SiaranIslam.m3u"
    override var name = "Siaran Islam"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        mainUrl to "Siaran Islam TV"
    )

    // Membaca dan menampilkan daftar channel dari file M3U di Halaman Utama
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Mengunduh isi file M3U dari GitHub
        val response = app.get(request.data).text
        val lines = response.lines()
        val channels = mutableListOf<SearchResponse>()
        
        var currentName = ""
        var currentLogo = ""
        
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            
            if (trimmed.startsWith("#EXTINF:")) {
                // Mengambil nama channel (teks setelah tanda koma terakhir)
                currentName = trimmed.substringAfterLast(",").trim()
                
                // Mengambil link logo jika ada
                val logoRegex = """tvg-logo="([^"]+)"""".toRegex()
                currentLogo = logoRegex.find(trimmed)?.groupValues?.get(1) ?: ""
                
            } else if (trimmed.startsWith("http")) {
                // Menambahkan channel ke daftar menggunakan DSL Builder Cloudstream
                channels.add(
                    newLiveSearchResponse(
                        name = currentName,
                        url = trimmed, // Menyimpan URL streaming langsung
                        type = TvType.Live,
                        fix = false
                    ) {
                        this.posterUrl = currentLogo
                    }
                )
                // Reset nama dan logo untuk channel berikutnya
                currentName = ""
                currentLogo = ""
            }
        }

        return newHomePageResponse(request, channels)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Opsional: Kamu bisa membuat fitur pencarian di sini nanti
        return emptyList()
    }

    // Ketika channel di-klik, langsung teruskan URL streaming-nya
    override suspend fun load(url: String): LoadResponse {
        return newLiveStreamLoadResponse(
            name = "Siaran Live",
            url = url,
            dataUrl = url
        )
    }

    // Melemparkan URL streaming ke pemutar video (ExoPlayer)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = data,
                referer = "",
                quality = Qualities.Unknown.value,
                isM3u8 = data.contains(".m3u8") // Deteksi otomatis jika formatnya m3u8
            )
        )
        return true
    }
}
