package org.libepub.core

/** Base type for all book-level failures surfaced to the caller. */
open class EpubException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Structurally invalid container/package (missing container.xml, malformed OPF, ...). */
class EpubFormatException(message: String, cause: Throwable? = null) : EpubException(message, cause)

/** Book uses content DRM we refuse to render rather than showing corrupt text. */
class DrmProtectedException(message: String) : EpubException(message)
