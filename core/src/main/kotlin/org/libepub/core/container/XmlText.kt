package org.libepub.core.container

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Decodes XML/XHTML document bytes honoring BOM and the encoding declared in
 * the XML prolog (real-world books ship UTF-8, UTF-16 and Shift_JIS).
 */
object XmlText {
    fun decode(bytes: ByteArray): String {
        // BOM sniff
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE).removePrefix("﻿")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE).removePrefix("﻿")
        }
        val charset = sniffPrologEncoding(bytes) ?: return String(bytes, StandardCharsets.UTF_8).removePrefix("﻿")
        return try {
            String(bytes, charset).removePrefix("﻿")
        } catch (_: Exception) {
            String(bytes, StandardCharsets.UTF_8)
        }
    }

    /**
     * Reads a bounded ASCII-safe prefix and looks for encoding="...". The prefix
     * is pure ASCII by XML rules, so latin-1 decoding never corrupts it.
     */
    private fun sniffPrologEncoding(bytes: ByteArray): Charset? {
        val prefixLen = minOf(bytes.size, 512)
        val prefix = String(bytes, 0, prefixLen, StandardCharsets.ISO_8859_1)
        val marker = prefix.indexOf("<?xml")
        if (marker < 0) return null
        val end = prefix.indexOf("?>", marker)
        if (end < 0) return null
        val decl = prefix.substring(marker, end)
        val m = Regex("""encoding\s*=\s*["']([^"']+)["']""").find(decl) ?: return null
        val name = m.groupValues[1]
        return try {
            when (name.lowercase()) {
                "utf-8", "utf8" -> null // default, avoid explicit re-decode path
                else -> Charset.forName(name)
            }
        } catch (_: Exception) {
            null
        }
    }
}
