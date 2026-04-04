package com.idopen.idopen.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalizedTextSupportTest {
    @Test
    fun `choose returns chinese text when it is clean`() {
        assertEquals("额度", LocalizedTextSupport.choose(DisplayLanguage.ZH_CN, "额度", "Quota"))
    }

    @Test
    fun `choose falls back to english when chinese text looks corrupted`() {
        assertEquals("Quota", LocalizedTextSupport.choose(DisplayLanguage.ZH_CN, "棰濆害", "Quota"))
        assertTrue(LocalizedTextSupport.looksLikeMojibake("鏈嶅姟绔湭杩斿洖"))
    }

    @Test
    fun `choose returns english when display language is not chinese`() {
        assertEquals("Quota", LocalizedTextSupport.choose(DisplayLanguage.EN_US, "额度", "Quota"))
    }
}
