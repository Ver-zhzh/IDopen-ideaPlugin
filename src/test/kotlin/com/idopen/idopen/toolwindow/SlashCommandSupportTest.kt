package com.idopen.idopen.toolwindow

import com.idopen.idopen.settings.DisplayLanguage
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SlashCommandSupportTest {
    @Test
    fun `parse supports aliases and preserves arguments`() {
        val parsed = SlashCommandSupport.parse("/del current draft")

        assertNotNull(parsed)
        assertEquals(SlashCommandId.DELETE, parsed.definition.id)
        assertEquals("current draft", parsed.argument)
    }

    @Test
    fun `suggestions show commands when only slash is typed`() {
        val suggestions = SlashCommandSupport.suggestions("/")

        assertTrue(suggestions.isNotEmpty())
        assertEquals(SlashCommandId.HELP, suggestions.first().id)
    }

    @Test
    fun `suggestions hide after exact command is completed`() {
        assertTrue(SlashCommandSupport.suggestions("/help").isEmpty())
        assertTrue(SlashCommandSupport.suggestions("/help ").isEmpty())
    }

    @Test
    fun `mcp command is available in suggestions and parsing`() {
        val suggestions = SlashCommandSupport.suggestions("/mc")
        val parsed = SlashCommandSupport.parse("/mcp")

        assertTrue(suggestions.any { it.id == SlashCommandId.MCP })
        assertNotNull(parsed)
        assertEquals(SlashCommandId.MCP, parsed.definition.id)
    }

    @Test
    fun `commands action is available in suggestions and parsing`() {
        val suggestions = SlashCommandSupport.suggestions("/co")
        val parsed = SlashCommandSupport.parse("/commands")

        assertTrue(suggestions.any { it.id == SlashCommandId.COMMANDS })
        assertNotNull(parsed)
        assertEquals(SlashCommandId.COMMANDS, parsed.definition.id)
    }

    @Test
    fun `mode action is available in suggestions and parsing`() {
        val suggestions = SlashCommandSupport.suggestions("/mo")
        val parsed = SlashCommandSupport.parse("/mode review")

        assertTrue(suggestions.any { it.id == SlashCommandId.MODE })
        assertNotNull(parsed)
        assertEquals(SlashCommandId.MODE, parsed.definition.id)
        assertEquals("review", parsed.argument)
    }

    @Test
    fun `agents action is available in suggestions and parsing`() {
        val suggestions = SlashCommandSupport.suggestions("/ag")
        val parsed = SlashCommandSupport.parse("/agents")

        assertTrue(suggestions.any { it.id == SlashCommandId.AGENTS })
        assertNotNull(parsed)
        assertEquals(SlashCommandId.AGENTS, parsed.definition.id)
    }

    @Test
    fun `skills command is available in suggestions and alias parsing`() {
        val suggestions = SlashCommandSupport.suggestions("/sk")
        val parsed = SlashCommandSupport.parse("/skill")

        assertTrue(suggestions.any { it.id == SlashCommandId.SKILLS })
        assertNotNull(parsed)
        assertEquals(SlashCommandId.SKILLS, parsed.definition.id)
    }

    @Test
    fun `toggle parsing accepts english and chinese variants`() {
        assertEquals(SlashToggleIntent.ENABLE, SlashCommandSupport.resolveToggle("on"))
        assertEquals(SlashToggleIntent.ENABLE, SlashCommandSupport.resolveToggle("开启"))
        assertEquals(SlashToggleIntent.DISABLE, SlashCommandSupport.resolveToggle("关闭"))
        assertEquals(SlashToggleIntent.TOGGLE, SlashCommandSupport.resolveToggle(""))
    }

    @Test
    fun `prompt commands expand into localized prompts`() {
        val projectPrompt = SlashCommandSupport.buildPrompt(
            ParsedSlashCommand(
                definition = SlashCommandSupport.parse("/project 登录流程")!!.definition,
                argument = "登录流程",
            ),
            DisplayLanguage.ZH_CN,
        )
        val reviewPrompt = SlashCommandSupport.buildPrompt(
            ParsedSlashCommand(
                definition = SlashCommandSupport.parse("/review auth module")!!.definition,
                argument = "auth module",
            ),
            DisplayLanguage.EN_US,
        )

        assertTrue(projectPrompt.contains("登录流程"))
        assertTrue(projectPrompt.contains("总结"))
        assertTrue(reviewPrompt.contains("auth module"))
        assertTrue(reviewPrompt.contains("bugs"))
    }

    @Test
    fun `apply suggestion keeps existing suffix for prompt commands`() {
        val definition = SlashCommandSupport.parse("/todo")!!.definition

        val updated = SlashCommandSupport.applySuggestion("/to investigate parser", definition)

        assertEquals("/todo investigate parser", updated)
    }

    @Test
    fun `project custom commands override builtins and expand arguments`() {
        withFakeHome {
            val projectRoot = Files.createTempDirectory("idopen-slash-custom")
            try {
                val commandDir = projectRoot.resolve(".idopen/commands")
                commandDir.createDirectories()
                commandDir.resolve("review.md").writeText(
                    """
                    ---
                    description: Project review command
                    argument-hint: target
                    ---
                    Focus on ${'$'}ARGUMENTS and list project-specific risks.
                    """.trimIndent(),
                )

                val suggestions = SlashCommandSupport.suggestions("/", projectRoot)
                val parsed = SlashCommandSupport.parse("/review auth flow", projectRoot)

                assertTrue(suggestions.first().id == SlashCommandId.CUSTOM)
                assertNotNull(parsed)
                assertEquals(SlashCommandId.CUSTOM, parsed.definition.id)
                assertEquals("review", parsed.definition.name)
                assertEquals("target", parsed.definition.argumentHint)
                assertTrue(parsed.definition.displayLabel(DisplayLanguage.EN_US).contains("<target>"))

                val prompt = SlashCommandSupport.buildPrompt(parsed, DisplayLanguage.EN_US)
                assertTrue(prompt.contains("auth flow"))
                assertTrue(prompt.contains("project-specific risks"))
            } finally {
                Files.walk(projectRoot).sorted(java.util.Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun withFakeHome(block: () -> Unit) {
        val originalHome = System.getProperty("user.home")
        val fakeHome = Files.createTempDirectory("idopen-home-slash")
        System.setProperty("user.home", fakeHome.toString())
        try {
            block()
        } finally {
            if (originalHome == null) {
                System.clearProperty("user.home")
            } else {
                System.setProperty("user.home", originalHome)
            }
            Files.walk(fakeHome).sorted(java.util.Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
