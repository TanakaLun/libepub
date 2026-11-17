package org.libepub.core.model

/** Dublin Core subset plus raw meta name/content pairs (EPUB2 and EPUB3 forms). */
data class EpubMetadata(
    val title: String?,
    val titles: List<String>,
    val creators: List<String>,
    val language: String?,
    val date: String?,
    val subjects: List<String>,
    val identifiers: List<String>,
    val description: String?,
    val publisher: String?,
    val rights: String?,
    val meta: Map<String, String>,
)

data class ManifestItem(
    val id: String,
    /** Zip-root-relative, percent-decoded, normalized. */
    val href: String,
    val mediaType: String,
    val properties: Set<String>,
)

data class SpineItem(
    val idref: String,
    /** Resolved via manifest; null when the idref is dangling (tolerated, reported by integrity checks). */
    val href: String?,
    val linear: Boolean,
)

data class GuideEntry(
    val type: String,
    val title: String?,
    /** Resolved zip-root path. */
    val href: String,
)

/** Parsed `<package>` document; paths are zip-root-relative. */
data class EpubPackage(
    val version: String,
    val opfPath: String,
    val metadata: EpubMetadata,
    val manifestById: Map<String, ManifestItem>,
    val manifestByHref: Map<String, ManifestItem>,
    val spine: List<SpineItem>,
    val spineTocId: String?,
    val guide: List<GuideEntry>,
    /** EPUB3 `properties=nav` first, then EPUB2 spine@toc -> NCX. */
    val tocHref: String?,
    val coverImageHref: String?,
    val coverPageHref: String?,
)
