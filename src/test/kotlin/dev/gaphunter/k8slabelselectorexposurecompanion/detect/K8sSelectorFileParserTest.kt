package dev.gaphunter.k8slabelselectorexposurecompanion.detect

import dev.gaphunter.k8slabelselectorexposurecompanion.model.LabelSelectorExpr
import dev.gaphunter.k8slabelselectorexposurecompanion.model.MatchExpression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class K8sSelectorFileParserTest {

    @Test
    fun `deployment pod template labels are read from spec template metadata labels, not the top-level metadata`() {
        val facts = K8sSelectorFileParser.parseFile(
            """
            apiVersion: apps/v1
            kind: Deployment
            metadata:
              name: api
              labels:
                team: payments
            spec:
              template:
                metadata:
                  labels:
                    app: api
                    tier: backend
            """.trimIndent(),
            "deploy.yaml",
        ).workloads

        assertEquals(1, facts.size)
        assertEquals(mapOf("app" to "api", "tier" to "backend"), facts[0].labels)
    }

    @Test
    fun `bare pod labels are read from top-level metadata labels`() {
        val facts = K8sSelectorFileParser.parseFile(
            """
            kind: Pod
            metadata:
              name: standalone
              labels:
                app: standalone
            """.trimIndent(),
            "pod.yaml",
        ).workloads
        assertEquals(mapOf("app" to "standalone"), facts[0].labels)
    }

    @Test
    fun `service selector is parsed`() {
        val facts = K8sSelectorFileParser.parseFile(
            """
            kind: Service
            metadata:
              name: api-svc
            spec:
              selector:
                app: api
              ports:
              - port: 80
            """.trimIndent(),
            "svc.yaml",
        ).services
        assertEquals(mapOf("app" to "api"), facts[0].selector)
    }

    @Test
    fun `network policy matchLabels and matchExpressions are parsed with Ingress coverage`() {
        val facts = K8sSelectorFileParser.parseFile(
            """
            kind: NetworkPolicy
            metadata:
              name: allow-frontend
            spec:
              podSelector:
                matchLabels:
                  tier: backend
                matchExpressions:
                - key: env
                  operator: In
                  values:
                  - prod
                  - staging
              policyTypes:
              - Ingress
            """.trimIndent(),
            "np.yaml",
        ).networkPolicies

        assertEquals(1, facts.size)
        val policy = facts[0]
        assertTrue(policy.coversIngress)
        assertEquals(mapOf("tier" to "backend"), policy.podSelector.matchLabels)
        assertEquals(listOf(MatchExpression("env", "In", listOf("prod", "staging"))), policy.podSelector.matchExpressions)
    }

    @Test
    fun `network policy with empty podSelector matches everything`() {
        val facts = K8sSelectorFileParser.parseFile(
            """
            kind: NetworkPolicy
            metadata:
              name: default-deny
            spec:
              podSelector: {}
              policyTypes:
              - Ingress
            """.trimIndent(),
            "np.yaml",
        ).networkPolicies
        assertTrue(facts[0].podSelector.isEmpty())
        assertTrue(LabelSelectorMatcher.matches(facts[0].podSelector, mapOf("anything" to "goes")))
    }

    @Test
    fun `policyTypes absent but ingress block present infers Ingress coverage`() {
        val facts = K8sSelectorFileParser.parseFile(
            """
            kind: NetworkPolicy
            metadata:
              name: implicit-ingress
            spec:
              podSelector:
                matchLabels:
                  app: api
              ingress:
              - from:
                - podSelector: {}
            """.trimIndent(),
            "np.yaml",
        ).networkPolicies
        assertTrue(facts[0].coversIngress)
    }

    @Test
    fun `a non-workload kind produces no workload facts`() {
        val facts = K8sSelectorFileParser.parseFile(
            """
            kind: ConfigMap
            metadata:
              name: settings
            data:
              key: value
            """.trimIndent(),
            "cm.yaml",
        )
        assertTrue(facts.workloads.isEmpty())
        assertTrue(facts.services.isEmpty())
        assertTrue(facts.networkPolicies.isEmpty())
    }

    @Test
    fun `matcher requires all matchLabels and all matchExpressions to be satisfied`() {
        val selector = LabelSelectorExpr(
            matchLabels = mapOf("tier" to "backend"),
            matchExpressions = listOf(MatchExpression("env", "In", listOf("prod"))),
        )
        assertTrue(LabelSelectorMatcher.matches(selector, mapOf("tier" to "backend", "env" to "prod")))
        assertFalse(LabelSelectorMatcher.matches(selector, mapOf("tier" to "backend", "env" to "dev")))
        assertFalse(LabelSelectorMatcher.matches(selector, mapOf("tier" to "frontend", "env" to "prod")))
    }

    @Test
    fun `matcher NotIn is satisfied when the key is absent`() {
        val selector = LabelSelectorExpr(emptyMap(), listOf(MatchExpression("env", "NotIn", listOf("prod"))))
        assertTrue(LabelSelectorMatcher.matches(selector, mapOf("tier" to "backend")))
        assertFalse(LabelSelectorMatcher.matches(selector, mapOf("env" to "prod")))
    }
}
