package io.snailrun.domain.gpx

import io.snailrun.domain.model.TrackPoint
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

data class GpxTrack(
    val name: String,
    val startedAtEpochMs: Long,
    val points: List<TrackPoint>,
)

/**
 * GPX 1.1 writer.
 *
 * Hand-rolled on purpose. `android.util.Xml.newSerializer()` and `XmlPullParserFactory`
 * both live in android.jar, which under `src/test` is a stub that throws — using either
 * would put the app's only interchange format beyond reach of a unit test.
 */
class GpxWriter(private val creator: String = "snail run") {

    fun write(out: Appendable, track: GpxTrack) {
        out.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        out.append("<gpx version=\"1.1\" creator=\"").append(escape(creator)).append("\"\n")
        out.append("     xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
        out.append("     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
        out.append("     xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1")
        out.append(" http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")

        out.append("  <metadata><time>").append(timestamp(track.startedAtEpochMs))
        out.append("</time></metadata>\n")

        out.append("  <trk>\n")
        out.append("    <name>").append(escape(track.name)).append("</name>\n")
        out.append("    <type>running</type>\n")

        // One <trkseg> per pause segment. A single segment spanning a ten-minute break
        // makes every importer draw a straight line across town.
        for (segment in track.points.groupBy { it.segment }.toSortedMap().values) {
            out.append("    <trkseg>\n")
            for (point in segment) {
                out.append("      <trkpt lat=\"").append(coordinate(point.lat))
                out.append("\" lon=\"").append(coordinate(point.lon)).append("\">")
                // Child order is schema-significant in GPX 1.1: ele before time.
                point.elevationM?.let {
                    out.append("<ele>").append(elevation(it)).append("</ele>")
                }
                out.append("<time>").append(timestamp(point.timestampMs)).append("</time>")
                out.append("</trkpt>\n")
            }
            out.append("    </trkseg>\n")
        }

        out.append("  </trk>\n")
        out.append("</gpx>\n")
    }

    fun writeToString(track: GpxTrack): String = StringBuilder().also { write(it, track) }.toString()

    // Locale.ROOT on every number. The phone's locale is French, where the default
    // formatter emits "48,8566000" and produces a GPX no parser will accept.
    private fun coordinate(value: Double) = String.format(Locale.ROOT, "%.7f", value)

    private fun elevation(value: Double) = String.format(Locale.ROOT, "%.1f", value)

    private fun timestamp(epochMs: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs))

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
