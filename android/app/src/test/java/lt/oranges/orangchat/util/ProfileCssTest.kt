package lt.oranges.orangchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Profile CSS is authored by one user and rendered in other users' clients, so
 * these are security tests as much as parser tests: anything that escapes the
 * <style> element, reaches the network, or leaves the card is a defect.
 */
class ProfileCssTest {

    private fun assertKeeps(css: String, vararg expected: String) {
        val out = sanitizeProfileCss(css)
        for (fragment in expected) {
            assertTrue("expected '$fragment' in: $out", out.contains(fragment, ignoreCase = true))
        }
    }

    private fun assertDrops(css: String, vararg forbidden: String) {
        val out = sanitizeProfileCss(css)
        for (fragment in forbidden) {
            assertTrue("'$fragment' leaked into: $out", !out.contains(fragment, ignoreCase = true))
        }
    }

    @Test
    fun `keeps and scopes ordinary rules`() {
        assertKeeps(
            ".oc-pf-name { color: #ff6a1a; font-size: 20px }",
            ".oc-profile-card .oc-pf-name",
            "color: #ff6a1a",
            "font-size: 20px",
        )
    }

    @Test
    fun `scopes every branch of a selector list`() {
        assertKeeps(
            ".oc-pf-name, .oc-pf-username { color: red }",
            ".oc-profile-card .oc-pf-name",
            ".oc-profile-card .oc-pf-username",
        )
    }

    @Test
    fun `preserves important`() {
        assertKeeps(".oc-pf-bio { color: red !important }", "color: red !important")
    }

    @Test
    fun `recurses into media and supports`() {
        assertKeeps(
            "@media (max-width: 600px) { .oc-pf-name { color: blue } }",
            "@media (max-width: 600px)",
            ".oc-profile-card .oc-pf-name",
        )
        assertKeeps("@supports (display: grid) { .oc-pf-body { display: grid } }", "@supports")
    }

    @Test
    fun `recurses into container starting-style and layer`() {
        assertKeeps(
            "@container (min-width: 300px) { .oc-pf-name { color: blue } }",
            "@container (min-width: 300px)",
            ".oc-profile-card .oc-pf-name",
        )
        assertKeeps(
            "@starting-style { .oc-pf-body { opacity: 0 } }",
            "@starting-style {",
            ".oc-profile-card .oc-pf-body",
        )
        assertKeeps(
            "@layer theme.base { .oc-pf-body { color: red } }",
            "@layer theme.base",
            ".oc-profile-card .oc-pf-body",
        )
    }

    @Test
    fun `drops grouping at rules with an unparseable prelude`() {
        assertDrops("@layer a\\7B x { .oc-pf-name { color: red } }", "@layer")
        assertDrops("@starting-style bogus { .oc-pf-name { color: red } }", "@starting-style")
        assertDrops("""@container "q" { .oc-pf-name { color: red } }""", "@container")
    }

    @Test
    fun `keeps keyframes`() {
        assertKeeps(
            "@keyframes pulse { 0% { opacity: 0 } 100% { opacity: 1 } }",
            "@keyframes pulse",
            "opacity: 0",
        )
    }

    @Test
    fun `strips comments`() {
        assertDrops("/* hi */ .oc-pf-name { color: red }", "hi")
    }

    @Test
    fun `drops external url but keeps siblings`() {
        assertKeeps(
            ".oc-pf-name { background: url(https://evil.example/pixel.png); color: red }",
            "color: red",
        )
        assertDrops(".oc-pf-name { background: url(https://evil.example/pixel.png) }", "evil.example")
        assertDrops(".oc-pf-name { background: url(//evil.example/p.png) }", "evil.example")
        assertDrops(""".oc-pf-name { background: url("https://evil.example/p.png") }""", "evil.example")
    }

    @Test
    fun `allows data uri`() {
        assertKeeps(".oc-pf-banner { background: url(data:image/png;base64,iVBOR) }", "data:image/png")
    }

    @Test
    fun `drops at rules that can load or escape`() {
        assertDrops("@font-face { font-family: x; src: url(https://evil.example/f.woff) }", "@font-face", "evil.example")
        assertDrops("@page { margin: 0 }", "@page")
        assertDrops(".oc-pf-name { position: fixed }", "position: fixed")
        assertDrops(".oc-pf-name { position: sticky }", "position: sticky")
    }

    @Test
    fun `allows absolute positioning because the card contains it`() {
        assertKeeps(".oc-pf-name { position: absolute }", "position: absolute")
    }

    @Test
    fun `drops legacy scripting hooks`() {
        assertDrops(".oc-pf-name { background: url(javascript:alert(1)) }", "javascript:")
        assertDrops(".oc-pf-name { width: expression(alert(1)) }", "expression(")
        assertDrops(".oc-pf-name { -moz-binding: url(https://evil.example/x.xml) }", "-moz-binding")
        assertDrops(".oc-pf-name { behavior: url(#default#time2) }", "behavior")
    }

    @Test
    fun `statement at rules do not swallow the rules after them`() {
        assertKeeps(
            "@import url('https://evil.example/x.css'); .oc-pf-name { color: red }",
            "color: red",
        )
        assertDrops("@import url('https://evil.example/x.css'); .oc-pf-name { color: red }", "@import", "evil.example")
        assertKeeps("@charset \"utf-8\"; .oc-pf-name { color: red }", "color: red")
        assertKeeps(
            "@namespace svg url(https://www.w3.org/2000/svg); .oc-pf-name { color: red }",
            "color: red",
        )
    }

    @Test
    fun `semicolon inside url does not split the declaration`() {
        assertKeeps(
            ".oc-pf-banner { background: url(data:image/svg+xml;base64,PHN2Zz4=) } .oc-pf-name { color: red }",
            "base64",
            "color: red",
        )
    }

    @Test
    fun `cannot close the style element from a selector`() {
        assertDrops(
            "</style><script>alert(1)</script><style> .oc-pf-name { color: red }",
            "</style",
            "<script",
        )
    }

    @Test
    fun `cannot close the style element from a value`() {
        assertDrops(""".oc-pf-name { font-family: "</style><img src=https://evil.example/x>" }""", "</style")
        assertDrops(""".oc-pf-name { font-family: "</STYLE ><img src=x>" }""", "</style")
    }

    @Test
    fun `inline svg data uri survives the style close strip`() {
        assertKeeps(".oc-pf-banner { background: url(data:image/svg+xml,<svg xmlns='x'></svg>) }", "</svg")
    }

    @Test
    fun `quoted brace does not desync the parser`() {
        assertKeeps(
            """.oc-pf-name { content: "}" ; color: red } .oc-pf-bio { color: blue }""",
            "color: red",
            "color: blue",
        )
    }

    @Test
    fun `malformed input degrades safely`() {
        assertEquals("", sanitizeProfileCss("   "))
        assertEquals("", sanitizeProfileCss(null))
        assertDrops(".oc-pf-name { color: red", "evil")
        assertDrops("body { background: url(https://evil.example/x) }", "evil.example")
        assertKeeps(".oc-pf-name { colorred; color: blue }", "color: blue")
        assertDrops(""".oc-pf-name { <script>: x; color: blue }""", "script")
    }
}
