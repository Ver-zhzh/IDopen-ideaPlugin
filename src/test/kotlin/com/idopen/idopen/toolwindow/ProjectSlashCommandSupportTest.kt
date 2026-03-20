package com.idopen.idopen.toolwindow

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectSlashCommandSupportTest {
    @Test
    fun `discovers nested markdown commands and trims extensions`() {
        withFakeHome {
            val projectRoot = Files.createTempDirectory("idopen-project-commands")
            try {
                val commandDir = projectRoot.resolve(".opencode/commands/quality")
                commandDir.createDirectories()
                commandDir.resolve("review.md").writeText(
                    """
                    ---
                    description: Review code quality
                    argument-hint: module-name
                    agent: review
                    model: gpt-5.4
                    ---
                    Review ${'$'}ARGUMENTS with the local quality checklist.
                    """.trimIndent(),
                )

                val commands = ProjectSlashCommandSupport.available(projectRoot)

                assertEquals(1, commands.size)
                assertEquals("quality/review", commands.first().name)
                assertEquals("Review code quality", commands.first().description)
                assertEquals("module-name", commands.first().argumentHint)
                assertEquals("review", commands.first().agent)
                assertEquals("gpt-5.4", commands.first().model)
                assertTrue(commands.first().template.contains("quality checklist"))
            } finally {
                deleteTree(projectRoot)
            }
        }
    }

    @Test
    fun `later project command roots override earlier roots with the same name`() {
        withFakeHome {
            val projectRoot = Files.createTempDirectory("idopen-project-commands-priority")
            try {
                val opencodeDir = projectRoot.resolve(".opencode/commands")
                val claudeDir = projectRoot.resolve(".claude/commands")
                opencodeDir.createDirectories()
                claudeDir.createDirectories()
                opencodeDir.resolve("review.md").writeText("Opencode review template")
                claudeDir.resolve("review.md").writeText(
                    """
                    ---
                    description: Claude review command
                    ---
                    Claude review template
                    """.trimIndent(),
                )

                val commands = ProjectSlashCommandSupport.available(projectRoot)

                assertEquals(1, commands.size)
                assertEquals("review", commands.first().name)
                assertEquals("Claude review command", commands.first().description)
                assertTrue(commands.first().template.contains("Claude review template"))
            } finally {
                deleteTree(projectRoot)
            }
        }
    }

    @Test
    fun `format includes metadata in verbose mode`() {
        withFakeHome {
            val projectRoot = Files.createTempDirectory("idopen-project-commands-format")
            try {
                val commandDir = projectRoot.resolve(".opencode/commands")
                commandDir.createDirectories()
                commandDir.resolve("fix.md").writeText(
                    """
                    ---
                    description: Fix target
                    argument-hint: bug-id
                    agent: build
                    model: gpt-5.4
                    ---
                    Fix ${'$'}1
                    """.trimIndent(),
                )

                val details = ProjectSlashCommandSupport.format(projectRoot, ProjectSlashCommandSupport.available(projectRoot), verbose = true)

                assertTrue(details.contains("/fix"))
                assertTrue(details.contains("argument-hint: bug-id"))
                assertTrue(details.contains("agent: build"))
                assertTrue(details.contains("model: gpt-5.4"))
                assertTrue(details.contains(".opencode/commands/fix.md"))
            } finally {
                deleteTree(projectRoot)
            }
        }
    }

    @Test
    fun `find resolves project commands case insensitively`() {
        withFakeHome {
            val projectRoot = Files.createTempDirectory("idopen-project-commands-find")
            try {
                val commandDir = projectRoot.resolve(".opencode/commands")
                commandDir.createDirectories()
                commandDir.resolve("Review.md").writeText(
                    """
                    ---
                    description: Review target
                    agent: reviewer
                    model: gpt-5.4
                    ---
                    Review ${'$'}ARGUMENTS carefully.
                    """.trimIndent(),
                )

                val matched = ProjectSlashCommandSupport.find(projectRoot, "review")
                val missing = ProjectSlashCommandSupport.find(projectRoot, "fix")

                assertNotNull(matched)
                assertEquals("Review", matched.name)
                assertEquals("reviewer", matched.agent)
                assertEquals("gpt-5.4", matched.model)
                assertNull(missing)
            } finally {
                deleteTree(projectRoot)
            }
        }
    }

    @Test
    fun `project commands override global commands with the same name`() {
        withFakeHome { home ->
            val globalDir = home.resolve(".config/opencode/commands")
            globalDir.createDirectories()
            globalDir.resolve("review.md").writeText(
                """
                ---
                description: Global review command
                ---
                Review with the global defaults.
                """.trimIndent(),
            )
            globalDir.resolve("explain.md").writeText("Explain the selected component.")

            val projectRoot = Files.createTempDirectory("idopen-project-commands-global")
            try {
                val projectDir = projectRoot.resolve(".opencode/commands")
                projectDir.createDirectories()
                projectDir.resolve("review.md").writeText(
                    """
                    ---
                    description: Project review command
                    ---
                    Review with the project defaults.
                    """.trimIndent(),
                )

                val commands = ProjectSlashCommandSupport.available(projectRoot)

                assertEquals(listOf("explain", "review"), commands.map { it.name })
                assertEquals("Project review command", commands.first { it.name == "review" }.description)
                assertTrue(commands.first { it.name == "explain" }.path.toString().contains(".config"))
            } finally {
                deleteTree(projectRoot)
            }
        }
    }

    private fun withFakeHome(block: (Path) -> Unit) {
        val originalHome = System.getProperty("user.home")
        val fakeHome = Files.createTempDirectory("idopen-home-commands")
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
