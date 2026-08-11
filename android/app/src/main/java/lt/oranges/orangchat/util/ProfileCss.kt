package lt.oranges.orangchat.util

const val PROFILE_CSS_MAX_LEN = 100_000

private const val MAX_NESTING = 8

private val EXTERNAL_URL = Regex("""url\(\s*['"]?(?!data:)""", RegexOption.IGNORE_CASE)
private val DANGEROUS_TOKEN = Regex(
    """(expression\(|javascript:|-moz-binding|behavior\s*:|@import|@charset)""",
    RegexOption.IGNORE_CASE,
)
private val PROPERTY_NAME = Regex("""^-{0,2}[a-zA-Z][a-zA-Z0-9-]*$""")
private val LAYER_NAME = Regex("""^[\w-]+(\.[\w-]+)*$""")
private val CONTAINER_QUERY = Regex("""^[\w\s.,()<>=:%+*/-]+$""")
private val COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

private val STYLE_CLOSE = Regex("""</style""", RegexOption.IGNORE_CASE)

private fun stripStyleClose(css: String): String {
    var out = css
    while (STYLE_CLOSE.containsMatchIn(out)) out = STYLE_CLOSE.replace(out, "")
    return out
}

fun sanitizeProfileCss(css: String?, scopeSelector: String = ".oc-profile-card"): String {
    if (css.isNullOrBlank()) return ""
    val source = COMMENT.replace(css.take(PROFILE_CSS_MAX_LEN), " ")
    val processed = runCatching { processRules(source, scopeSelector, 0) }.getOrDefault("")
    return stripStyleClose(processed)
}

private fun processRules(input: String, scope: String, depth: Int): String {
    if (depth > MAX_NESTING) return ""
    val out = StringBuilder()
    var i = 0
    while (i < input.length) {
        val (terminator, at) = nextTopLevel(input, i) ?: break
        if (terminator == ';') {
            i = at + 1
            continue
        }
        val open = at
        val close = matchingBrace(input, open)
        if (close < 0) break
        val prelude = input.substring(i, open).substringAfterLast('}').trim()
        val body = input.substring(open + 1, close)
        i = close + 1

        if (prelude.startsWith("@")) {
            val name = prelude.takeWhile { !it.isWhitespace() }.lowercase()
            val params = prelude.drop(name.length).trim()
            if (DANGEROUS_TOKEN.containsMatchIn(params)) continue
            when {
                name == "@media" || name == "@supports" -> {
                    val inner = processRules(body, scope, depth + 1)
                    if (inner.isNotBlank()) out.append("$name $params {\n").append(inner).append("}\n")
                }
                name == "@container" && CONTAINER_QUERY.matches(params) -> {
                    val inner = processRules(body, scope, depth + 1)
                    if (inner.isNotBlank()) out.append("$name $params {\n").append(inner).append("}\n")
                }
                name == "@starting-style" && params.isBlank() -> {
                    val inner = processRules(body, scope, depth + 1)
                    if (inner.isNotBlank()) out.append("$name {\n").append(inner).append("}\n")
                }
                name == "@layer" && (params.isBlank() || LAYER_NAME.matches(params)) -> {
                    val inner = processRules(body, scope, depth + 1)
                    if (inner.isNotBlank()) {
                        out.append(if (params.isBlank()) "$name {\n" else "$name $params {\n")
                            .append(inner).append("}\n")
                    }
                }
                name.endsWith("keyframes") -> {
                    val frames = processKeyframes(body)
                    if (frames.isNotBlank() && params.isNotBlank()) {
                        out.append("$name $params {\n").append(frames).append("}\n")
                    }
                }
                else -> Unit
            }
        } else {
            val selector = scopeSelectorList(prelude, scope)
            val declarations = filterDeclarations(body)
            if (selector.isNotBlank() && declarations.isNotBlank()) {
                out.append("$selector { $declarations }\n")
            }
        }
    }
    return out.toString()
}

private fun processKeyframes(body: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < body.length) {
        val open = body.indexOf('{', i)
        if (open < 0) break
        val close = matchingBrace(body, open)
        if (close < 0) break
        val keyText = body.substring(i, open).substringAfterLast('}').trim()
        val declarations = filterDeclarations(body.substring(open + 1, close))
        i = close + 1
        if (keyText.isNotBlank() && declarations.isNotBlank()) {
            out.append("$keyText { $declarations }\n")
        }
    }
    return out.toString()
}

private fun nextTopLevel(input: String, from: Int): Pair<Char, Int>? {
    var parens = 0
    var quote: Char? = null
    for (i in from until input.length) {
        val ch = input[i]
        if (quote != null) {
            if (ch == quote && input.getOrNull(i - 1) != '\\') quote = null
            continue
        }
        when (ch) {
            '"', '\'' -> quote = ch
            '(' -> parens++
            ')' -> parens = maxOf(0, parens - 1)
            '{', ';' -> if (parens == 0) return ch to i
        }
    }
    return null
}

private fun matchingBrace(input: String, open: Int): Int {
    var depth = 0
    var quote: Char? = null
    for (i in open until input.length) {
        val ch = input[i]
        if (quote != null) {
            if (ch == quote && input.getOrNull(i - 1) != '\\') quote = null
            continue
        }
        when (ch) {
            '"', '\'' -> quote = ch
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return i
            }
        }
    }
    return -1
}

private fun splitTopLevel(input: String, separator: Char): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var depth = 0
    for (ch in input) {
        when (ch) {
            '(' -> depth++
            ')' -> depth = maxOf(0, depth - 1)
        }
        if (ch == separator && depth == 0) {
            parts.add(current.toString())
            current.clear()
        } else {
            current.append(ch)
        }
    }
    if (current.isNotBlank()) parts.add(current.toString())
    return parts
}

private fun scopeSelectorList(selector: String, scope: String): String {
    if (DANGEROUS_TOKEN.containsMatchIn(selector)) return ""
    if (selector.contains('<')) return ""
    return splitTopLevel(selector, ',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(", ") { "$scope $it" }
}

private fun filterDeclarations(body: String): String {
    val kept = mutableListOf<String>()
    for (declaration in splitTopLevel(body, ';')) {
        val colon = declaration.indexOf(':')
        if (colon <= 0) continue
        val property = declaration.substring(0, colon).trim().lowercase()
        var value = declaration.substring(colon + 1).trim()
        if (!PROPERTY_NAME.matches(property) || value.isEmpty()) continue

        var important = false
        val bang = value.indexOf('!')
        if (bang >= 0) {
            important = value.substring(bang).trim().equals("!important", ignoreCase = true)
            value = value.substring(0, bang).trim()
        }
        if (value.isEmpty()) continue
        if (DANGEROUS_TOKEN.containsMatchIn(value) || EXTERNAL_URL.containsMatchIn(value)) continue
        if (property == "position" && Regex("""\b(fixed|sticky)\b""", RegexOption.IGNORE_CASE).containsMatchIn(value)) continue

        kept.add(if (important) "$property: $value !important" else "$property: $value")
    }
    return kept.joinToString("; ")
}
