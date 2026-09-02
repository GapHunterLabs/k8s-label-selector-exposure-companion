package dev.gaphunter.k8slabelselectorexposurecompanion.model

/** One `matchExpressions` entry of a real Kubernetes `LabelSelector`. [operator] is `In`/`NotIn`/`Exists` -- v0.1's supported subset, see [dev.gaphunter.k8slabelselectorexposurecompanion.detect.LabelSelectorMatcher]. */
data class MatchExpression(val key: String, val operator: String, val values: List<String>)

/** A real Kubernetes `LabelSelector`: `matchLabels` (AND of exact equality) plus `matchExpressions` (each its own AND'd condition) -- both together are AND'd, exactly as the platform defines set-based selectors. */
data class LabelSelectorExpr(
    val matchLabels: Map<String, String>,
    val matchExpressions: List<MatchExpression>,
) {
    /** True for `{}` (no matchLabels, no matchExpressions) -- real Kubernetes semantics: an empty selector matches every pod in scope. */
    fun isEmpty(): Boolean = matchLabels.isEmpty() && matchExpressions.isEmpty()
}

/** A Deployment/Pod/StatefulSet/DaemonSet's pod TEMPLATE labels (the ones a Service/NetworkPolicy selector actually matches against at runtime -- never the workload resource's own `metadata.labels`, a different, unrelated label set). */
data class WorkloadPodTemplate(
    val kind: String,
    val name: String,
    val labels: Map<String, String>,
    val filePath: String,
    val lineNumber: Int,
)

/** A `Service`'s `spec.selector` -- always simple key=value equality AND, never `matchExpressions` (not part of the real Service selector shape). */
data class ServiceDecl(
    val name: String,
    val selector: Map<String, String>,
    val filePath: String,
)

/** A `NetworkPolicy`'s `spec.podSelector` plus whether it covers Ingress traffic (`spec.policyTypes` includes `Ingress`, or `policyTypes` is absent and an `ingress:` block is present -- the real Kubernetes default-inference rule). */
data class NetworkPolicyDecl(
    val name: String,
    val podSelector: LabelSelectorExpr,
    val coversIngress: Boolean,
    val filePath: String,
)

/** Everything this plugin's parser extracted from a single YAML file's text -- at most one of the three lists is non-empty in practice (v0.1 assumes one `kind:` per file, same single-doc limit as this catalog's other YAML scanners). */
data class FileK8sFacts(
    val workloads: List<WorkloadPodTemplate>,
    val services: List<ServiceDecl>,
    val networkPolicies: List<NetworkPolicyDecl>,
)
