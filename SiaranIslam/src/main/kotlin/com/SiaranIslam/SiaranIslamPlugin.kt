package com.SiaranIslam

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class SiaranIslamPlugin : Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan Provider Siaran Islam
        registerMainAPI(SiaranIslamProvider())
    }
}
