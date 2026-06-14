package cz.skybit.mrsd

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// --- JSON:API response wrappers (api.mujrozhlas.cz) ---

@Serializable
data class JsonApiListResponse(
    val data: List<JsonApiResource>,
    val meta: JsonApiMeta? = null,
    val links: JsonApiLinks? = null,
)

@Serializable
data class JsonApiSingleResponse(
    val data: JsonApiResource,
)

@Serializable
data class JsonApiLinks(
    val next: String? = null,
)

@Serializable
data class JsonApiMeta(
    val count: Int? = null,
)

@Serializable
data class JsonApiResource(
    val type: String,
    val id: String,
    val attributes: JsonElement,
    val relationships: JsonElement? = null,
)

// --- Domain models ---

data class Show(
    val uuid: String,
    val title: String,
    val imageUrl: String? = null,
)

data class Serial(
    val uuid: String,
    val showUuid: String? = null,
    val title: String,
    val totalParts: Int,
    val imageUrl: String? = null,
    val lastEpisodeSince: String? = null,
    val episodes: List<Episode> = emptyList(),
)

data class Episode(
    val uuid: String,
    val title: String,
    val part: Int? = null,
    val seriesEpisodeNumber: Int? = null,
    val audioLinks: List<AudioLink> = emptyList(),
    val serialUuid: String? = null,
    val showUuid: String? = null,
    val imageUrl: String? = null,
) {
    /** Effective ordering number: serial part or show episode number. */
    val orderNumber: Int get() = part ?: seriesEpisodeNumber ?: 0
}

data class AudioLink(
    val url: String,
    val variant: String,
    val duration: Int,
    val bitrate: Int,
    val playableTill: String? = null,
)

/** Pick the best audio link: prefer HLS (streaming), fall back to MP3 (direct download). */
fun List<AudioLink>.bestAudioLink(): AudioLink? =
    firstOrNull { it.variant == "hls" } ?: firstOrNull { it.variant == "mp3" }
