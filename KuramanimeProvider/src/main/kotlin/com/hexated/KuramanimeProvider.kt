package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.requestCreator
import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class KuramanimeProvider : MainAPI() {
    override var mainUrl = "https://kuramanime.pro"
    override var name = "Kuramanime"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    private var params:  AuthParams? = null
    private var headers: Map<String,String> = mapOf()
    private var cookies: Map<String,String> = mapOf()
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String, s: Int): TvType {
            return if (t.contains("OVA", true) || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie", true) && s == 1) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Selesai Tayang" -> ShowStatus.Completed
                "Sedang Tayang" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/anime/ongoing?order_by=updated&page=" to "Sedang Tayang",
        "$mainUrl/anime/finished?order_by=updated&page=" to "Selesai Tayang",
        "$mainUrl/properties/season/summer-2022?order_by=most_viewed&page=" to "Dilihat Terbanyak Musim Ini",
        "$mainUrl/anime/movie?order_by=updated&page=" to "Film Layar Lebar",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document

        val home = document.select("div.col-lg-4.col-md-6.col-sm-6").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/episode")) {
            Regex("(.*)/episode/.+").find(uri)?.groupValues?.get(1).toString() + "/"
        } else {
            uri
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = getProperAnimeLink(fixUrl(this.selectFirst("a")!!.attr("href")))
        val title = this.selectFirst("h5 a")?.text() ?: return null
        val posterUrl = fixUrl(this.select("div.product__item__pic.set-bg").attr("data-setbg"))
        val episode = this.select("div.ep span").text().let {
            Regex("Ep\\s(\\d+)\\s/").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(episode)
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/anime?search=$query&order_by=latest"
        val document = app.get(link).document

        return document.select("div#animeList div.product__item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(".anime__details__title > h3")!!.text().trim()
        val poster = document.selectFirst(".anime__details__pic")?.attr("data-setbg")
        val tags = document.select("div.anime__details__widget > div > div:nth-child(2) > ul > li:nth-child(1)")
            .text().trim().replace("Genre: ", "").split(", ")

        val year = Regex("\\D").replace(
            document.select("div.anime__details__widget > div > div:nth-child(1) > ul > li:nth-child(5)")
                .text().trim().replace("Musim: ", ""), ""
        ).toIntOrNull()
        val status = getStatus(
            document.select("div.anime__details__widget > div > div:nth-child(1) > ul > li:nth-child(3)")
                .text().trim().replace("Status: ", "")
        )
        val description = document.select(".anime__details__text > p").text().trim()

        val episodes = mutableListOf<Episode>()

        for (i in 1..6) {
            val doc = app.get("$url?page=$i").document
            val eps = Jsoup.parse(doc.select("#episodeLists").attr("data-content")).select("a.btn.btn-sm.btn-danger")
                .mapNotNull {
                    val name = it.text().trim()
                    val episode = Regex("(\\d+[.,]?\\d*)").find(name)?.groupValues?.getOrNull(0)
                        ?.toIntOrNull()
                    val link = it.attr("href")
                    Episode(link, episode = episode)
                }
            if(eps.isEmpty()) break else episodes.addAll(eps)
        }

        val type = getType(document.selectFirst("div.col-lg-6.col-md-6 ul li:contains(Tipe:) a")?.text()?.lowercase() ?: "tv", episodes.size)
        val recommendations = document.select("div#randomList > a").mapNotNull {
            val epHref = it.attr("href")
            val epTitle = it.select("h5.sidebar-title-h5.px-2.py-2").text()
            val epPoster = it.select(".product__sidebar__view__item.set-bg").attr("data-setbg")
            newAnimeSearchResponse(epTitle, epHref, TvType.Anime) {
                this.posterUrl = epPoster
                addDubStatus(dubExist = false, subExist = true)
            }
        }

        val tracker = APIHolder.getTracker(listOf(title),TrackerType.getTypes(type),year,true)

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
            this.recommendations = recommendations
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }

    }

    private suspend fun invokeLocalSource(
        url: String,
        server: String,
        ref: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(
            url,
            referer = ref,
            headers = headers,
            cookies = cookies
        ).document
        document.select("video#player > source").map {
            val link = fixUrl(it.attr("src"))
            val quality = it.attr("size").toIntOrNull()
            callback.invoke(
                ExtractorLink(
                    fixTitle(server),
                    fixTitle(server),
                    link,
                    referer = "",
                    quality = quality ?: Qualities.Unknown.value,
                    headers = mapOf(
                        "Accept" to "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5",
                        "Range" to "bytes=0-",
                        "Sec-Fetch-Dest" to "video",
                        "Sec-Fetch-Mode" to "no-cors",
                    ),
                )
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val req = app.get(data)
        val res = req.document

        argamap(
            {
                val token = res.select("meta[name=csrf-token]").attr("content")
                val auth = getAuth(data)
                headers = mapOf(
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "Authorization" to "${auth.authHeader}",
                    "X-Fuck-ID" to "${auth.fidHeader}",
                    "X-Requested-With" to "XMLHttpRequest",
                    "X-CSRF-TOKEN" to token
                ).filter { !it.value.isNullOrEmpty() }
                cookies = req.cookies
                res.select("select#changeServer option").apmap { source ->
                    val server = source.attr("value")
                    val query = auth.serverUrl?.queryParameterNames?.map { it } ?: return@apmap
                    val link = "$data?${query[0]}=${getMisc(auth.authUrl)}&${query[1]}=$server"
                    if (server.contains(Regex("(?i)kuramadrive|archive"))) {
                        invokeLocalSource(link, server, data, callback)
                    } else {
                        app.get(
                            link,
                            referer = data,
                            headers = headers,
                            cookies = cookies
                        ).document.select("div.iframe-container iframe").attr("src").let { videoUrl ->
                            loadExtractor(fixUrl(videoUrl), "$mainUrl/", subtitleCallback, callback)
                        }
                    }
                }
            },
            {
                res.select("div#animeDownloadLink a").apmap {
                    loadExtractor(it.attr("href"), "$mainUrl/", subtitleCallback, callback)
                }
            }
        )

        return true
    }

    private suspend fun fetchAuth(url: String) : AuthParams {
        val regex = Regex("""$mainUrl/\S+""")
        val found = WebViewResolver(
            Regex("""$url(?!\?page=)\?"""),
            additionalUrls = listOf(regex)
        ).resolveUsingWebView(
            requestCreator(
                "GET", url
            )
        )
        val addition = found.second.find { !it.headers["Authorization"].isNullOrBlank() || !it.headers["X-Fuck-ID"].isNullOrBlank() }
        return AuthParams(found.first?.url, addition?.url.toString(), addition?.headers?.get("Authorization"), addition?.headers?.get("X-Fuck-ID"))
    }

    private suspend fun getAuth(url: String) = params ?: fetchAuth(url).also { params = it }

    private suspend fun getMisc(url: String?): String {
        val misc = app.get(
            "$url",
            headers = headers + mapOf("X-Request-ID" to getRequestId()),
            cookies = cookies
        )
        cookies = misc.cookies
        return misc.parsed()
    }

    private fun getRequestId(length: Int = 8): String {
        val allowedChars = ('a'..'z') + ('0'..'9')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }

    data class AuthParams (
        val serverUrl: HttpUrl?,
        val authUrl: String?,
        val authHeader: String?,
        val fidHeader: String?,
    )

}