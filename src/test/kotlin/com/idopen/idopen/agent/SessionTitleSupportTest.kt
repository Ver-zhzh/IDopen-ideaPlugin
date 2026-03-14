package com.idopen.idopen.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTitleSupportTest {
    @Test
    fun `summarize removes filler prefixes`() {
        val title = SessionTitleSupport.summarize("请帮我修复 README 里的链接错误")

        assertEquals("修复 README 里的链接错误", title)
    }

    @Test
    fun `summarize ignores generic prompts`() {
        assertNull(SessionTitleSupport.summarize("继续"))
        assertNull(SessionTitleSupport.summarize("看下"))
    }

    @Test
    fun `pick title refreshes generic session title`() {
        val title = SessionTitleSupport.pickTitle(
            currentTitle = "继续",
            defaultTitle = "新对话",
            userText = "分析 Gradle 构建失败原因",
        )

        assertEquals("分析 Gradle 构建失败原因", title)
    }

    @Test
    fun `long titles are shortened`() {
        val title = SessionTitleSupport.summarize("请帮我检查这个 IntelliJ 插件在长会话下的上下文压缩是否有问题")

        assertTrue(title!!.length <= 24)
        assertTrue(title.endsWith("..."))
    }
}
