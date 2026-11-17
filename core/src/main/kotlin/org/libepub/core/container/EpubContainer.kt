package org.libepub.core.container

import org.libepub.core.EpubFormatException
import org.libepub.core.util.WarningSink
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Random-access view over an EPUB zip. Lookup tolerates "./" prefixes,
 * percent-encoding mismatches between hrefs and stored entry names, and
 * backslash separators produced by broken tools.
 *
 * A shared [WarningSink] collects container-level degradations.
 */
class EpubContainer private constructor(
    private val zip: ZipFile,
    private val index: Map<String, ZipEntry>,
    val opfPath: String,
    val warnings: WarningSink,
) : Closeable {

    companion object {
        private const val CONTAINER_XML = "META-INF/container.xml"

        fun open(file: File): EpubContainer {
            if (!file.isFile) throw EpubFormatException("not a file: $file")
            val zip = try {
                ZipFile(file)
            } catch (e: Exception) {
                throw EpubFormatException("cannot open zip: ${file.name}", e)
            }
            val warnings = WarningSink()
            try {
                val index = HashMap<String, ZipEntry>(256)
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name.removePrefix("./")
                    index.putIfAbsent(name, entry)
                }
                val containerBytes = index[CONTAINER_XML]?.let { entry ->
                    zip.getInputStream(entry).use { it.readBytes() }
                } ?: throw EpubFormatException("missing $CONTAINER_XML — not an EPUB")
                val opfPath = parseRootfile(XmlText.decode(containerBytes))
                if (opfPath !in index) {
                    throw EpubFormatException("container.xml points to missing package document: $opfPath")
                }
                probeMimetype(index, warnings)
                return EpubContainer(zip, index, opfPath, warnings)
            } catch (e: EpubFormatException) {
                zip.close()
                throw e
            } catch (e: Exception) {
                zip.close()
                throw EpubFormatException("unreadable container: ${e.message}", e)
            }
        }

        private fun parseRootfile(containerXml: String): String {
            val parser = KXmlParser()
            try {
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                parser.setInput(containerXml.reader())
                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG && parser.localName == "rootfile") {
                        val full = parser.getAttributeValue(null, "full-path")
                        if (!full.isNullOrBlank()) return ResourcePath.resolve("", full)
                    }
                    eventType = parser.next()
                }
            } catch (e: Exception) {
                throw EpubFormatException("malformed META-INF/container.xml", e)
            }
            throw EpubFormatException("container.xml has no rootfile full-path")
        }

        private fun probeMimetype(index: Map<String, ZipEntry>, warnings: WarningSink) {
            val entry = index["mimetype"] ?: return
            // entry not available here; cheap presence-only check is enough at this layer
            if (entry.method == ZipEntry.STORED && entry.size > 64) {
                warnings.warnOnce("mimetype.size", "mimetype entry looks malformed")
            }
        }
    }

    fun exists(path: String): Boolean = entryFor(path) != null

    fun listPaths(): List<String> = index.keys.sorted()

    fun size(path: String): Long = entryFor(path)?.size ?: -1L

    fun readBytes(path: String): ByteArray {
        val entry = entryFor(path) ?: throw EpubFormatException("missing entry: $path")
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    fun openStream(path: String): InputStream {
        val entry = entryFor(path) ?: throw EpubFormatException("missing entry: $path")
        return zip.getInputStream(entry)
    }

    /** UTF-8 (or prolog-declared) text with BOM stripped. */
    fun readText(path: String): String = XmlText.decode(readBytes(path))

    override fun close() = zip.close()

    private fun entryFor(path: String): ZipEntry? {
        val (clean, _) = ResourcePath.splitFragment(path)
        val normalized = clean.replace('\\', '/').removePrefix("./").removePrefix("/")
        index[normalized]?.let { return it }
        val decoded = ResourcePath.percentDecode(normalized)
        if (decoded != normalized) index[decoded]?.let { return it }
        return null
    }
}
