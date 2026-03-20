package com.idopen.idopen.agent

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectAgentSupportTest {
    @Test
    fun `discovers nested markdown agents and trims extensions`() {
        withFakeHome {
            val projectRoot = Files.createTempDirectory("idopen-project-agents")
            try {
                val agentDir = projectRoot.resolve(".opencode/agents/review")
                agentDir.createDirectories()
                agentDir.resolve("security.md").writeText(
                    """
                    ---
                    description: Security reviewer
                    model: gpt-5.4
                    ---
                    Focus on auth, secrets, and privilege boundaries.
                    """.trimIndent(),
                )

                val agents = ProjectAgentSupport.available(projectRoot)

                assertEquals(1, agents.size)
                assertEquals("review/security", agents.first().name)
                assertEquals("Security reviewer", agents.first().description)
                assertEquals("gpt-5.4", agents.first().model)
                assertTrue(agents.first().prompt.contains("auth"))
            } finally {
                deleteTree(projectRoot)
            }
        }
    }

    @Test
    fun `later roots override earlier roots with the same agent name`() {
        withFakeHome {
            val projectRoot = Files.createTempDirectory("idopen-project-agents-priority")
            try {
                val opencodeDir = projectRoot.resolve(".opencode/agents")
                val claudeDir = projectRoot.resolve(".claude/agents")
                opencodeDir.createDirectories()
                claudeDir.createDirectories()
                opencodeDir.resolve("reviewer.md").writeText("Opencode reviewer")
                claudeDir.resolve("reviewer.md").writeText(
                    """
                    ---
                    description: Claude reviewer
                    ---
                    Claude reviewer prompt
                    """.trimIndent(),
                )

                val agents = ProjectAgentSupport.available(projectRoot)

                assertEquals(1, agents.size)
                assertEquals("reviewer", agents.first().name)
                assertEquals("Claude reviewer", agents.first().description)
                assertTrue(agents.first().prompt.contains("Claude reviewer prompt"))
            } finally {
                deleteTree(projectRoot)
            }
        }
    }

    @Test
    fun `format includes model and path in verbose mode`() {
        withFakeHome {
            val projectRoot = Files.createTempDirectory("idopen-project-agents-format")
            try {
                val agentDir = projectRoot.resolve(".agents")
                agentDir.createDirectories()
                agentDir.resolve("planner.md").writeText(
                    """
                    ---
                    description: Planner agent
                    model: gpt-5.4
                    ---
                    Plan carefully before editing files.
                    """.trimIndent(),
                )

                val details = ProjectAgentSupport.format(projectRoot, ProjectAgentSupport.available(projectRoot), verbose = true)

                assertTrue(details.contains("@planner"))
                assertTrue(details.contains("Planner agent"))
                assertTrue(details.contains("model: gpt-5.4"))
                assertTrue(details.contains(".agents/planner.md"))
            } finally {
                deleteTree(projectRoot)
            }
        }
    }

    @Test
    fun `find resolves agents case insensitively`() {
        withFakeHome {
            val projectRoot = Files.createTempDirectory("idopen-project-agents-find")
            try {
                val agentDir = projectRoot.resolve(".opencode/agents")
                agentDir.createDirectories()
                agentDir.resolve("Planner.md").writeText(
                    """
                    ---
                    description: Planner agent
                    model: gpt-5.4
                    ---
                    Plan carefully before editing files.
                    """.trimIndent(),
                )

                val matched = ProjectAgentSupport.find(projectRoot, "planner")
                val missing = ProjectAgentSupport.find(projectRoot, "reviewer")

                assertNotNull(matched)
                assertEquals("Planner", matched.name)
                assertNull(missing)
            } finally {
                deleteTree(projectRoot)
            }
        }
    }

    @Test
    fun `project agents override global agents with the same name`() {
        withFakeHome { home ->
            val globalDir = home.resolve(".config/opencode/agents")
            globalDir.createDirectories()
            globalDir.resolve("planner.md").writeText(
                """
                ---
                description: Global planner
                ---
                Plan with the global defaults.
                """.trimIndent(),
            )
            globalDir.resolve("security.md").writeText("Inspect auth and secret handling.")

            val projectRoot = Files.createTempDirectory("idopen-project-agents-global")
            try {
                val projectDir = projectRoot.resolve(".opencode/agents")
                projectDir.createDirectories()
                projectDir.resolve("planner.md").writeText(
                    """
                    ---
                    description: Project planner
                    ---
                    Plan with the project defaults.
                    """.trimIndent(),
                )

                val agents = ProjectAgentSupport.available(projectRoot)

                assertEquals(listOf("planner", "security"), agents.map { it.name })
                assertEquals("Project planner", agents.first { it.name == "planner" }.description)
                assertTrue(agents.first { it.name == "security" }.path.toString().contains(".config"))
            } finally {
                deleteTree(projectRoot)
            }
        }
    }

    private fun withFakeHome(block: (Path) -> Unit) {
        val originalHome = System.getProperty("user.home")
        val fakeHome = Files.createTempDirectory("idopen-home-agents")
        System.setProperty("user.home", fakeHome.toString())
        try {
            block(fakeHome)
        } finally {
            if (originalHome == null) {
                System.clearProperty("user.home")
            } else {
                System.setProperty("user.home", originalHome)
            }
            deleteTree(fakeHome)
        }
    }

    private fun deleteTree(root: Path) {
        Files.walk(root).sorted(java.util.Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}
