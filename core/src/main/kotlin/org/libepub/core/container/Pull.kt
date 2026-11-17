package org.libepub.core.container

import org.kxml2.io.KXmlParser

/**
 * kxml2 (namespace-aware, FEATURE_PROCESS_NAMESPACES=true) returns the *local*
 * name from getName(); the bundled 2006-era xmlpull interface predates getLocalName().
 */
internal val KXmlParser.localName: String
    get() = name
