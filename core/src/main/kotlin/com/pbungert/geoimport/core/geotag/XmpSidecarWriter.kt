package com.pbungert.geoimport.core.geotag

import com.pbungert.geoimport.core.model.TrackPoint
import org.w3c.dom.Attr
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.math.abs

/**
 * Writes the position to an XMP sidecar. The universal fallback: it works for
 * any format, and desktop editors (Lightroom Classic, Capture One, darktable,
 * digiKam) honour it even where nothing can write into the container.
 *
 * The sidecar is named after the basename — `DSCF0001.RAF` becomes
 * `DSCF0001.xmp` — which is the Adobe convention Lightroom looks for. A RAW
 * and JPEG of the same shot therefore share one sidecar, which is harmless:
 * same shot, same capture time, same position, identical content.
 *
 * A sidecar that already exists is someone's work - ratings, keywords, a whole
 * develop history - so only its GPS properties are replaced. One that cannot
 * be read as XMP is left alone and the write fails, which the caller reports:
 * a missing position is recoverable, a lost edit history is not.
 */
object XmpSidecarWriter : GpsWriter {

    override val name = "sidecar"

    override fun supports(file: File) = true

    override fun write(file: File, point: TrackPoint): GpsWriteResult {
        val sidecar = File(file.parentFile, file.nameWithoutExtension + ".xmp")
        val content = if (sidecar.exists()) merge(sidecar, point) else buildXmp(point)
        // Through a temp file, so a failure part-way cannot truncate the very
        // sidecar the merge exists to protect.
        val temp = File(sidecar.parentFile, sidecar.name + ".tmp")
        try {
            temp.writeText(content)
            Files.move(temp.toPath(), sidecar.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
        return GpsWriteResult.Sidecar(sidecar)
    }

    private fun buildXmp(p: TrackPoint): String = buildString {
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n")
        append(" <rdf:RDF xmlns:rdf=\"$RDF_NS\">\n")
        append("  <rdf:Description rdf:about=\"\"\n")
        append("    xmlns:exif=\"$EXIF_NS\"\n")
        for ((name, value) in gpsProperties(p)) append("    exif:$name=\"$value\"\n")
        append("  />\n")
        append(" </rdf:RDF>\n")
        append("</x:xmpmeta>\n")
    }

    private fun gpsProperties(p: TrackPoint): List<Pair<String, String>> = buildList {
        add("GPSVersionID" to "2.3.0.0")
        add("GPSLatitude" to formatCoordinate(p.lat, 'N', 'S'))
        add("GPSLongitude" to formatCoordinate(p.lon, 'E', 'W'))
        p.ele?.let { ele ->
            // Through ExifGpsFormat like every other backend, so a sidecar and
            // an embedded tag for the same photo cannot round differently.
            add("GPSAltitude" to ExifGpsFormat.altitudeRational(ele))
            add("GPSAltitudeRef" to ExifGpsFormat.altitudeRef(ele))
        }
    }

    /**
     * [sidecar] with every exif:GPS* property removed - as an attribute or as
     * an element, from every rdf:Description - and the new ones added to the
     * first. Removing all of them first matters: a stale GPSAltitude left
     * beside a new position would describe somewhere else.
     */
    private fun merge(sidecar: File, point: TrackPoint): String {
        val text = sidecar.readText()
        // No DOCTYPE, no entity expansion - the same line TrackParser holds.
        if (text.contains("<!DOCTYPE", ignoreCase = true)) {
            throw IOException("${sidecar.name} declares a DOCTYPE; left untouched")
        }
        val doc = try {
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(InputSource(StringReader(text)))
        } catch (e: Exception) {
            throw IOException("${sidecar.name} is not readable XMP; left untouched", e)
        }

        val nodes = doc.getElementsByTagNameNS(RDF_NS, "Description")
        val descriptions = (0 until nodes.length).map { nodes.item(it) as Element }
        val target = descriptions.firstOrNull()
            ?: throw IOException("${sidecar.name} has no rdf:Description; left untouched")
        descriptions.forEach(::removeGps)

        target.setAttributeNS(XMLNS_NS, "xmlns:exif", EXIF_NS)
        for ((name, value) in gpsProperties(point)) {
            target.setAttributeNS(EXIF_NS, "exif:$name", value)
        }

        val out = StringWriter()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        }.transform(DOMSource(doc), StreamResult(out))
        return out.toString()
    }

    private fun removeGps(description: Element) {
        val attributes = description.attributes
        (0 until attributes.length)
            .map { attributes.item(it) }
            .filter { it.namespaceURI == EXIF_NS && it.localName.startsWith("GPS") }
            .forEach { description.removeAttributeNode(it as Attr) }

        val children = description.childNodes
        (0 until children.length)
            .map { children.item(it) }
            .filter { it is Element && it.namespaceURI == EXIF_NS && it.localName.startsWith("GPS") }
            .forEach { description.removeChild(it) }
    }

    /** XMP GPS format: degrees,decimal-minutes plus hemisphere, e.g. "48,7.634512N". */
    private fun formatCoordinate(value: Double, positive: Char, negative: Char): String {
        val hemisphere = if (value >= 0) positive else negative
        val abs = abs(value)
        val degrees = abs.toInt()
        val minutes = (abs - degrees) * 60
        return String.format(Locale.US, "%d,%.6f%c", degrees, minutes, hemisphere)
    }

    private const val RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private const val EXIF_NS = "http://ns.adobe.com/exif/1.0/"
    private const val XMLNS_NS = "http://www.w3.org/2000/xmlns/"
}
