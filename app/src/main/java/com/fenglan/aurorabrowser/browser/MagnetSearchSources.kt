package com.fenglan.aurorabrowser.browser

import java.net.URI
import java.net.URLEncoder

data class MagnetSearchResult(
    val sourceId: String,
    val sourceLabel: String,
    val title: String,
    val pageUrl: String?,
    val magnetUrl: String?,
    val torrentUrl: String?,
    val sizeText: String? = null,
) {
    val copyUrl: String
        get() = magnetUrl ?: torrentUrl ?: pageUrl.orEmpty()

    val openUrl: String
        get() = pageUrl ?: torrentUrl ?: magnetUrl.orEmpty()
}

data class MagnetSearchOutcome(
    val source: MagnetSearchSource,
    val results: List<MagnetSearchResult>,
    val errorMessage: String? = null,
) {
    val requiresVerification: Boolean
        get() = errorMessage == ERROR_REQUIRES_VERIFICATION

    companion object {
        const val ERROR_REQUIRES_VERIFICATION = "需要手动验证源站"
    }
}

data class MagnetSearchSource(
    val id: String,
    val label: String,
    val template: String,
    val isCustom: Boolean = false,
    val fallbackTemplates: List<String> = emptyList(),
) {
    fun buildSearchUrl(query: String): String {
        return buildSearchUrl(template, query)
    }

    fun buildSearchUrls(query: String): List<String> {
        return (listOf(template) + fallbackTemplates)
            .map { buildSearchUrl(it, query) }
            .distinct()
    }

    private fun buildSearchUrl(sourceTemplate: String, query: String): String {
        val normalized = query.trim()
        val encoded = URLEncoder.encode(normalized, "UTF-8")
        val pathEncoded = encoded.replace("+", "%20")
        val slug = normalized
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return sourceTemplate
            .replace(MagnetSearchSources.QUERY_PLACEHOLDER, encoded)
            .replace(MagnetSearchSources.LEGACY_QUERY_PLACEHOLDER, encoded)
            .replace(MagnetSearchSources.QUERY_PLUS_PLACEHOLDER, encoded)
            .replace(MagnetSearchSources.QUERY_PATH_PLACEHOLDER, pathEncoded)
            .replace(MagnetSearchSources.QUERY_SLUG_PLACEHOLDER, slug.ifBlank { encoded })
    }
}

object MagnetSearchSources {
    const val QUERY_PLACEHOLDER = "{query}"
    const val LEGACY_QUERY_PLACEHOLDER = "%s"
    const val QUERY_PLUS_PLACEHOLDER = "{query+}"
    const val QUERY_PATH_PLACEHOLDER = "{query%20}"
    const val QUERY_SLUG_PLACEHOLDER = "{query-}"
    const val EXAMPLE_TEMPLATE = "https://example.com/search?q={query}"

