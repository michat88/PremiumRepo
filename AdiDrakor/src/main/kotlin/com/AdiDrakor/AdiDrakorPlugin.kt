package com.AdiDrakor

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AdiDrakorPlugin : Plugin() {
    override fun load(context: Context) {
        // Register Main Provider
        registerMainAPI(AdiDrakor())
        
        // Register Extractors
        // Mendaftarkan Jeniusplay yang baru (dari IdlixProvider)
        registerExtractorAPI(Jeniusplay())
    }
}
