package cz.skybit.mrsd

import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Thin client for the public api.mujrozhlas.cz JSON:API.
 * We only ever fetch by UUID — URLs are resolved to UUIDs by [UrlResolver].
 */
class Api(
    private val baseUrl: String = "https://api.mujrozhlas.cz",
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    fun getShow(uuid: String): Show =
        parseShow(json.decodeFromString<JsonApiSingleResponse>(fetch("$baseUrl/shows/$uuid")).data)

    fun getSerial(uuid: String): Serial =
        parseSerial(json.decodeFromString<JsonApiSingleResponse>(fetch("$baseUrl/serials/$uuid")).data)

    fun getSerialEpisodes(serialUuid: String): List<Episode> =
        fetchAllPages("$baseUrl/serials/$serialUuid/episodes?page[limit]=500")
            .map { parseEpisode(it) }
            .sortedBy { it.orderNumber }

    fun getEpisode(uuid: String): Episode =
        parseEpisode(json.decodeFromString<JsonApiSingleResponse>(fetch("$baseUrl/episodes/$uuid")).data)

    // --- HTTP ---

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("API request failed: ${response.code} for $url")
            }
            return response.body.string()
        }
    }

    private fun fetchAllPages(firstUrl: String): List<JsonApiResource> {
        val all = mutableListOf<JsonApiResource>()
        var url: String? = firstUrl
        while (url != null) {
            val page = json.decodeFromString<JsonApiListResponse>(fetch(url))
            all.addAll(page.data)
            url = page.links?.next
        }
        return all
    }

    // --- Parsing ---

    private fun assetUrl(attrs: JsonObject): String? =
        attrs["asset"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull

    private fun parseShow(resource: JsonApiResource): Show {
        val attrs = json.decodeFromJsonElement<JsonObject>(resource.attributes)
        return Show(
            uuid = resource.id,
            title = attrs["title"]?.jsonPrimitive?.content ?: "",
            imageUrl = assetUrl(attrs),
        )
    }

    private fun parseSerial(resource: JsonApiResource): Serial {
        val attrs = json.decodeFromJsonElement<JsonObject>(resource.attributes)
        val showUuid = resource.relationships?.let { rels ->
            json.decodeFromJsonElement<JsonObject>(rels)["show"]?.jsonObject
                ?.get("data")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
        }
        return Serial(
            uuid = resource.id,
            showUuid = showUuid,
            title = attrs["title"]?.jsonPrimitive?.content ?: "",
            totalParts = attrs["totalParts"]?.jsonPrimitive?.intOrNull ?: 0,
            imageUrl = assetUrl(attrs),
            lastEpisodeSince = attrs["lastEpisodeSince"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun parseEpisode(resource: JsonApiResource): Episode {
        val attrs = json.decodeFromJsonElement<JsonObject>(resource.attributes)
        val audioLinks = (attrs["audioLinks"]?.jsonArray ?: JsonArray(emptyList())).map { el ->
            val link = el.jsonObject
            AudioLink(
                url = link["url"]?.jsonPrimitive?.content ?: "",
                variant = link["variant"]?.jsonPrimitive?.content ?: "",
                duration = link["duration"]?.jsonPrimitive?.intOrNull ?: 0,
                bitrate = link["bitrate"]?.jsonPrimitive?.intOrNull ?: 0,
                playableTill = link["playableTill"]?.jsonPrimitive?.contentOrNull,
            )
        }

        val rels = resource.relationships?.let { json.decodeFromJsonElement<JsonObject>(it) }
        val serialUuid = rels?.get("serial")?.jsonObject
            ?.get("data")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
        val showUuid = rels?.get("show")?.jsonObject
            ?.get("data")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull

        return Episode(
            uuid = resource.id,
            title = attrs["title"]?.jsonPrimitive?.content ?: "",
            part = attrs["part"]?.jsonPrimitive?.intOrNull,
            seriesEpisodeNumber = attrs["series_episode_number"]?.jsonPrimitive?.intOrNull,
            audioLinks = audioLinks,
            serialUuid = serialUuid,
            showUuid = showUuid,
            imageUrl = assetUrl(attrs),
        )
    }
}