    val builtIn: List<MagnetSearchSource> = listOf(
        MagnetSearchSource(
            "0magnet",
            "0Magnet",
            "https://0magnet.co/search?q={query}",
            fallbackTemplates = listOf(
                "https://0magnet.co/search/{query%20}",
                "https://0magnet.com/search?q={query}",
                "https://0magnet.com/search/{query%20}",
                "https://0mag.net/search?q={query}",
                "https://0mag.net/search/{query%20}",
            ),
        ),
        MagnetSearchSource(
            "eztv",
            "EZTV",
            "https://eztvx.to/search/{query%20}",
            fallbackTemplates = listOf(
                "https://eztv.re/search/{query%20}",
                "https://eztv1.xyz/search/{query%20}",
            ),
        ),
        MagnetSearchSource("btdirectory", "BTDirectory", "https://btdig.com/search?q={query}"),
        MagnetSearchSource(
            "btsow",
            "BTSOW",
            "https://btsow.rest/search/{query%20}",
            fallbackTemplates = listOf(
                "https://btsow.one/search/{query%20}",
                "https://btsow.click/search/{query%20}",
            ),
        ),
        MagnetSearchSource(
            "1337x",
            "1337x",
            "https://1337x.to/search/{query%20}/1/",
            fallbackTemplates = listOf(
                "https://1337x.st/search/{query%20}/1/",
                "https://x1337x.ws/search/{query%20}/1/",
                "https://x1337x.se/search/{query%20}/1/",
            ),
        ),
        MagnetSearchSource(
            "tpb",
            "TPB",
            "https://thepiratebay.org/search.php?q={query}",
            fallbackTemplates = listOf(
                "https://piratebay.live/search/{query}/1/99/0",
                "https://tpb.party/search/{query}/1/99/0",
                "https://thepiratebay10.org/search/{query}/1/99/0",
            ),
        ),
        MagnetSearchSource("bitsearch", "BitSearch", "https://bitsearch.to/search?q={query}"),
        MagnetSearchSource(
            "torlock",
            "Torlock",
            "https://www.torlock.com/all/torrents/{query-}.html",
            fallbackTemplates = listOf(
                "https://www.torlock.unblockit.dev/all/torrents/{query-}.html",
            ),
        ),
        MagnetSearchSource(
            "torrentfunk",
            "TorrentFunk",
            "https://www.torrentfunk.com/all/torrents/{query-}.html",
        ),
        MagnetSearchSource(
            "solidtorrents",
            "SolidTorrents",
            "https://solidtorrents.to/search?q={query}",
        ),
        MagnetSearchSource(
            "idope",
            "iDope",
            "https://idope.se/torrent-list/{query%20}/",
        ),
        MagnetSearchSource(
            "btdb",
            "BTDB",
            "https://btdb.eu/search/{query%20}/0/?sort=popular",
        ),
        MagnetSearchSource("nyaa", "Nyaa", "https://nyaa.si/?f=0&c=0_0&q={query}"),
        MagnetSearchSource(
            "torrentgalaxy",
            "TorrentGalaxy",
            "https://torrentgalaxy.to/torrents.php?search={query}",
            fallbackTemplates = listOf(
                "https://torrentgalaxy.mx/torrents.php?search={query}",
            ),
        ),
        MagnetSearchSource(
            "magnetdl",
            "MagnetDL",
            "https://www.magnetdl.com/search/?q={query}",
            fallbackTemplates = listOf(
                "https://www.magnetdl.unblockit.dev/search/?q={query}",
            ),
        ),
        MagnetSearchSource(
            "bt4g",
            "BT4G",
            "https://bt4gprx.com/search?q={query}&orderby=seeders&page=rss",
            fallbackTemplates = listOf(
                "https://bt4gprx.com/search?q={query}&orderby=seeders",
                "https://bt4g.org/search?q={query}&orderby=seeders",
            ),
        ),
        MagnetSearchSource(
            "limetorrents",
            "LimeTorrents",
            "https://www.limetorrents.lol/search/all/{query%20}/",
            fallbackTemplates = listOf(
                "https://www.limetorrents.info/search/all/{query%20}/",
                "https://www.limetorrents.pro/search/all/{query%20}/",
                "https://limetorrents.unblockit.dev/search/all/{query%20}/",
            ),
        ),
        MagnetSearchSource(
            "glodls",
            "GloDLS",
            "https://glodls.to/search_results.php?search={query}&cat=0",
        ),
        MagnetSearchSource(
            "torrentdownloads",
            "TorrentDownloads",
            "https://www.torrentdownloads.pro/search/?search={query}",
            fallbackTemplates = listOf(
                "https://www.torrentdownloads.me/search/?search={query}",
            ),
        ),
        MagnetSearchSource(
            "yourbittorrent",
            "YourBittorrent",
            "https://yourbittorrent.com/?q={query}",
        ),
        MagnetSearchSource(
            "extto",
            "EXT Torrents",
            "https://ext.to/search/?q={query}",
        ),
        MagnetSearchSource(
            "kickasstorrents",
            "KickassTorrents",
            "https://kickasstorrents.to/usearch/{query%20}/",
        ),
        MagnetSearchSource(
            "torrentproject",
            "TorrentProject",
            "https://torrentproject2.com/?t={query}",
            fallbackTemplates = listOf(
                "https://torrentproject.cc/?t={query}",
            ),
        ),
        MagnetSearchSource(
            "skytorrents",
            "SkyTorrents",
            "https://www.skytorrents.lol/?query={query}",
            fallbackTemplates = listOf(
                "https://skytorrents.to/?query={query}",
            ),
        ),
        MagnetSearchSource(
            "academictorrents",
            "AcademicTorrents",
            "https://academictorrents.com/browse.php?search={query}",
        ),
        MagnetSearchSource(
            "ettv",
            "ETTV",
            "https://www.ettvcentral.com/torrents-search.html?search={query}",
            fallbackTemplates = listOf(
                "https://ettvcentral.com/torrents-search.html?search={query}",
            ),
        ),
        MagnetSearchSource(
            "glotorrents",
            "GloTorrents",
            "https://glotorrents.com/search?q={query}",
            fallbackTemplates = listOf(
                "https://glotorrents.net/search?q={query}",
            ),
        ),
        MagnetSearchSource(
            "toros",
            "Toros",
            "https://www.toros.co/search?q={query}",
        ),
        MagnetSearchSource(
            "monova",
            "Monova",
            "https://monova.org/search?q={query}",
        ),
        MagnetSearchSource(
            "zooqle",
            "Zooqle",
            "https://zooqle.com/search?q={query}",
            fallbackTemplates = listOf(
                "https://zooqle.unblockit.dev/search?q={query}",
            ),
        ),
        MagnetSearchSource(
            "rarbg",
            "RARBG",
            "https://rargb.to/search/{query%20}/",
            fallbackTemplates = listOf(
                "https://rarbgmirror.org/search/{query%20}/",
            ),
        ),
        MagnetSearchSource(
            "torrentseeker",
            "TorrentSeeker",
            "https://torrentseeker.com/search?q={query}",
        ),
        MagnetSearchSource(
            "snowfl",
            "Snowfl",
            "https://snowfl.com/search?q={query}",
        ),
    )

