package ru.hilem.cloudstream

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class HilemTVProvider : MainAPI() {
    override var mainUrl = "https://tv.hilem.ru"
    override var name = "хилемTV"
    override var lang = "ru"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val hasMainPage = true
    override val loadLinksTimeoutMs: Long? = 180_000L
    override val mainPage = mainPageOf(
        "/cloudstream/catalog?section=popular" to "Популярное",
        "/cloudstream/catalog?section=new" to "Новинки",
        "/cloudstream/catalog?type=movie" to "Фильмы",
        "/cloudstream/catalog?type=series" to "Сериалы",
        "/cloudstream/catalog?genre=%D0%B4%D1%80%D0%B0%D0%BC%D0%B0" to "Драмы",
        "/cloudstream/catalog?genre=%D0%BA%D0%BE%D0%BC%D0%B5%D0%B4%D0%B8%D1%8F" to "Комедии",
        "/cloudstream/catalog?genre=%D1%82%D1%80%D0%B8%D0%BB%D0%BB%D0%B5%D1%80" to "Триллеры",
        "/cloudstream/catalog?genre=%D1%84%D0%B0%D0%BD%D1%82%D0%B0%D1%81%D1%82%D0%B8%D0%BA%D0%B0" to "Фантастика",
        "/cloudstream/catalog?genre=%D1%83%D0%B6%D0%B0%D1%81%D1%8B" to "Ужасы",
        "/cloudstream/catalog?genre=%D0%BC%D1%83%D0%BB%D1%8C%D1%82%D1%84%D0%B8%D0%BB%D1%8C%D0%BC" to "Мультфильмы"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        return try {
            val separator = if (request.data.contains("?")) "&" else "?"
            val response = app.get("$mainUrl${request.data}${separator}page=$page").text
            val payload = tryParseJson<CatalogEnvelope>(response) ?: return null
            val items = payload.results.map { it.toSearchResponse(this) }
            newHomePageResponse(request, items, payload.hasNext)
        } catch (error: Exception) {
            logError(error)
            null
        }
    }

    private suspend fun searchViaCatalogFallback(query: String): List<SearchResponse> {
        val normalizedTokens = normalizeSearch(query)
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (normalizedTokens.isEmpty()) return emptyList()

        val rows = mutableListOf<MediaItem>()
        for (page in 1..6) {
            val response = app.get("$mainUrl/cloudstream/catalog?type=all&page=$page").text
            val payload = tryParseJson<CatalogEnvelope>(response) ?: break
            rows += payload.results
            if (!payload.hasNext) break
        }

        return rows
            .distinctBy { it.id }
            .filter { item ->
                val haystack = normalizeSearch(
                    listOfNotNull(item.title, item.originalTitle, item.year?.toString()).joinToString(" ")
                )
                normalizedTokens.all { token -> haystack.contains(token) }
            }
            .take(50)
            .map { it.toSearchResponse(this) }
    }

    private suspend fun performSearch(query: String, page: Int): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val encodedQuery = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val response = app.get("$mainUrl/cloudstream/search?q=$encodedQuery&page=$page").text
        val payload = tryParseJson<SearchEnvelope>(response)
        val results = payload?.results.orEmpty().map { it.toSearchResponse(this) }

        return if (results.isNotEmpty() || page > 1) results else searchViaCatalogFallback(query)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            performSearch(query, 1)
        } catch (error: Exception) {
            logError(error)
            try {
                searchViaCatalogFallback(query)
            } catch (fallbackError: Exception) {
                logError(fallbackError)
                emptyList()
            }
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        return try {
            val results = performSearch(query, page)
            results.toNewSearchResponseList(hasNext = results.size >= 50)
        } catch (error: Exception) {
            logError(error)
            emptyList<SearchResponse>().toNewSearchResponseList(hasNext = false)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url.substringAfterLast('/').substringBefore('?').filter(Char::isDigit)
        if (id.isBlank()) throw ErrorLoadingException("Geçersiz içerik kimliği")

        return try {
            val response = app.get("$mainUrl/cloudstream/item/$id").text
            val payload = tryParseJson<ItemEnvelope>(response)
                ?: throw ErrorLoadingException("İçerik cevabı okunamadı")
            payload.item.toLoadResponse(this, url)
        } catch (error: ErrorLoadingException) {
            throw error
        } catch (error: Exception) {
            logError(error)
            throw ErrorLoadingException("İçerik yüklenemedi")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split('|')
        val id = parts.getOrNull(0)?.filter(Char::isDigit).orEmpty()
        if (id.isBlank()) return false

        val season = parts.getOrNull(1)?.toIntOrNull()
        val episode = parts.getOrNull(2)?.toIntOrNull()
        val isEpisode = season != null && episode != null
        val endpoint = if (isEpisode) {
            "$mainUrl/cloudstream/links/$id?season=$season&episode=$episode"
        } else {
            "$mainUrl/cloudstream/links/$id"
        }

        var loadedCount = 0
        val emittedUrls = linkedSetOf<String>()

        fun emit(link: ExtractorLink) {
            if (link.url.isBlank() || !emittedUrls.add(link.url)) return
            callback(link)
            loadedCount += 1
        }

        try {
            val response = app.get(endpoint).text
            val payload = tryParseJson<LinksEnvelope>(response)

            payload?.subtitles.orEmpty()
                .filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
                .distinctBy { it.url }
                .forEach { subtitle ->
                    subtitleCallback(
                        SubtitleFile(
                            subtitle.name ?: "Субтитры",
                            subtitle.url
                        )
                    )
                }

            val uniqueLinks = payload?.links.orEmpty()
                .filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
                .distinctBy { it.url }

            for (link in uniqueLinks.filter { it.isDirectMedia() }) {
                try {
                    val refererValue = if (link.proxied) {
                        "$mainUrl/"
                    } else {
                        link.referer?.takeIf { it.isNotBlank() } ?: "$mainUrl/"
                    }
                    val requestHeaders = linkedMapOf<String, String>()

                    if (!link.proxied) {
                        requestHeaders.putAll(link.headers)
                        requestHeaders.putIfAbsent("Referer", refererValue)
                        requestHeaders.putIfAbsent("User-Agent", USER_AGENT)
                        originOf(refererValue)?.let { requestHeaders.putIfAbsent("Origin", it) }
                    }

                    val streamType = when {
                        link.proxied || link.kind.equals("hls", ignoreCase = true) ->
                            ExtractorLinkType.M3U8
                        link.kind.equals("dash", ignoreCase = true) ->
                            ExtractorLinkType.DASH
                        else -> link.extractorType() ?: ExtractorLinkType.VIDEO
                    }
                    val streamName = link.name ?: link.provider ?: link.source ?: name
                    val qualityValue = if (
                        link.quality.equals("Авто", ignoreCase = true) ||
                        link.quality.equals("Auto", ignoreCase = true)
                    ) {
                        Qualities.Unknown.value
                    } else {
                        getQualityFromName(link.quality ?: streamName)
                    }

                    emit(
                        newExtractorLink(
                            source = link.provider ?: link.source ?: name,
                            name = streamName,
                            url = link.url,
                            type = streamType
                        ) {
                            referer = refererValue
                            quality = qualityValue
                            headers = requestHeaders
                        }
                    )
                } catch (error: Exception) {
                    logError(error)
                }
            }

            // Alloha, Veoveo and other iframe providers are passed to CloudStream's
            // registered extractors. A source appears only when an extractor
            // actually returns a playable stream.
            for (link in uniqueLinks.filterNot { it.isDirectMedia() }.take(10)) {
                try {
                    val refererValue = link.referer?.takeIf { it.isNotBlank() } ?: "$mainUrl/"
                    loadExtractor(
                        url = link.url,
                        referer = refererValue,
                        subtitleCallback = subtitleCallback
                    ) { extracted -> emit(extracted) }
                } catch (error: Exception) {
                    logError(error)
                }
            }
        } catch (error: Exception) {
            logError(error)
        }

        // Movies always keep the stable server-side automatic resolver as a
        // last fallback. Other playable providers remain selectable above it.
        if (!isEpisode) {
            try {
                emit(
                    newExtractorLink(
                        source = name,
                        name = "$name • Авто",
                        url = "$mainUrl/cloudstream/play/$id/master.m3u8",
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = "$mainUrl/"
                        quality = Qualities.Unknown.value
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Accept" to "application/vnd.apple.mpegurl, application/x-mpegURL, */*"
                        )
                    }
                )
            } catch (error: Exception) {
                logError(error)
            }
        }

        return loadedCount > 0
    }

    private data class SearchEnvelope(
        val results: List<MediaItem> = emptyList()
    )

    private data class CatalogEnvelope(
        val results: List<MediaItem> = emptyList(),
        val hasNext: Boolean = false
    )

    private data class ItemEnvelope(
        val item: MediaItem
    )

    private data class EpisodeEnvelope(
        val episodes: List<EpisodeItem> = emptyList()
    )

    private data class EpisodeItem(
        val season: Int,
        val episode: Int,
        val title: String? = null,
        val duration: Int? = null,
        val providers: List<String> = emptyList()
    )

    private data class LinksEnvelope(
        val links: List<PlayerLink> = emptyList(),
        val subtitles: List<SubtitleItem> = emptyList()
    )

    private data class SubtitleItem(
        val name: String? = null,
        val url: String
    )

    private data class PlayerLink(
        val source: String? = null,
        val provider: String? = null,
        val name: String? = null,
        val quality: String? = null,
        val kind: String? = null,
        val direct: Boolean = false,
        val referer: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val proxied: Boolean = false,
        val url: String
    ) {
        fun isDirectMedia(): Boolean {
            val lowerUrl = url.lowercase()
            return direct ||
                kind.equals("hls", ignoreCase = true) ||
                kind.equals("dash", ignoreCase = true) ||
                kind.equals("video", ignoreCase = true) ||
                lowerUrl.contains(".m3u8") ||
                lowerUrl.contains(".mpd") ||
                lowerUrl.contains(".mp4") ||
                lowerUrl.contains(".webm")
        }

        fun extractorType(): ExtractorLinkType? = when {
            kind.equals("hls", ignoreCase = true) || url.contains(".m3u8", ignoreCase = true) ->
                ExtractorLinkType.M3U8
            kind.equals("dash", ignoreCase = true) || url.contains(".mpd", ignoreCase = true) ->
                ExtractorLinkType.DASH
            kind.equals("video", ignoreCase = true) -> ExtractorLinkType.VIDEO
            else -> null
        }
    }

    private suspend fun fetchEpisodes(id: String): List<EpisodeItem> {
        return try {
            val response = app.get("$mainUrl/cloudstream/episodes/$id").text
            tryParseJson<EpisodeEnvelope>(response)?.episodes.orEmpty()
        } catch (error: Exception) {
            logError(error)
            emptyList()
        }
    }

    private data class MediaItem(
        val id: String,
        val title: String,
        val originalTitle: String? = null,
        val year: Int? = null,
        val type: String = "movie",
        val poster: String? = null,
        val description: String? = null,
        val rating: Double? = null,
        val duration: Int? = null,
        val genres: List<String> = emptyList(),
        val countries: List<String> = emptyList(),
        val pageUrl: String? = null
    ) {
        private fun tvType(): TvType =
            if (type.equals("series", ignoreCase = true)) TvType.TvSeries else TvType.Movie

        fun toSearchResponse(provider: HilemTVProvider): SearchResponse {
            val url = "${provider.mainUrl}/cloudstream/item/$id"
            return if (tvType() == TvType.TvSeries) {
                provider.newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    posterUrl = poster
                    year = this@MediaItem.year
                }
            } else {
                provider.newMovieSearchResponse(title, url, TvType.Movie) {
                    posterUrl = poster
                    year = this@MediaItem.year
                }
            }
        }

        suspend fun toLoadResponse(provider: HilemTVProvider, requestUrl: String): LoadResponse {
            return if (tvType() == TvType.TvSeries) {
                val remoteEpisodes = provider.fetchEpisodes(id)
                if (remoteEpisodes.isEmpty()) {
                    throw ErrorLoadingException("Список серий не найден")
                }
                val episodes = remoteEpisodes.map { item ->
                    provider.newEpisode("$id|${item.season}|${item.episode}") {
                        name = item.title ?: "Серия ${item.episode}"
                        season = item.season
                        episode = item.episode
                        posterUrl = poster
                        runTime = item.duration?.takeIf { it > 0 }?.div(60)
                    }
                }

                provider.newTvSeriesLoadResponse(title, requestUrl, TvType.TvSeries, episodes) {
                    posterUrl = poster
                    plot = description
                    year = this@MediaItem.year
                    tags = genres
                    duration = this@MediaItem.duration
                }
            } else {
                provider.newMovieLoadResponse(title, requestUrl, TvType.Movie, id) {
                    posterUrl = poster
                    plot = description
                    year = this@MediaItem.year
                    tags = genres
                    duration = this@MediaItem.duration
                }
            }
        }
    }

    companion object {
        private fun originOf(value: String): String? = try {
            val uri = java.net.URI(value)
            if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) null
            else "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
        } catch (_: Exception) {
            null
        }

        private fun normalizeSearch(value: String): String = value
            .lowercase()
            .replace('ё', 'е')
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
    }
}
