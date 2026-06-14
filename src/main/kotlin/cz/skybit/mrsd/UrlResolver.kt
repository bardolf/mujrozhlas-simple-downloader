package cz.skybit.mrsd

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Resolves a mujrozhlas.cz page URL to a concrete API entity, deterministically
 * (no fuzzy title matching) — the page itself tells us what it is about.
 *
 * Every page carries analytics fields that name its primary entity:
 *   "contentSerialName":"<serial-uuid>: title"   → on a serial landing page AND on
 *                                                   any episode page of a serial
 *   "contentId":"<episode-uuid>"                  → a standalone episode page
 * A serial signal wins (download the whole serial). As a fallback we also match
 * the embedded `data-entry='{"uuid":..,"type":..,"url":"/path"}'` attributes by URL.
 */
class UrlResolver(
    private val api: Api,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    sealed class Resolution {
        /** A standalone episode (not part of a serial). */
        data class Single(val episode: Episode, val show: Show?) : Resolution()

        /** A serial with all its currently-published episodes. */
        data class Series(val serial: Serial, val episodes: List<Episode>) : Resolution()
    }

    data class Entry(val uuid: String, val type: String, val url: String)

    fun resolve(url: String): Resolution {
        val html = fetchHtml(url)

        // 1) Serial page (landing page or any episode page of a serial) → whole serial.
        contentSerialUuid(html)?.let { return resolveSerial(it) }

        // 2) A specific standalone item (episode page).
        contentId(html)?.let { return resolveEpisode(it) }

        // 3) Fallback: match an embedded data-entry by its URL.
        matchEntry(extractEntries(html), url)?.let { return resolveEntry(it) }

        // 4) A show / overview page with no single downloadable item.
        throw showPageError()
    }

    private fun resolveEntry(entry: Entry): Resolution = when (entry.type) {
        "serial" -> resolveSerial(entry.uuid)
        "episode" -> resolveEpisode(entry.uuid)
        else -> throw showPageError()
    }

    /** An episode that belongs to a serial means "download the whole serial". */
    private fun resolveEpisode(episodeUuid: String): Resolution {
        val episode = api.getEpisode(episodeUuid)
        return episode.serialUuid?.let { resolveSerial(it) }
            ?: Resolution.Single(episode, episode.showUuid?.let { runCatching { api.getShow(it) }.getOrNull() })
    }

    private fun resolveSerial(serialUuid: String): Resolution {
        val serial = api.getSerial(serialUuid)
        val episodes = api.getSerialEpisodes(serialUuid)
        return Resolution.Series(serial.copy(episodes = episodes), episodes)
    }

    private fun showPageError() = ResolveException(
        "Tohle je stránka pořadu bez konkrétního dílu nebo seriálu. " +
            "Vlož prosím URL jednoho dílu nebo seriálu."
    )

    private fun fetchHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            // Full browser-like headers — a bare User-Agent gets an anti-bot interstitial.
            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "cs,en;q=0.5")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ResolveException("Stránku se nepodařilo načíst: HTTP ${response.code}")
            }
            return response.body.string()
        }
    }

    companion object {
        private val DATA_ENTRY = Regex("""data-entry='([^']*)'""")
        private val TRAILING_ID = Regex("""-\d+$""")
        private val UUID = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")
        private val json = Json { ignoreUnknownKeys = true }

        /** Read a page analytics field like `"contentId":"…"` (raw or HTML-encoded). */
        fun contentField(html: String, name: String): String? {
            Regex(""""$name"\s*:\s*"([^"]*)"""").find(html)?.let { return it.groupValues[1] }
            Regex("""&quot;$name&quot;:&quot;([^&]*)&quot;""").find(html)?.let { return it.groupValues[1] }
            return null
        }

        /** Serial uuid the page declares as its subject (serial landing or serial-episode page). */
        fun contentSerialUuid(html: String): String? =
            contentField(html, "contentSerialName")?.let { UUID.find(it)?.value }

        /** The page's primary specific item id — an episode uuid on a standalone episode page. */
        fun contentId(html: String): String? =
            contentField(html, "contentId")?.let { UUID.find(it)?.value }

        /** Parse all data-entry blobs out of a page's HTML. */
        fun extractEntries(html: String): List<Entry> =
            DATA_ENTRY.findAll(html).mapNotNull { match ->
                runCatching {
                    val obj = json.decodeFromString<JsonObject>(htmlDecode(match.groupValues[1]))
                    val uuid = obj["uuid"]?.jsonPrimitive?.contentOrNull
                    val type = obj["type"]?.jsonPrimitive?.contentOrNull
                    val entryUrl = obj["url"]?.jsonPrimitive?.contentOrNull
                    if (uuid != null && type != null && entryUrl != null) Entry(uuid, type, entryUrl) else null
                }.getOrNull()
            }.distinctBy { it.uuid + it.url }.toList()

        /** Pick the entry whose url matches [url]: exact path first, then ignoring a trailing node id. */
        fun matchEntry(entries: List<Entry>, url: String): Entry? {
            val wanted = normalizePath(url)
            entries.firstOrNull { normalizePath(it.url) == wanted }?.let { return it }
            val loose = stripNodeId(wanted)
            return entries.firstOrNull { stripNodeId(normalizePath(it.url)) == loose }
        }

        /** Reduce any URL (absolute or path) to its decoded path without a trailing slash. */
        fun normalizePath(url: String): String {
            val path = try {
                val u = URI(url)
                if (u.path != null && u.path.isNotEmpty()) u.path else url
            } catch (_: Exception) {
                url
            }
            return path.removeSuffix("/")
        }

        fun stripNodeId(path: String): String = path.replace(TRAILING_ID, "")

        fun htmlDecode(s: String): String = s
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }
}

class ResolveException(message: String) : RuntimeException(message)
