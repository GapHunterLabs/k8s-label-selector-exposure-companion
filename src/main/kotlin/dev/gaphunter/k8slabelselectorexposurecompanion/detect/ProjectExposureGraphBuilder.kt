package dev.gaphunter.k8slabelselectorexposurecompanion.detect

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import dev.gaphunter.k8slabelselectorexposurecompanion.model.LabelSelectorExpr
import dev.gaphunter.k8slabelselectorexposurecompanion.model.NetworkPolicyDecl
import dev.gaphunter.k8slabelselectorexposurecompanion.model.ServiceDecl
import dev.gaphunter.k8slabelselectorexposurecompanion.model.WorkloadPodTemplate

/**
 * Aggregates [K8sSelectorFileParser]'s per-file facts across EVERY
 * `.yml`/`.yaml` file in the project into a real label-selector
 * resolution index, then answers -- for a given workload -- whether it
 * is Service-exposed AND left uncovered by every NetworkPolicy in
 * scope while at least one NetworkPolicy exists (the real, documented
 * failure pattern: a team believes NetworkPolicies protect the
 * namespace, but this particular workload silently isn't matched by
 * any of them).
 *
 * **v0.1 scope, stated honestly:** every manifest found in the project
 * is treated as one flat, implicit namespace -- an explicit
 * `metadata.namespace:` on different manifests is never cross-checked,
 * so a Service/NetworkPolicy declared for a genuinely different
 * namespace than the workload could produce a false negative (treated
 * as covering/exposing it when it wouldn't in a real cluster) -- the
 * safe direction for a plugin whose job is flagging gaps, not the
 * unsafe one.
 *
 * Cached per-project via [CachedValuesManager], invalidated on any PSI
 * change, same reasoning as `terraform-iam-privesc-companion`'s own
 * whole-project graph.
 */
object ProjectExposureGraphBuilder {

    const val MAX_YAML_FILES = 500
    private const val MAX_FILE_LENGTH = 500_000

    private val CACHE_KEY: Key<CachedValue<ExposureIndex>> = Key.create("k8sLabelSelectorExposureCompanion.index")

    class ExposureIndex(
        val workloads: List<WorkloadPodTemplate>,
        private val services: List<ServiceDecl>,
        private val networkPolicies: List<NetworkPolicyDecl>,
    ) {
        fun isServiceExposed(workload: WorkloadPodTemplate): Boolean =
            services.any { LabelSelectorMatcher.matches(LabelSelectorExpr(it.selector, emptyList()), workload.labels) }

        fun hasAnyNetworkPolicyInScope(): Boolean = networkPolicies.isNotEmpty()

        fun isCoveredByIngressNetworkPolicy(workload: WorkloadPodTemplate): Boolean =
            networkPolicies.any { it.coversIngress && LabelSelectorMatcher.matches(it.podSelector, workload.labels) }
    }

    fun indexFor(project: Project): ExposureIndex {
        return CachedValuesManager.getManager(project).getCachedValue(
            project,
            CACHE_KEY,
            { CachedValueProvider.Result.create(computeIndex(project), PsiModificationTracker.MODIFICATION_COUNT) },
            false,
        )
    }

    private fun computeIndex(project: Project): ExposureIndex {
        val scope = GlobalSearchScope.projectScope(project)
        val files = FilenameIndex.getAllFilesByExt(project, "yaml", scope) + FilenameIndex.getAllFilesByExt(project, "yml", scope)
        if (files.size > MAX_YAML_FILES) return ExposureIndex(emptyList(), emptyList(), emptyList())

        val workloads = mutableListOf<WorkloadPodTemplate>()
        val services = mutableListOf<ServiceDecl>()
        val networkPolicies = mutableListOf<NetworkPolicyDecl>()

        val psiManager = PsiManager.getInstance(project)
        for (virtualFile in files) {
            val text = psiManager.findFile(virtualFile)?.text ?: continue
            if (text.length > MAX_FILE_LENGTH) continue

            val facts = K8sSelectorFileParser.parseFile(text, virtualFile.path)
            workloads += facts.workloads
            services += facts.services
            networkPolicies += facts.networkPolicies
        }

        return ExposureIndex(workloads, services, networkPolicies)
    }
}
