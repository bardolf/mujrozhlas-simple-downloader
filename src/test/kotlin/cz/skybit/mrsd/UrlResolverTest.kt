package cz.skybit.mrsd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UrlResolverTest {

    // Mirrors how mujrozhlas.cz embeds entities in the page HTML.
    private val html = """
        <article data-entry='{"id":"587315","uuid":"97e4e533-1cf0-3bbb-8026-c3e22ff82ad0","type":"show","title":"Toulky českou minulostí","url":"\/toulky-ceskou-minulosti"}'>
        <li data-entry='{"id":"735378","uuid":"0b349bf0-531f-328f-be9d-429d53c50886","type":"episode","title":"637. schůzka","url":"\/toulky-ceskou-minulosti\/637-schuzka-vladar-na-dva-roky"}'></li>
        <li data-entry='{"id":"123","uuid":"9d81a091-3d21-37e9-9060-6eb535c6b157","type":"serial","title":"Kryštof Kolumbus","url":"\/cetba-na-pokracovani\/jakob-wassermann-krystof-kolumbus-don-quijote-oceanu"}'></li>
    """.trimIndent()

    @Test
    fun `extractEntries reads uuid, type and url from data-entry blobs`() {
        val entries = UrlResolver.extractEntries(html)
        assertEquals(3, entries.size)
        val show = entries.first { it.type == "show" }
        assertEquals("97e4e533-1cf0-3bbb-8026-c3e22ff82ad0", show.uuid)
        assertEquals("/toulky-ceskou-minulosti", show.url)
    }

    @Test
    fun `matchEntry finds the episode by exact path`() {
        val entries = UrlResolver.extractEntries(html)
        val match = UrlResolver.matchEntry(
            entries,
            "https://www.mujrozhlas.cz/toulky-ceskou-minulosti/637-schuzka-vladar-na-dva-roky",
        )
        assertEquals("episode", match?.type)
        assertEquals("0b349bf0-531f-328f-be9d-429d53c50886", match?.uuid)
    }

    @Test
    fun `matchEntry tolerates a trailing node id in the browser URL`() {
        val entries = UrlResolver.extractEntries(html)
        // User-pasted serial URL carries a trailing "-3453094" the embedded url lacks.
        val match = UrlResolver.matchEntry(
            entries,
            "https://www.mujrozhlas.cz/cetba-na-pokracovani/jakob-wassermann-krystof-kolumbus-don-quijote-oceanu-3453094",
        )
        assertEquals("serial", match?.type)
        assertEquals("9d81a091-3d21-37e9-9060-6eb535c6b157", match?.uuid)
    }

    @Test
    fun `matchEntry returns null when nothing matches`() {
        val entries = UrlResolver.extractEntries(html)
        assertNull(UrlResolver.matchEntry(entries, "https://www.mujrozhlas.cz/neco-jineho"))
    }

    @Test
    fun `normalizePath strips host and trailing slash`() {
        assertEquals("/a/b", UrlResolver.normalizePath("https://www.mujrozhlas.cz/a/b/"))
        assertEquals("/a/b", UrlResolver.normalizePath("/a/b"))
    }

    @Test
    fun `stripNodeId removes only a trailing numeric id`() {
        assertEquals("/cetba/some-title", UrlResolver.stripNodeId("/cetba/some-title-3453094"))
        assertEquals("/cetba/some-title", UrlResolver.stripNodeId("/cetba/some-title"))
    }

    @Test
    fun `htmlDecode decodes entities and unescaping happens via json`() {
        assertEquals("\"a\" & 'b'", UrlResolver.htmlDecode("&quot;a&quot; &amp; &#039;b&#039;"))
    }
}
