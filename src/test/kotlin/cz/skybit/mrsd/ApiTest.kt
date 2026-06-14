package cz.skybit.mrsd

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: Api

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Api(baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getSerial parses serial attributes`() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {
                  "data": {
                    "type": "serial",
                    "id": "86006a3c-b4ec-38f6-b754-565cc18cbe17",
                    "attributes": {
                      "title": "Umberto Eco: Foucaultovo kyvadlo",
                      "totalParts": 5,
                      "asset": { "url": "https://portal.rozhlas.cz/images/cover.jpg" }
                    },
                    "relationships": {
                      "show": { "data": { "type": "show", "id": "11112222-3333-4444-5555-666677778888" } }
                    }
                  }
                }
                """.trimIndent()
            )
        )

        val serial = api.getSerial("86006a3c-b4ec-38f6-b754-565cc18cbe17")
        assertEquals("Umberto Eco: Foucaultovo kyvadlo", serial.title)
        assertEquals(5, serial.totalParts)
        assertEquals("https://portal.rozhlas.cz/images/cover.jpg", serial.imageUrl)
        assertEquals("11112222-3333-4444-5555-666677778888", serial.showUuid)
    }

    @Test
    fun `getSerialEpisodes parses episodes with audio links`() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {
                  "data": [
                    {
                      "type": "episode",
                      "id": "ff1cf4d9-7606-300b-bd31-98b8631f59af",
                      "attributes": {
                        "title": "Foucaultovo kyvadlo I",
                        "part": 1,
                        "audioLinks": [
                          { "url": "https://croaod.cz/x/playlist.m3u8", "variant": "hls", "duration": 3205, "bitrate": 128, "playableTill": "2026-04-11T23:59:00+02:00" }
                        ]
                      },
                      "relationships": {
                        "serial": { "data": { "type": "serial", "id": "86006a3c-b4ec-38f6-b754-565cc18cbe17" } }
                      }
                    }
                  ],
                  "meta": { "count": 1 }
                }
                """.trimIndent()
            )
        )

        val episodes = api.getSerialEpisodes("86006a3c-b4ec-38f6-b754-565cc18cbe17")
        assertEquals(1, episodes.size)
        assertEquals(1, episodes[0].part)
        assertEquals("hls", episodes[0].audioLinks[0].variant)
        assertEquals(3205, episodes[0].audioLinks[0].duration)
        assertEquals("86006a3c-b4ec-38f6-b754-565cc18cbe17", episodes[0].serialUuid)
    }

    @Test
    fun `getEpisode reads show relationship and series_episode_number`() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {
                  "data": {
                    "type": "episode",
                    "id": "a2010787-e6d9-3b23-9ce6-3fb58bfe2568",
                    "attributes": {
                      "title": "571. schůzka",
                      "part": null,
                      "series_episode_number": 571,
                      "audioLinks": [ { "url": "https://croaod.cz/x/playlist.m3u8", "variant": "hls", "duration": 1615, "bitrate": 128 } ]
                    },
                    "relationships": {
                      "show": { "data": { "type": "show", "id": "97e4e533-1cf0-3bbb-8026-c3e22ff82ad0" } },
                      "serial": {}
                    }
                  }
                }
                """.trimIndent()
            )
        )

        val ep = api.getEpisode("a2010787-e6d9-3b23-9ce6-3fb58bfe2568")
        assertEquals(571, ep.seriesEpisodeNumber)
        assertEquals(571, ep.orderNumber)
        assertEquals("97e4e533-1cf0-3bbb-8026-c3e22ff82ad0", ep.showUuid)
        assertEquals(null, ep.serialUuid)
    }
}
