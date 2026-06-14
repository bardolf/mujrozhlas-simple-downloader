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
 * Resolves a mujrozhlas.cz page URL to a concrete API entity, deterministically.
 *
 * Every mujrozhlas.cz page embeds its entities as HTML attributes:
 *   data-entry='{"id":"...","uuid":"...","type":"show|serial|episode","url":"/path",...}'
 * We fetch the page, find the entry whose `url` matches the requested path, and
 * read its `uuid` + `type`. No fuzzy title matching — the page tells us exactly.
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
        val entry = findEntry(url)
        return when (entry.type) {
            "serial" -> resolveSerial(entry.uuid)
            "episode" -> {
                val episode = api.getEpisode(entry.uuid)
                // An episode that belongs to a serial means "download the whole serial".
                val serialUuid = episode.serialUuid
                if (serialUuid != null) {
                    resolveSerial(serialUuid)
                } else {
                    val show = episode.showUuid?.let { runCatching { api.getShow(it) }.getOrNull() }
                    Resolution.Single(episode, show)
                }
            }
            "show" -> throw ResolveException(
                "Tohle je stránka pořadu, ne konkrétní díl ani seriál. " +
                    "Vlož prosím URL jednoho dílu nebo seriálu."
            )
            else -> throw ResolveException("Neznámý typ obsahu '${entry.type}' na stránce.")
        }
    }

    private fun resolveSerial(serialUuid: String): Resolution {
        val serial = api.getSerial(serialUuid)
        val episodes = api.getSerialEpisodes(serialUuid)
        return Resolution.Series(serial.copy(episodes = episodes), episodes)
    }

    /** Fetch the page and pick the data-entry whose url matches the requested path. */
    fun findEntry(url: String): Entry {
        val entries = extractEntries(fetchHtml(url))
        if (entries.isEmpty()) {
            throw ResolveException("Na stránce nebyl nalezen žádný přehratelný obsah ($url).")
        }
        return matchEntry(entries, url) ?: throw ResolveException(
            "URL se nepodařilo spárovat s obsahem na stránce. Zkontroluj, že odkazuje na konkrétní díl nebo seriál."
        )
    }

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
        private val json = Json { ignoreUnknownKeys = true }

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
