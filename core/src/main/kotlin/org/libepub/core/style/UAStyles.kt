package org.libepub.core.style

import org.libepub.core.css.CssParser
import org.libepub.core.css.Stylesheet

/**
 * User-agent stylesheet: the reader's defaults. Written as real CSS and parsed
 * by the same [CssParser] as author sheets so there is exactly one cascade
 * implementation.
 *
 * Deliberate choices that differ from desktop browsers:
 *  - `body` has no 8px default margin (a reader supplies its own page margins);
 *  - links do not get a UA blue color, they inherit the surrounding text color
 *    (EPUBs style links themselves, and duokan footnote markers must match the
 *    body text);
 *  - footnote asides are hidden from the body flow — the native equivalent of
 *    duokan's `notereplace.js`, which moves them into a popup. The semantic
 *    index still exposes their content for the footnote panel.
 */
object UAStyles {

    val CSS: String = """
        /* ------------------------------------------------ document structure */
        html, body { display: block; margin: 0 }
        head, script, style, link, meta, title, base { display: none }

        div, p, section, article, aside, header, footer, nav, main, figure,
        figcaption, address, center, form, fieldset, legend, details, summary,
        dl, dt, dd, hgroup, blockquote, pre, table, ul, ol, hr {
            display: block
        }

        span, a, em, strong, b, i, u, s, strike, del, ins, small, big, code,
        kbd, samp, tt, sub, sup, ruby, rt, rp, img, br, q, cite, dfn, var,
        abbr, mark, time, label, input, select, textarea, bdi, bdo, wbr {
            display: inline
        }

        li { display: list-item }
        table { display: table; border-collapse: separate; border-spacing: 2px }
        caption { display: block; text-align: center }
        thead, tbody, tfoot { display: block }
        tr { display: table-row }
        td, th { display: table-cell; padding: 1px; vertical-align: middle }
        th { font-weight: bold; text-align: center }

        /* --------------------------------------------------------- headings */
        h1 { display: block; font-size: 2em; font-weight: bold; margin: 0.67em 0 }
        h2 { display: block; font-size: 1.5em; font-weight: bold; margin: 0.83em 0 }
        h3 { display: block; font-size: 1.17em; font-weight: bold; margin: 1em 0 }
        h4 { display: block; font-size: 1em; font-weight: bold; margin: 1.33em 0 }
        h5 { display: block; font-size: 0.83em; font-weight: bold; margin: 1.67em 0 }
        h6 { display: block; font-size: 0.67em; font-weight: bold; margin: 2.33em 0 }

        /* ------------------------------------------------------------ blocks */
        p { margin: 1em 0 }
        blockquote { margin: 1em 40px }
        figure { margin: 1em 40px }
        dl { margin: 1em 0 }
        dd { margin-left: 40px }
        ul, ol { margin: 1em 0; padding-left: 40px }
        li { margin: 0 }
        center { text-align: center }
        pre { white-space: pre; font-family: monospace; margin: 1em 0 }
        hr { margin: 0.5em auto; border: 1px inset }

        /* --------------------------------------------------------- inline text */
        a { text-decoration: underline }
        b, strong { font-weight: bold }
        i, em, cite, dfn, var, address { font-style: italic }
        u, ins { text-decoration: underline }
        s, strike, del { text-decoration: line-through }
        code, kbd, samp, tt, pre { font-family: monospace }
        small { font-size: 0.83em }
        big { font-size: 1.17em }
        sub { vertical-align: sub; font-size: 0.83em }
        sup { vertical-align: super; font-size: 0.83em }
        mark { background-color: yellow }

        /* ------------------------------------------------------------- ruby */
        ruby { display: ruby }
        rt { display: ruby-text; font-size: 0.5em }
        rp { display: none }

        /* -------------------------------------------------- duokan semantics */
        aside[epub:type~="footnote"], aside[type~="footnote"] { display: none }
    """.trimIndent()

    private val sheet: Stylesheet by lazy { CssParser.parse(CSS, "") }

    /** Shared immutable UA sheet (parsed once). */
    fun stylesheet(): Stylesheet = sheet
}
