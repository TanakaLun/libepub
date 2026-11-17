package org.libepub.core.container

/** Classification of a `META-INF/encryption.xml` cipher reference. */
enum class EncryptionType { FONT_IDPF, FONT_ADOBE, UNKNOWN }

data class EncryptedEntry(
    /** Zip-root path of the encrypted resource. */
    val path: String,
    val type: EncryptionType,
)

/**
 * Result of reading META-INF/encryption.xml.
 * Font obfuscation (IDPF/Adobe) is legitimate and supported; anything else on
 * content documents is treated as DRM and surfaces as [org.libepub.core.DrmProtectedException].
 */
data class EncryptionInfo(val entries: List<EncryptedEntry>) {
    val isDrm: Boolean get() = entries.any { it.type == EncryptionType.UNKNOWN }

    val obfuscatedFontPaths: Set<String>
        get() = entries.filter { it.type != EncryptionType.UNKNOWN }.mapTo(LinkedHashSet()) { it.path }

    companion object {
        val NONE = EncryptionInfo(emptyList())

        fun parse(container: EpubContainer): EncryptionInfo {
            if (!container.exists(ENCRYPTION_PATH)) return NONE
            val xml = container.readText(ENCRYPTION_PATH)
            return try {
                parseXml(xml)
            } catch (e: Exception) {
                container.warnings.warn("unreadable encryption.xml (${e.message}); treating as DRM")
                EncryptionInfo(listOf(EncryptedEntry("", EncryptionType.UNKNOWN)))
            }
        }

        internal fun parseXml(xml: String): EncryptionInfo {
            val parser = org.kxml2.io.KXmlParser()
            parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(xml.reader())
            val entries = ArrayList<EncryptedEntry>()
            var algorithm: String? = null
            var uri: String? = null
            var eventType = parser.eventType
            while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.localName) {
                        "EncryptionMethod" -> algorithm = parser.getAttributeValue(null, "Algorithm")
                        "CipherReference" -> uri = parser.getAttributeValue(null, "URI")
                        "EncryptedData" -> {
                            algorithm = null
                            uri = null
                        }
                    }
                    else -> Unit
                }
                if (eventType == org.xmlpull.v1.XmlPullParser.END_TAG && parser.localName == "EncryptedData") {
                    val a = algorithm
                    val u = uri
                    if (a != null && u != null) {
                        val type = when (a) {
                            IDPF_ALGORITHM -> EncryptionType.FONT_IDPF
                            ADOBE_ALGORITHM -> EncryptionType.FONT_ADOBE
                            else -> EncryptionType.UNKNOWN
                        }
                        entries.add(EncryptedEntry(ResourcePath.resolve("", u), type))
                    } else {
                        entries.add(EncryptedEntry(u ?: "", EncryptionType.UNKNOWN))
                    }
                    algorithm = null
                    uri = null
                }
                eventType = parser.next()
            }
            return EncryptionInfo(entries)
        }

        private const val ENCRYPTION_PATH = "META-INF/encryption.xml"
        const val IDPF_ALGORITHM = "http://www.idpf.org/2007/embedding"
        const val ADOBE_ALGORITHM = "http://ns.adobe.com/pdf/enc#RC"
    }
}
