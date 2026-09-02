package dev.gaphunter.k8slabelselectorexposurecompanion.inspection

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class LabelSelectorExposureInspectionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(LabelSelectorExposureInspection::class.java)
    }

    fun `test a Service-exposed workload uncovered by any NetworkPolicy is flagged`() {
        myFixture.addFileToProject(
            "service.yaml",
            """
            kind: Service
            metadata:
              name: api-svc
            spec:
              selector:
                app: api
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "networkpolicy.yaml",
            """
            kind: NetworkPolicy
            metadata:
              name: allow-other
            spec:
              podSelector:
                matchLabels:
                  app: other
              policyTypes:
              - Ingress
            """.trimIndent(),
        )
        myFixture.configureByText(
            "deployment.yaml",
            """
            kind: Deployment
            metadata:
              name: api
            spec:
              template:
                metadata:
                  labels:
                    app: api
            """.trimIndent(),
        )
        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.any { it.description?.contains("silently unprotected") == true })
    }

    fun `test a workload covered by a matching NetworkPolicy is not flagged`() {
        myFixture.addFileToProject(
            "service.yaml",
            """
            kind: Service
            metadata:
              name: api-svc
            spec:
              selector:
                app: api
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "networkpolicy.yaml",
            """
            kind: NetworkPolicy
            metadata:
              name: allow-api
            spec:
              podSelector:
                matchLabels:
                  app: api
              policyTypes:
              - Ingress
            """.trimIndent(),
        )
        myFixture.configureByText(
            "deployment.yaml",
            """
            kind: Deployment
            metadata:
              name: api
            spec:
              template:
                metadata:
                  labels:
                    app: api
            """.trimIndent(),
        )
        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.none { it.description?.contains("silently unprotected") == true })
    }

    fun `test a workload not Service-exposed at all is not flagged`() {
        myFixture.addFileToProject(
            "networkpolicy.yaml",
            """
            kind: NetworkPolicy
            metadata:
              name: allow-other
            spec:
              podSelector:
                matchLabels:
                  app: other
              policyTypes:
              - Ingress
            """.trimIndent(),
        )
        myFixture.configureByText(
            "deployment.yaml",
            """
            kind: Deployment
            metadata:
              name: internal-only
            spec:
              template:
                metadata:
                  labels:
                    app: internal-only
            """.trimIndent(),
        )
        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.none { it.description?.contains("silently unprotected") == true })
    }

    fun `test no NetworkPolicy anywhere in the project produces no warning`() {
        myFixture.addFileToProject(
            "service.yaml",
            """
            kind: Service
            metadata:
              name: api-svc
            spec:
              selector:
                app: api
            """.trimIndent(),
        )
        myFixture.configureByText(
            "deployment.yaml",
            """
            kind: Deployment
            metadata:
              name: api
            spec:
              template:
                metadata:
                  labels:
                    app: api
            """.trimIndent(),
        )
        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.none { it.description?.contains("silently unprotected") == true })
    }

    fun `test a non-yaml file is never scanned`() {
        myFixture.configureByText(
            "Notes.java",
            "String x = \"kind: Deployment\";",
        )
        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.none { it.description?.contains("silently unprotected") == true })
    }
}
