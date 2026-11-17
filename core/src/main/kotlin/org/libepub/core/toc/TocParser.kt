package org.libepub.core.toc

import org.libepub.core.EpubFormatException
import org.libepub.core.container.EpubContainer
import org.libepub.core.container.PackageParser
import org.libepub.core.container.ResourcePath
import org.libepub.core.model.EpubPackage
import org.libepub.core.util.WarningSink

/**
 * Reads the navigation of a book: EPUB3 `nav` document when the manifest marks
 * one (`properties=nav`), otherwise the EPUB2 NCX referenced by spine@toc.
 * Both forms surface as one [Toc].
 */
class TocParser(
    private val container: EpubContainer,
    private val pkg: EpubPackage,
) {
    constructor(container: EpubContainer) : this(container, PackageParser(container).parse())

    fun parse(): Toc {
        val tocHref = pkg.tocHref
            ?: return Toc(null, emptyList(), emptyList(), emptyList(), TocSource.NONE)
        val item = pkg.manifestByHref[tocHref]
        val baseDir = ResourcePath.parent(tocHref)
        val xml = runCatching { container.readText(tocHref) }.getOrElse {
            container.warnings.warn("unreadable toc document $tocHref (${it.message})")
            return Toc(null, emptyList(), emptyList(), emptyList(), TocSource.NONE)
        }
        val asNav = item != null && (
            "nav" in item.properties ||
                (item.mediaType == "application/xhtml+xml" && item.id != pkg.spineTocId)
            )
        return try {
            if (asNav) {
                val r = NavParser.parse(xml, baseDir)
                Toc(r.title, r.entries, r.landmarks, r.pageLabels, TocSource.NAV)
            } else {
                val r = NcxParser.parse(xml, baseDir)
                Toc(r.title, r.entries, emptyList(), r.pageLabels, TocSource.NCX)
            }
        } catch (e: EpubFormatException) {
            throw e
        } catch (e: Exception) {
            container.warnings.warnOnce("toc.parse", "malformed toc in $tocHref (${e.message})")
            Toc(null, emptyList(), emptyList(), emptyList(), TocSource.NONE)
        }
    }
}

internal object HrefResolver {
    fun splitResolve(baseDir: String, rawHref: String?): Pair<String, String?> {
        if (rawHref.isNullOrBlank()) return "" to null
        val (path, frag) = ResourcePath.splitFragment(rawHref)
        return ResourcePath.resolve(baseDir, path) to frag
    }
}
