package dev.gaphunter.k8slabelselectorexposurecompanion.detect

/**
 * Small indentation-based YAML micro-scanner -- same "not a real YAML
 * parser" discipline already used by `k8s-resource-limit-companion`'s
 * `K8sManifestScanner`, extended here to walk an arbitrary NESTED key
 * path (`spec.template.metadata.labels`, `spec.podSelector.matchLabels`,
 * etc.), which the single-level scan those other plugins do never
 * needed. Still no YAML PSI dependency -- the platform bundles none
 * for a Kotlin-only plugin.
 */
object IndentedYamlBlocks {

    /**
     * Body lines of the block reached by following [keyPath] one key at
     * a time, each key required to open a nested block (`KEY:` with
     * nothing else on the line) -- the FIRST match of each key found
     * within the current (already-narrowed) scope is followed, which is
     * correct for a single-document manifest (this plugin's stated
     * v0.1 assumption, same as this catalog's other YAML scanners).
     * Returns null the moment any step of the path isn't found.
     */
    fun findBlockBody(lines: List<String>, keyPath: List<String>): List<String>? {
        var scope = lines
        for (key in keyPath) {
            val keyRegex = Regex("""^(\s*)${Regex.escape(key)}:\s*$""")
            var lineIndex = -1
            for ((idx, line) in scope.withIndex()) {
                if (keyRegex.containsMatchIn(line)) {
                    lineIndex = idx
                    break
                }
            }
            if (lineIndex < 0) return null
            val bodyStart = lineIndex + 1

            // Reference indent for THIS key's own body -- the first
            // non-blank/non-comment line after it, NOT the key's own
            // indent. Normally keyIndent+2 for a nested map, but a YAML
            // list can validly write its `- ` items at the SAME column
            // as their own key (`matchExpressions:` / `- key: env` both
            // at indent 4) -- using the first body line's real indent as
            // the "still inside this block" threshold handles both
            // shapes with one rule, instead of assuming body > key.
            var referenceIndent = -1
            for (idx in bodyStart until scope.size) {
                val trimmed = scope[idx].trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                referenceIndent = scope[idx].length - scope[idx].trimStart().length
                break
            }
            if (referenceIndent == -1) return emptyList() // key found, but it has no body at all (e.g. end of file right after)

            var bodyEnd = bodyStart
            while (bodyEnd < scope.size && !isBlockEnd(scope[bodyEnd], referenceIndent - 1)) bodyEnd++
            scope = scope.subList(bodyStart, bodyEnd)
        }
        return scope
    }

    /** Parses immediate `key: value` scalar lines of [lines] into a map -- a line whose value is empty (a nested block's own opening key) is skipped, never treated as an empty-string value. */
    fun parseFlatKeyValues(lines: List<String>): Map<String, String> {
        val kv = Regex("""^\s*([\w./-]+):\s*["']?([^"'#]*?)["']?\s*(?:#.*)?$""")
        val result = mutableMapOf<String, String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) continue
            val match = kv.find(line) ?: continue
            val value = match.groupValues[2].trim()
            if (value.isEmpty()) continue
            result[match.groupValues[1]] = value
        }
        return result
    }

    /** Splits a YAML list block (`- key: a` / `  value: b` per item) into one raw line-group per `-` entry at the list's own top indent. */
    fun splitListItems(lines: List<String>): List<List<String>> {
        val items = mutableListOf<MutableList<String>>()
        var dashIndent = -1
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val indent = line.length - line.trimStart().length
            if (trimmed.startsWith("- ") || trimmed == "-") {
                if (dashIndent == -1) dashIndent = indent
                if (indent == dashIndent) {
                    val rest = line.trimStart().removePrefix("-").let { if (it.startsWith(" ")) it.drop(1) else it }
                    val reindented = " ".repeat(dashIndent + 2) + rest
                    items += mutableListOf(reindented)
                    continue
                }
            }
            if (items.isNotEmpty()) items.last() += line
        }
        return items
    }

    /** Plain scalar list (`- a` / `- b`, no nested keys) into a string list, e.g. `matchExpressions[].values` or `spec.policyTypes`. */
    fun parseScalarList(lines: List<String>): List<String> =
        lines.mapNotNull { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("-")) return@mapNotNull null
            trimmed.removePrefix("-").trim().trim('"', '\'').ifEmpty { null }
        }

    private fun isBlockEnd(line: String, indent: Int): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return false
        val lineIndent = line.length - line.trimStart().length
        return lineIndent <= indent
    }
}
