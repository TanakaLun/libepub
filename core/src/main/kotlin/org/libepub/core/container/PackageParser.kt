package org.libepub.core.container

import org.libepub.core.EpubFormatException
import org.libepub.core.model.EpubMetadata
import org.libepub.core.model.EpubPackage
import org.libepub.core.model.GuideEntry
import org.libepub.core.model.ManifestItem
import org.libepub.core.model.SpineItem
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

/**
 * OPF 2.0 / 3.0 package document parser.
 * Namespace-tolerant, tolerant of duplicated meta names (last wins except title/creator lists).
 */
class PackageParser(private val container: EpubContainer) {

    fun parse(): EpubPackage {
        val opfPath = container.opfPath
        val opfDir = ResourcePath.parent(opfPath)
        val text = container.readText(opfPath)

        var version = ""
        var spineTocId: String? = null

        val titles = ArrayList<String>()
        val creators = ArrayList<String>()
        val subjects = ArrayList<String>()
        val identifiers = ArrayList<String>()
        var language: String? = null
        var date: String? = null
        var description: String? = null
        var publisher: String? = null
        var rights: String? = null
        val meta = LinkedHashMap<String, String>()

        val manifestById = LinkedHashMap<String, ManifestItem>()
        val manifestByHref = LinkedHashMap<String, ManifestItem>()
        val spine = ArrayList<SpineItem>()
        val guide = ArrayList<GuideEntry>()

        val parser = KXmlParser()
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(text.reader())

            var section = Section.NONE
            var dcField: String? = null
            var dcText: StringBuilder? = null
            var metaProperty: String? = null
            var metaText: StringBuilder? = null

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> when (parser.localName) {
                        "package" -> version = parser.getAttributeValue(null, "version") ?: ""
                        "metadata" -> section = Section.METADATA
                        "manifest" -> section = Section.MANIFEST
                        "spine" -> {
                            section = Section.SPINE
                            spineTocId = parser.getAttributeValue(null, "toc")
                        }
                        "guide" -> section = Section.GUIDE
                        "item" -> if (section == Section.MANIFEST) {
                            val id = parser.getAttributeValue(null, "id")
                            val href = parser.getAttributeValue(null, "href")
                            if (id != null && href != null) {
                                val item = ManifestItem(
                                    id = id,
                                    href = ResourcePath.resolve(opfDir, href),
                                    mediaType = parser.getAttributeValue(null, "media-type") ?: "",
                                    properties = parser.getAttributeValue(null, "properties")
                                        ?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.toSet()
                                        ?: emptySet(),
                                )
                                manifestById.putIfAbsent(id, item)
                                manifestByHref.putIfAbsent(item.href, item)
                            }
                        }
                        "itemref" -> if (section == Section.SPINE) {
                            val idref = parser.getAttributeValue(null, "idref")
                            if (idref != null) {
                                spine.add(
                                    SpineItem(
                                        idref = idref,
                                        href = null, // resolved after the document is read
                                        linear = parser.getAttributeValue(null, "linear") != "no",
                                    ),
                                )
                            }
                        }
                        "reference" -> if (section == Section.GUIDE) {
                            val href = parser.getAttributeValue(null, "href")
                            if (href != null) {
                                guide.add(
                                    GuideEntry(
                                        type = parser.getAttributeValue(null, "type") ?: "",
                                        title = parser.getAttributeValue(null, "title"),
                                        href = ResourcePath.resolve(opfDir, href),
                                    ),
                                )
                            }
                        }
                        "meta" -> when {
                            section != Section.METADATA -> Unit
                            parser.getAttributeValue(null, "property") != null -> {
                                metaProperty = parser.getAttributeValue(null, "property")
                                metaText = StringBuilder()
                            }
                            else -> {
                                val name = parser.getAttributeValue(null, "name")
                                val content = parser.getAttributeValue(null, "content")
                                if (name != null && content != null) meta[name] = content
                            }
                        }
                        else -> if (section == Section.METADATA && parser.namespace == DC_NS) {
                            dcField = parser.localName
                            dcText = StringBuilder()
                        }
                    }

                    XmlPullParser.TEXT -> {
                        dcText?.append(parser.text)
                        metaText?.append(parser.text)
                    }

                    XmlPullParser.END_TAG -> {
                        if (dcField != null && parser.localName == dcField && parser.namespace == DC_NS) {
                            val value = dcText?.toString()?.trim().orEmpty()
                            if (value.isNotEmpty()) {
                                when (dcField) {
                                    "title" -> titles.add(value)
                                    "creator" -> creators.add(value)
                                    "subject" -> subjects.add(value)
                                    "identifier" -> identifiers.add(value)
                                    "language" -> if (language == null) language = value
                                    "date" -> if (date == null) date = value
                                    "description" -> if (description == null) description = value
                                    "publisher" -> if (publisher == null) publisher = value
                                    "rights" -> if (rights == null) rights = value
                                }
                            }
                            dcField = null
                            dcText = null
                        }
                        if (metaText != null && parser.localName == "meta") {
                            val prop = metaProperty
                            val value = metaText.toString().trim()
                            if (prop != null && value.isNotEmpty()) meta.putIfAbsent(prop, value)
                            metaProperty = null
                            metaText = null
                        }
                        when (parser.localName) {
                            "metadata", "manifest", "spine", "guide" -> section = Section.NONE
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: EpubFormatException) {
            throw e
        } catch (e: Exception) {
            throw EpubFormatException("malformed package document: $opfPath (${e.message})", e)
        }

        // Resolve spine idrefs against the manifest (dangling idrefs tolerated, href stays null).
        val resolvedSpine = spine.map { it.copy(href = manifestById[it.idref]?.href) }
        val dangling = resolvedSpine.count { it.href == null }
        if (dangling > 0) {
            container.warnings.warn("spine has $dangling dangling itemref(s)")
        }

        val navHref = manifestById.values.firstOrNull { "nav" in it.properties }?.href
        val tocHref = navHref
            ?: spineTocId?.let { manifestById[it]?.href }
            ?: manifestById.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }?.href

        val coverImageHref = resolveCoverImage(manifestById.values, manifestByHref, meta, opfDir)
        val guideCover = guide.firstOrNull { it.type == "cover" }
        val coverPageHref = guideCover?.href ?: resolvedSpine.firstOrNull()?.href

        if (version.isEmpty()) {
            container.warnings.warn("package@version missing; assuming 2.0")
            version = "2.0"
        }

        return EpubPackage(
            version = version,
            opfPath = opfPath,
            metadata = EpubMetadata(
                title = titles.firstOrNull(),
                titles = titles,
                creators = creators,
                language = language,
                date = date,
                subjects = subjects,
                identifiers = identifiers,
                description = description,
                publisher = publisher,
                rights = rights,
                meta = meta,
            ),
            manifestById = manifestById,
            manifestByHref = manifestByHref,
            spine = resolvedSpine,
            spineTocId = spineTocId,
            guide = guide,
            tocHref = tocHref,
            coverImageHref = coverImageHref,
            coverPageHref = coverPageHref,
        )
    }

    private fun resolveCoverImage(
        items: Collection<ManifestItem>,
        byHref: Map<String, ManifestItem>,
        meta: Map<String, String>,
        opfDir: String,
    ): String? {
        // EPUB3: manifest item property
        items.firstOrNull { "cover-image" in it.properties }?.let { return it.href }
        // EPUB2: <meta name="cover" content="manifest-id"> (quirky books pass an href instead)
        meta["cover"]?.let { value ->
            byHref[value]?.let { return it.href }
            items.firstOrNull { it.id == value }?.let { return it.href }
            val resolved = ResourcePath.resolve(opfDir, value)
            byHref[resolved]?.let { return it.href }
            // not in manifest: fall through to heuristic (never fabricate a path)
        }
        // Last-resort heuristic: a cover.* image in the manifest
        return items.firstOrNull { it.mediaType.startsWith("image/") && it.href.substringAfterLast('/').startsWith("cover") }?.href
    }

    private enum class Section { NONE, METADATA, MANIFEST, SPINE, GUIDE }

    private companion object {
        const val DC_NS = "http://purl.org/dc/elements/1.1/"
    }
}
