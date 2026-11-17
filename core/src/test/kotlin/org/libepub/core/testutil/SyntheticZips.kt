package org.libepub.core.testutil

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds tiny EPUB zips in temp dirs to lock behavior no sample book exercises. */
object SyntheticZips {

    const val MINIMAL_OPF = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bid">urn:test</dc:identifier>
    <dc:title>Synthetic &amp; Small</dc:title>
    <dc:creator>Tester</dc:creator>
    <dc:language>en</dc:language>
    <meta name="cover" content="missing-cover-id"/>
  </metadata>
  <manifest>
    <item id="c" href="Text/a.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="c"/></spine>
</package>
"""

    const val MINIMAL_CONTAINER = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

    /** [cipherPairs] are (algorithm, uri). [packageXml] replaces the default OPF. */
    fun write(
        cipherPairs: List<Pair<String, String>> = emptyList(),
        extra: Map<String, String> = emptyMap(),
        packageXml: String = MINIMAL_OPF,
    ): File {
        val file = File.createTempFile("synthetic", ".epub")
        file.deleteOnExit()
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun put(name: String, data: ByteArray, stored: Boolean = false) {
                val entry = ZipEntry(name)
                if (stored) entry.method = ZipEntry.STORED
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
            put("mimetype", "application/epub+zip".toByteArray(), stored = false)
            put("META-INF/container.xml", MINIMAL_CONTAINER.toByteArray())
            put("OEBPS/content.opf", packageXml.toByteArray())
            put("OEBPS/Text/a.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>x</p></body></html>".toByteArray())
            if (cipherPairs.isNotEmpty()) {
                val body = buildString {
                    append("""<?xml version="1.0" encoding="UTF-8"?>""")
                    append("""<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">""")
                    for ((alg, uri) in cipherPairs) {
                        append("""<enc:EncryptedData><enc:EncryptionMethod Algorithm="$alg"/>""")
                        append("""<enc:CipherData><enc:CipherReference URI="$uri"/></enc:CipherData></enc:EncryptedData>""")
                    }
                    append("</encryption>")
                }
                put("META-INF/encryption.xml", body.toByteArray())
            }
            for ((name, content) in extra) put(name, content.toByteArray())
        }
        return file
    }
}
