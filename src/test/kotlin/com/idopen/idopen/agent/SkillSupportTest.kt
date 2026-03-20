package com.idopen.idopen.agent

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SkillSupportTest {
    @Test
    fun `available prefers opencode skills over legacy directories`() {
        withFakeHome {
            val root = Files.createTempDirectory("idopen-skill-test")
            try {
                writeSkill(
                    root.resolve(".claude/skills/review/SKILL.md"),
                    """
                    ---
                    name: review
                    description: legacy review flow
                    ---
                    Legacy review instructions.
                    """.trimIndent(),
                )
                writeSkill(
                    root.resolve(".opencode/skills/review/SKILL.md"),
                    """
                    ---
                    name: review
                    description: project review flow
                    ---
                    Preferred review instructions.
                    """.trimIndent(),
                )
                writeSkill(
                    root.resolve(".opencode/skills/debug/SKILL.md"),
                    """
                    Inspect runtime logs before changing code.
                    Read the failing path, then narrow the scope.
                    """.trimIndent(),
                )

                val skills = SkillSupport.available(root)

                assertEquals(listOf("debug", "review"), skills.map { it.name })
                assertEquals("project review flow", skills.first { it.name == "review" }.description)
                assertTrue(skills.first { it.name == "debug" }.description.contains("Inspect runtime logs"))
            } finally {
                deleteTree(root)
            }
        }
    }

    @Test
    fun `load tool result wraps skill content and bundled files`() {
        withFakeHome {
            val root = Files.createTempDirectory("idopen-skill-load")
            try {
                val skillDir = root.resolve(".opencode/skills/release")
                writeSkill(
                    skillDir.resolve("SKILL.md"),
                    """
                    ---
                    name: release-check
                    description: verify release steps
                    ---
                    Follow the release checklist carefully.
                    """.trimIndent(),
                )
                Files.createDirectories(skillDir.resolve("scripts"))
                Files.writeString(skillDir.resolve("scripts/check.ps1"), "Write-Host 'ok'")

                val result = SkillSupport.loadToolResult(root, "release-check")

                assertTrue(result.success)
                assertTrue(result.content.contains("<skill_content name=\"release-check\">"))
                assertTrue(result.content.contains("verify release steps"))
                assertTrue(result.content.contains(".opencode/skills/release/scripts/check.ps1"))
            } finally {
                deleteTree(root)
            }
        }
    }

    @Test
    fun `missing skill returns a recoverable tool error`() {
        withFakeHome {
            val root = Files.createTempDirectory("idopen-skill-missing")
            try {
                val result = SkillSupport.loadToolResult(root, "unknown")

                assertFalse(result.success)
                assertNotNull(result.recoveryHint)
                assertTrue(result.content.contains("Available skills"))
            } finally {
                deleteTree(root)
            }
        }
    }

    @Test
    fun `project skills override global skills with the same name`() {
        withFakeHome { home ->
            writeSkill(
                home.resolve(".config/opencode/skills/review/SKILL.md"),
                """
                ---
                name: review
                description: global review flow
                ---
                Global review instructions.
                """.trimIndent(),
            )
            writeSkill(
                home.resolve(".config/opencode/skills/debug/SKILL.md"),
                """
                ---
                name: debug
                description: global debug flow
                ---
                Global debug instructions.
                """.trimIndent(),
            )

            val root = Files.createTempDirectory("idopen-skill-global")
            try {
                writeSkill(
                    root.resolve(".opencode/skills/review/SKILL.md"),
                    """
                    ---
                    name: review
                    description: project review flow
                    ---
                    Project review instructions.
                    """.trimIndent(),
                )

                val skills = SkillSupport.available(root)

                assertEquals(listOf("debug", "review"), skills.map { it.name })
                assertEquals("project review flow", skills.first { it.name == "review" }.description)
                assertTrue(skills.first { it.name == "debug" }.location.toString().contains(".config"))
            } finally {
                deleteTree(root)
            }
        }
    }

    private fun writeSkill(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    private fun withFakeHome(block: (Path) -> Unit) {
        val originalHome = System.getProperty("user.home")
        val fakeHome = Files.createTempDirectory("idopen-home-skills")
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
        val stream = Files.walk(root)
        try {
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        } finally {
            stream.close()
        }
    }
}
