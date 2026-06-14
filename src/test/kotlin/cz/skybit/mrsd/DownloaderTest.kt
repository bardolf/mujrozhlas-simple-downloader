package cz.skybit.mrsd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloaderTest {

    @Test
    fun `sanitizeFilename removes unsafe characters`() {
        assertEquals("Umberto Eco_ Foucaultovo kyvadlo", Downloader.sanitizeFilename("Umberto Eco: Foucaultovo kyvadlo"))
        assertEquals("a_b_c_d", Downloader.sanitizeFilename("a/b\\c|d"))
        assertEquals("Normal Title 123", Downloader.sanitizeFilename("Normal Title 123"))
    }

    @Test
    fun `digitCount has a minimum of two`() {
        assertEquals(2, Downloader.digitCount(1))
        assertEquals(2, Downloader.digitCount(9))
        assertEquals(2, Downloader.digitCount(50))
        assertEquals(3, Downloader.digitCount(100))
    }

    @Test
    fun `isPlayable respects the playableTill window`() {
        assertTrue(Downloader.isPlayable(AudioLink("u", "hls", 1, 1, null)))
        assertTrue(Downloader.isPlayable(AudioLink("u", "hls", 1, 1, "2999-01-01T00:00:00+02:00")))
        assertFalse(Downloader.isPlayable(AudioLink("u", "hls", 1, 1, "2000-01-01T00:00:00+02:00")))
    }
}