    fun createCustom(id: String, label: String, template: String): MagnetSearchSource? {
        val cleanLabel = label.trim()
        val cleanTemplate = template.trim()
        if (cleanLabel.isBlank() || !isValidTemplate(cleanTemplate)) {
            return null
        }
        return MagnetSearchSource(
            id = id.trim().ifBlank { customId(cleanLabel, cleanTemplate) },
            label = cleanLabel,
            template = cleanTemplate,
            isCustom = true,
        )
    }

    fun isValidTemplate(template: String): Boolean {
        val value = template.trim()
        if (!hasPlaceholder(value)) {
            return false
        }
        val sampleUrl = value
            .replace(QUERY_PLACEHOLDER, "ubuntu")
            .replace(LEGACY_QUERY_PLACEHOLDER, "ubuntu")
            .replace(QUERY_PLUS_PLACEHOLDER, "ubuntu")
            .replace(QUERY_PATH_PLACEHOLDER, "ubuntu")
            .replace(QUERY_SLUG_PLACEHOLDER, "ubuntu")
        val uri = runCatching { URI(sampleUrl) }.getOrNull() ?: return false
        return uri.scheme == "http" || uri.scheme == "https"
    }

    private fun hasPlaceholder(value: String): Boolean {
        return listOf(
            QUERY_PLACEHOLDER,
            LEGACY_QUERY_PLACEHOLDER,
            QUERY_PLUS_PLACEHOLDER,
            QUERY_PATH_PLACEHOLDER,
            QUERY_SLUG_PLACEHOLDER,
        ).any(value::contains)
    }

    fun customId(label: String, template: String): String {
        val seed = "${label.trim()}|${template.trim()}|${System.currentTimeMillis()}"
        return "custom_${seed.hashCode().toUInt().toString(16)}"
    }
}
