package io.snailrun.domain.gpx

import io.snailrun.domain.fixtures.Traces
import io.snailrun.domain.model.TrackPoint
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GpxWriterTest {

    private val defaultLocale = Locale.getDefault()
    private val writer = GpxWriter()

    @Before fun setUp() = Unit

    @After fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    private fun track(points: List<TrackPoint>) =
        GpxTrack(name = "Morning run", startedAtEpochMs = Traces.START_MS, points = points)

    @Test
    fun `a French locale does not produce decimal commas`() {
        // The phone runs in French, where the default formatter writes "48,8566000"
        // and produces a GPX no parser will accept.
        Locale.setDefault(Locale.FRANCE)
        val xml = writer.writeToString(track(Traces.straightTrack(seconds = 5)))
        assertFalse("found a decimal comma in a coordinate", Regex("lat=\"[-0-9]+,").containsMatchIn(xml))
        assertTrue(xml.contains("lat=\"48.8566000\""))
        assertTrue(xml.contains("<ele>35.0</ele>"))
    }

    @Test
    fun `the output is well-formed XML`() {
        val xml = writer.writeToString(track(Traces.straightTrack(seconds = 10)))
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(xml.byteInputStream())
        assertEquals("gpx", document.documentElement.tagName)
        assertEquals("1.1", document.documentElement.getAttribute("version"))
        assertEquals(11, document.getElementsByTagName("trkpt").length)
    }

    @Test
    fun `ele comes before time, as the schema requires`() {
        val xml = writer.writeToString(track(Traces.straightTrack(seconds = 2)))
        val point = xml.lines().first { it.contains("<trkpt") }
        assertTrue(point.indexOf("<ele>") < point.indexOf("<time>"))
    }

    @Test
    fun `each pause segment becomes its own trkseg`() {
        val first = Traces.straightTrack(seconds = 5, segment = 0)
        val second = Traces.straightTrack(
            seconds = 5,
            startMs = Traces.START_MS + 600_000,
            segment = 1,
        )
        val xml = writer.writeToString(track(first + second))
        assertEquals(2, Regex("<trkseg>").findAll(xml).count())
    }

    @Test
    fun `timestamps are ISO-8601 in UTC`() {
        val xml = writer.writeToString(track(Traces.straightTrack(seconds = 1)))
        assertTrue(xml.contains("<time>2023-11-14T22:13:20Z</time>"))
    }

    @Test
    fun `a track name containing markup is escaped`() {
        val xml = writer.writeToString(
            GpxTrack("Rain & <wind>", Traces.START_MS, Traces.straightTrack(seconds = 1)),
        )
        assertTrue(xml.contains("<name>Rain &amp; &lt;wind&gt;</name>"))
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
    }

    @Test
    fun `a point with no altitude simply omits ele`() {
        val points = Traces.straightTrack(seconds = 2).map { it.copy(elevationM = null) }
        val xml = writer.writeToString(track(points))
        assertFalse(xml.contains("<ele>"))
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
    }
}
