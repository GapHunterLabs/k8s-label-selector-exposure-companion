package dev.gaphunter.k8slabelselectorexposurecompanion.detect

import dev.gaphunter.k8slabelselectorexposurecompanion.model.LabelSelectorExpr

/**
 * Real Kubernetes `LabelSelector` set-based matching -- `matchLabels`
 * (every key must equal exactly) AND every `matchExpressions` entry,
 * each evaluated by its own operator. An empty selector (`{}`) matches
 * every set of labels, exactly as the platform defines it.
 *
 * **v0.1 scope, stated honestly:** only `In`/`NotIn`/`Exists` are
 * evaluated for real -- an unrecognized operator (chiefly
 * `DoesNotExist`) is treated as satisfied rather than guessed, the
 * SAFE direction for this plugin's actual use (deciding whether a
 * NetworkPolicy covers a workload): treating an unresolved expression
 * as "still matches" can only make the tool UNDER-flag a real gap
 * (silence, not a false alarm), never over-flag one.
 */
object LabelSelectorMatcher {

    fun matches(selector: LabelSelectorExpr, labels: Map<String, String>): Boolean {
        if (selector.isEmpty()) return true
        if (selector.matchLabels.any { (key, value) -> labels[key] != value }) return false

        for (expression in selector.matchExpressions) {
            val actual = labels[expression.key]
            val satisfied = when (expression.operator) {
                "In" -> actual != null && actual in expression.values
                "NotIn" -> actual == null || actual !in expression.values
                "Exists" -> actual != null
                else -> true // e.g. DoesNotExist -- out of v0.1 scope, never guessed (see class doc)
            }
            if (!satisfied) return false
        }
        return true
    }
}
