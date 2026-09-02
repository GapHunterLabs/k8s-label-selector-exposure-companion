package dev.gaphunter.k8slabelselectorexposurecompanion.detect

import dev.gaphunter.k8slabelselectorexposurecompanion.model.FileK8sFacts
import dev.gaphunter.k8slabelselectorexposurecompanion.model.LabelSelectorExpr
import dev.gaphunter.k8slabelselectorexposurecompanion.model.MatchExpression
import dev.gaphunter.k8slabelselectorexposurecompanion.model.NetworkPolicyDecl
import dev.gaphunter.k8slabelselectorexposurecompanion.model.ServiceDecl
import dev.gaphunter.k8slabelselectorexposurecompanion.model.WorkloadPodTemplate

/**
 * Parses a single YAML file's text into whichever of [FileK8sFacts]'s
 * three shapes its `kind:` matches -- a workload's pod-TEMPLATE labels
 * (never the workload resource's own top-level `metadata.labels`, a
 * separate, unrelated label set), a Service's selector, or a
 * NetworkPolicy's podSelector + Ingress coverage.
 *
 * **v0.1 scope, stated honestly:** one `kind:` per file (a multi-doc
 * `---` file isn't specially split, same limit as
 * `k8s-resource-limit-companion`'s own scanner); `policyTypes` read
 * either as a block list or an inline `[Ingress, Egress]` form: if
 * `policyTypes` is absent entirely, Ingress coverage is inferred from
 * the presence of an `ingress:` block under `spec` -- the real
 * Kubernetes default-inference rule.
 */
object K8sSelectorFileParser {

    private val KIND_LINE = Regex("""^kind:\s*["']?(\w+)["']?\s*$""")
    private val NAME_LINE = Regex("""^\s*name:\s*["']?([\w.-]+)["']?\s*$""")
    private val POLICY_TYPES_INLINE = Regex("""policyTypes:\s*\[([^]]*)]""")

    private val WORKLOAD_KINDS = setOf("Deployment", "Pod", "StatefulSet", "DaemonSet")

    fun parseFile(text: String, filePath: String): FileK8sFacts {
        val kind = text.lineSequence().firstNotNullOfOrNull { KIND_LINE.find(it)?.groupValues?.get(1) }
            ?: return EMPTY

        return when (kind) {
            in WORKLOAD_KINDS -> {
                val workload = parseWorkload(text.lines(), kind, filePath)
                FileK8sFacts(listOfNotNull(workload), emptyList(), emptyList())
            }
            "Service" -> {
                val service = parseService(text.lines(), filePath)
                FileK8sFacts(emptyList(), listOfNotNull(service), emptyList())
            }
            "NetworkPolicy" -> {
                val policy = parseNetworkPolicy(text.lines(), filePath)
                FileK8sFacts(emptyList(), emptyList(), listOfNotNull(policy))
            }
            else -> EMPTY
        }
    }

    private fun parseWorkload(lines: List<String>, kind: String, filePath: String): WorkloadPodTemplate? {
        val labelsPath = if (kind == "Pod") listOf("metadata", "labels") else listOf("spec", "template", "metadata", "labels")
        val labelsBody = IndentedYamlBlocks.findBlockBody(lines, labelsPath) ?: return null
        val labels = IndentedYamlBlocks.parseFlatKeyValues(labelsBody)
        if (labels.isEmpty()) return null

        val name = findTopLevelResourceName(lines) ?: "<unnamed>"
        val kindLineNumber = (lines.indexOfFirst { KIND_LINE.matches(it) } + 1).coerceAtLeast(1)
        return WorkloadPodTemplate(kind, name, labels, filePath, kindLineNumber)
    }

    private fun parseService(lines: List<String>, filePath: String): ServiceDecl? {
        val selectorBody = IndentedYamlBlocks.findBlockBody(lines, listOf("spec", "selector")) ?: return null
        val selector = IndentedYamlBlocks.parseFlatKeyValues(selectorBody)
        if (selector.isEmpty()) return null
        val name = findTopLevelResourceName(lines) ?: "<unnamed>"
        return ServiceDecl(name, selector, filePath)
    }

    private fun parseNetworkPolicy(lines: List<String>, filePath: String): NetworkPolicyDecl? {
        val podSelectorBody = IndentedYamlBlocks.findBlockBody(lines, listOf("spec", "podSelector"))
        val emptySelectorLine = lines.any { Regex("""^\s*podSelector:\s*\{\s*}\s*$""").matches(it) }
        if (podSelectorBody == null && !emptySelectorLine) return null

        val podSelector = if (podSelectorBody != null) {
            val matchLabelsBody = IndentedYamlBlocks.findBlockBody(podSelectorBody, listOf("matchLabels")).orEmpty()
            val matchLabels = IndentedYamlBlocks.parseFlatKeyValues(matchLabelsBody)
            val matchExpressionsBody = IndentedYamlBlocks.findBlockBody(podSelectorBody, listOf("matchExpressions")).orEmpty()
            val matchExpressions = parseMatchExpressions(matchExpressionsBody)
            LabelSelectorExpr(matchLabels, matchExpressions)
        } else {
            LabelSelectorExpr(emptyMap(), emptyList())
        }

        val name = findTopLevelResourceName(lines) ?: "<unnamed>"
        return NetworkPolicyDecl(name, podSelector, coversIngress(lines), filePath)
    }

    private fun parseMatchExpressions(body: List<String>): List<MatchExpression> {
        val items = IndentedYamlBlocks.splitListItems(body)
        return items.mapNotNull { itemLines ->
            val flat = IndentedYamlBlocks.parseFlatKeyValues(itemLines)
            val key = flat["key"] ?: return@mapNotNull null
            val operator = flat["operator"] ?: return@mapNotNull null
            val valuesBody = IndentedYamlBlocks.findBlockBody(itemLines, listOf("values")).orEmpty()
            MatchExpression(key, operator, IndentedYamlBlocks.parseScalarList(valuesBody))
        }
    }

    private fun coversIngress(lines: List<String>): Boolean {
        val blockBody = IndentedYamlBlocks.findBlockBody(lines, listOf("spec", "policyTypes"))
        if (blockBody != null) return IndentedYamlBlocks.parseScalarList(blockBody).any { it.equals("Ingress", ignoreCase = true) }

        val inlineMatch = lines.firstNotNullOfOrNull { POLICY_TYPES_INLINE.find(it) }
        if (inlineMatch != null) return inlineMatch.groupValues[1].contains("Ingress", ignoreCase = true)

        // policyTypes entirely absent -- Kubernetes infers Ingress coverage from the presence of an ingress: block.
        return IndentedYamlBlocks.findBlockBody(lines, listOf("spec", "ingress")) != null
    }

    /** The manifest's own top-level `metadata.name` -- the resource's identity, distinct from any pod-template label. */
    private fun findTopLevelResourceName(lines: List<String>): String? {
        val metadataBody = IndentedYamlBlocks.findBlockBody(lines, listOf("metadata")) ?: return null
        for (line in metadataBody) {
            val match = NAME_LINE.find(line) ?: continue
            return match.groupValues[1]
        }
        return null
    }

    private val EMPTY = FileK8sFacts(emptyList(), emptyList(), emptyList())
}
