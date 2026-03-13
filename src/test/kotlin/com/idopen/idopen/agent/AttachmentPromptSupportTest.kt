package com.idopen.idopen.agent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentPromptSupportTest {
    @Test
    fun `tool-first prompt keeps code out and instructs tool reads`() {
        val prepared = AttachmentPromptSupport.prepare(
            attachments = listOf(
                AttachmentContext(
                    label = "当前选区",
                    reference = "当前选区：src/App.kt 第 10-20 行，共 120 字符。",
                    resolvedContent = "fun example() = Unit",
                ),
            ),
            toolCallingEnabled = true,
        )

        assertTrue(prepared.transcriptSummary.contains("当前选区"))
        assertTrue(prepared.injectedPrompt.contains("get_current_selection"))
        assertTrue(prepared.injectedPrompt.contains("references only"))
        assertFalse(prepared.injectedPrompt.contains("fun example() = Unit"))
    }

    @Test
    fun `resolved prompt includes content when tools are disabled`() {
        val prepared = AttachmentPromptSupport.prepare(
            attachments = listOf(
                AttachmentContext(
                    label = "当前文件",
                    reference = "当前文件：src/App.kt",
                    resolvedContent = "class App",
                ),
            ),
            toolCallingEnabled = false,
        )

        assertTrue(prepared.injectedPrompt.contains("class App"))
        assertTrue(prepared.injectedPrompt.contains("当前文件"))
    }
}
