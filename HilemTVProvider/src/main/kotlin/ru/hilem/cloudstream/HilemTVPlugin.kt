package ru.hilem.cloudstream

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class HilemTVPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(HilemTVProvider())
    }
}
