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
                    kind = AttachmentKind.CURRENT_SELECTION,
                    label = "当前选区",
                    reference = "当前选区：src/App.kt 第 10-20 行",
                    path = "src/App.kt",
                    startLine = 10,
                    endLine = 20,
                    resolvedContent = "fun example() = Unit",
                ),
            ),
            toolCallingEnabled = true,
        )

        assertTrue(prepared.transcriptSummary.contains("当前选区"))
        assertTrue(prepared.injectedPrompt.contains("get_current_selection"))
        assertTrue(prepared.injectedPrompt.contains("read_file(path=\"src/App.kt\""))
        assertTrue(prepared.injectedPrompt.contains("hints only"))
        assertFalse(prepared.injectedPrompt.contains("fun example() = Unit"))
    }

    @Test
    fun `resolved prompt includes content when tools are disabled`() {
        val prepared = AttachmentPromptSupport.prepare(
            attachments = listOf(
                AttachmentContext(
                    kind = AttachmentKind.CURRENT_FILE,
                    label = "当前文件",
                    reference = "当前文件：src/App.kt",
                    path = "src/App.kt",
                    resolvedContent = "class App",
                ),
            ),
            toolCallingEnabled = false,
        )

        assertTrue(prepared.injectedPrompt.contains("class App"))
        assertTrue(prepared.injectedPrompt.contains("当前文件"))
    }
}
