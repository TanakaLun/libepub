package org.libepub.core.container

/**
 * Href/path algebra for OPF- and document-relative references.
 * Never touches the filesystem or network; results are zip-root-relative paths.
 */
object ResourcePath {

    /** Directory part of a zip-root path ("OEBPS/content.opf" -> "OEBPS"), "" at root. */
    fun parent(path: String): String = path.substringBeforeLast('/', "")

    /** Splits "a/b.xhtml?q=1#frag" into ("a/b.xhtml", "frag"?); query is dropped. */
    fun splitFragment(href: String): Pair<String, String?> {
        val hash = href.indexOf('#')
        val fragment = if (hash >= 0) href.substring(hash + 1) else null
        var path = if (hash >= 0) href.substring(0, hash) else href
        val query = path.indexOf('?')
        if (query >= 0) path = path.substring(0, query)
        return path to fragment
    }

    /** Resolves [href] against directory [baseDir] (zip-root paths), clamped at the root. */
    fun resolve(baseDir: String, href: String): String {
        val (pathOnly, _) = splitFragment(href)
        val slashed = pathOnly.replace('\\', '/')
        val joined = when {
            slashed.startsWith("/") -> slashed.removePrefix("/")
            baseDir.isEmpty() -> slashed
            else -> "$baseDir/$slashed"
        }
        val decoded = percentDecode(joined)
        return normalize(decoded)
    }

    /** Decodes %XX sequences as UTF-8; malformed sequences are left untouched. */
    fun percentDecode(input: String): String {
        if (!input.contains('%')) return input
        val out = java.io.ByteArrayOutputStream(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '%') {
                val hi = hex(input.getOrNull(i + 1))
                val lo = hex(input.getOrNull(i + 2))
                if (hi != null && lo != null) {
                    out.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            // write char as utf-8 bytes
            val cb = c.code
            when {
                cb < 0x80 -> out.write(cb)
                cb < 0x800 -> {
                    out.write(0xC0 or (cb shr 6))
                    out.write(0x80 or (cb and 0x3F))
                }
                cb >= 0xD800 && cb <= 0xDBFF && i + 1 < input.length -> {
                    val low = input[i + 1].code
                    val cp = 0x10000 + ((cb - 0xD800) shl 10) + (low - 0xDC00)
                    out.write(0xF0 or (cp shr 18))
                    out.write(0x80 or ((cp shr 12) and 0x3F))
                    out.write(0x80 or ((cp shr 6) and 0x3F))
                    out.write(0x80 or (cp and 0x3F))
                    i += 2
                    continue
                }
                cb < 0x10000 -> {
                    out.write(0xE0 or (cb shr 12))
                    out.write(0x80 or ((cb shr 6) and 0x3F))
                    out.write(0x80 or (cb and 0x3F))
                }
            }
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun hex(c: Char?): Int? {
        val ch = c ?: return null
        return when (ch) {
            in '0'..'9' -> ch - '0'
            in 'a'..'f' -> ch - 'a' + 10
            in 'A'..'F' -> ch - 'A' + 10
            else -> null
        }
    }

    private fun normalize(path: String): String {
        val out = ArrayList<String>(8)
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(seg)
            }
        }
        return out.joinToString("/")
    }
}
