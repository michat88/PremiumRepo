package com.CeweCantik

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.FilemoonV2
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

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
        val token = Regex("""(?:/f/|/file/|\?id=)(\w+)""").find(url)?.groupValues?.get(1)
        val id =
            app.get("$realUrl/api/file/share_out_list/?sortField=utime&sortAsc=0&pageNo=1&pageSize=50&shareToken=$token")
                .parsedSafe<Responses>()?.data?.itemId
        app.get("$realUrl/api/file/detail?itemId=$id", referer = url)
            .parsedSafe<Responses>()?.data?.itemInfo?.resolutionList?.map { link ->
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        link.url ?: return@map null,
                        INFER_TYPE
                    ) {
                        this.referer = "$realUrl/"
                        this.quality = getQualityFromName(link.resolution)
                    }
                )
            }
    }

    data class Resolutions(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("resolution") val resolution: String? = null,
    )

    data class ItemInfo(
        @JsonProperty("resolutionList") val resolutionList: ArrayList<Resolutions>? = arrayListOf(),
    )

    data class Data(
        @JsonProperty("itemInfo") val itemInfo: ItemInfo? = null,
        @JsonProperty("itemId") val itemId: String? = null,
    )

    data class Responses(
        @JsonProperty("data") val data: Data? = null,
    )

}

open class Kuramadrive : ExtractorApi() {
    override val name = "Kuramadrive"
    override val mainUrl = "https://v8.kuramanime.blog"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // URL format internal kita: https://v8.kuramanime.blog/kuramadrive?pid=123&sid=456
        val pid = Regex("pid=(\\d+)").find(url)?.groupValues?.get(1) ?: return
        val sid = Regex("sid=(\\d+)").find(url)?.groupValues?.get(1) ?: return

        try {
            // Request Token Google Drive (Sesuai log user)
            val json = app.post(
                "$mainUrl/misc/token/drive-token",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/"
                ),
                data = mapOf("pid" to pid, "sid" to sid)
            ).parsedSafe<DriveTokenResponse>()

            val accessToken = json?.accessToken ?: return
            val gid = json.gid ?: return // Google Drive File ID

            // Construct Direct Google API Link
            val driveUrl = "https://www.googleapis.com/drive/v3/files/$gid?alt=media"

            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    driveUrl,
                    INFER_TYPE
                ) {
                    // Masukkan Access Token ke Header
                    this.headers = mapOf(
                        "Authorization" to "Bearer $accessToken"
                    )
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    data class DriveTokenResponse(
        @JsonProperty("access_token") val accessToken: String? = null,
        @JsonProperty("gid") val gid: String? = null
    )
}
