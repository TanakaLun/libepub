package org.libepub.render

import org.junit.Assume
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Test corpora for the render module.
 *
 * The real sample novel lives in `<repo>/assets`; the synthetic book covers the
 * duokan constructs the novel does not ship (an image gallery with more than one
 * image) so gallery/zoom behaviour is tested deterministically.
 */
object TestBooks {

    fun sampleFile(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val assets = File(dir, "assets")
            if (assets.isDirectory) {
                assets.listFiles()?.firstOrNull { it.extension.equals("epub", ignoreCase = true) }
                    ?.let { return it }
            }
            dir = dir.parentFile
        }
        // Copyrighted corpus, deliberately not committed: skip rather than fail
        // on a fresh clone.
        Assume.assumeTrue(
            "no sample epub in assets/; drop one there or pass -Dlibepub.sample=/path/to/book.epub",
            false,
        )
        error("unreachable: the assumption above always fails here")
    }

    fun syntheticFile(): File {
        val file = File.createTempFile("libepub-synthetic-", ".epub")
        file.deleteOnExit()
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()

            zip.putText(
                "META-INF/container.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent(),
            )
            zip.putText("OEBPS/content.opf", CONTENT_OPF)
            zip.putText("OEBPS/nav.xhtml", NAV)
            zip.putText("OEBPS/Text/chapter.xhtml", chapter())
            zip.putBytes("OEBPS/Images/g1.png", PNG)
            zip.putBytes("OEBPS/Images/g2.png", PNG)
            zip.putBytes("OEBPS/Images/single.png", PNG)
            zip.putBytes("OEBPS/Images/note.png", PNG)
        }
        return file
    }

    private fun chapter(): String {
        val paragraphs = (1..24).joinToString("\n") { index ->
            "<p>第${index}段测试文字，用来把这一章撑到多页，验证翻页与滚动两种阅读模式。</p>"
        }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
            <head><title>测试章节</title></head>
            <body>
            <p id="first">开头段落<a class="duokan-footnote" epub:type="noteref" href="#fn1" id="A_1"><sup><img alt="note" src="../Images/note.png"/></sup></a>结尾。</p>
            $paragraphs
            <div class="duokan-image-gallery">
              <div class="duokan-image-gallery-cell"><img alt="g1" src="../Images/g1.png"/></div>
              <div class="duokan-image-gallery-cell"><img alt="g2" src="../Images/g2.png"/></div>
            </div>
            <div class="duokan-image-single"><img alt="single" src="../Images/single.png"/></div>
            <aside epub:type="footnote" id="fn1"><p>这是脚注内容，来自合成测试书。</p></aside>
            </body>
            </html>
        """.trimIndent()
    }

    private fun ZipOutputStream.putText(name: String, text: String) =
        putBytes(name, text.toByteArray())

    private fun ZipOutputStream.putBytes(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private val CONTENT_OPF = """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:identifier id="id">libepub-synthetic</dc:identifier>
            <dc:title>合成测试书</dc:title>
            <dc:language>zh</dc:language>
          </metadata>
          <manifest>
            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="ch1" href="Text/chapter.xhtml" media-type="application/xhtml+xml"/>
            <item id="g1" href="Images/g1.png" media-type="image/png"/>
            <item id="g2" href="Images/g2.png" media-type="image/png"/>
            <item id="s1" href="Images/single.png" media-type="image/png"/>
            <item id="note" href="Images/note.png" media-type="image/png"/>
          </manifest>
          <spine>
            <itemref idref="ch1"/>
          </spine>
        </package>
    """.trimIndent()

    private val NAV = """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
        <head><title>目录</title></head>
        <body>
        <nav epub:type="toc"><ol><li><a href="Text/chapter.xhtml">测试章节</a></li></ol></nav>
        </body>
        </html>
    """.trimIndent()

    /** 1x1 transparent RGBA PNG (base64 so the CRC is guaranteed valid). */
    private val PNG: ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
    )
}
