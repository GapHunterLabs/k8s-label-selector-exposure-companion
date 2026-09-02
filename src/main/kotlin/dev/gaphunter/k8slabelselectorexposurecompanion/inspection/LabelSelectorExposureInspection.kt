package dev.gaphunter.k8slabelselectorexposurecompanion.inspection

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.gaphunter.k8slabelselectorexposurecompanion.detect.K8sSelectorFileParser
import dev.gaphunter.k8slabelselectorexposurecompanion.detect.ProjectExposureGraphBuilder
import dev.gaphunter.k8slabelselectorexposurecompanion.model.WorkloadPodTemplate
import dev.gaphunter.k8slabelselectorexposurecompanion.review.ReviewPrompt

/**
 * Flags a workload (Deployment/Pod/StatefulSet/DaemonSet) whose pod
 * template is: (1) selected by a real Service (Service-exposed), AND
 * (2) NOT matched by any NetworkPolicy's Ingress-covering podSelector,
 * WHILE (3) at least one NetworkPolicy exists somewhere in the project
 * -- the real, documented Kubernetes failure pattern: a team relies on
 * NetworkPolicy for segmentation, but this specific workload silently
 * falls outside every one of them ("a pod not selected by any
 * NetworkPolicy is not governed by any policy").
 *
 * Re-parses only the CURRENT file for its own workload declarations
 * (cheap, reliable anchor); the exposure verdict itself comes from the
 * real, cached, whole-project [ProjectExposureGraphBuilder] index.
 */
class LabelSelectorExposureInspection : LocalInspectionTool() {

    companion object {
        const val MAX_FILE_LENGTH = 500_000
        private val YAML_FILE_NAME = Regex("""^[^.]+\.ya?ml$""", RegexOption.IGNORE_CASE)
    }

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val virtualFile = file.virtualFile ?: return null
        if (!YAML_FILE_NAME.matches(virtualFile.name)) return null

        val text = file.text
        if (text.length > MAX_FILE_LENGTH) return null

        val localWorkloads = K8sSelectorFileParser.parseFile(text, virtualFile.path).workloads
        if (localWorkloads.isEmpty()) return null

        val index = ProjectExposureGraphBuilder.indexFor(file.project)
        if (!index.hasAnyNetworkPolicyInScope()) return null // no NetworkPolicy anywhere -- nothing to be silently uncovered BY

        val document = file.viewProvider.document ?: return null
        val problems = mutableListOf<ProblemDescriptor>()

        for (workload in localWorkloads) {
            if (!index.isServiceExposed(workload)) continue
            if (index.isCoveredByIngressNetworkPolicy(workload)) continue

            val lineNumber = workload.lineNumber - 1
            if (lineNumber !in 0 until document.lineCount) continue
            val lineStartOffset = document.getLineStartOffset(lineNumber)
            val lineEndOffset = document.getLineEndOffset(lineNumber)
            val anchor = leafElementAt(file, lineStartOffset) ?: continue
            val anchorStart = anchor.textRange.startOffset
            val relativeRange = TextRange(
                (lineStartOffset - anchorStart).coerceAtLeast(0),
                (lineEndOffset - anchorStart).coerceAtMost(anchor.textLength),
            )
            if (relativeRange.startOffset >= relativeRange.endOffset) continue

            problems += manager.createProblemDescriptor(
                anchor,
                relativeRange,
                messageFor(workload),
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                isOnTheFly,
            )
            ReviewPrompt.recordHit(file.project, "${virtualFile.path}:${workload.name}")
        }

        return if (problems.isEmpty()) null else problems.toTypedArray()
    }

    private fun messageFor(workload: WorkloadPodTemplate): String =
        "${workload.kind} '${workload.name}' is Service-exposed but not covered by any NetworkPolicy's Ingress podSelector, " +
            "even though NetworkPolicies exist in this project -- it is silently unprotected (OWASP Kubernetes Top 10, inadequate network segmentation)"

    private fun leafElementAt(file: PsiFile, startOffset: Int): PsiElement? {
        if (startOffset < 0 || startOffset >= file.textLength) return null
        var element = file.findElementAt(startOffset) ?: return file
        while (element.firstChild != null) {
            element = element.firstChild
        }
        return element
    }
}
