package com.idopen.idopen.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatGptQuotaSupportTest {
    @Test
    fun `parse quota status extracts primary and secondary windows`() {
        val status = ChatGptQuotaSupport.parseQuotaStatus(
            """
            {
              "plan_type": "pro",
              "primary_window": {
                "used_percent": 62,
                "resets_at": "2026-03-18T12:30:00Z",
                "limit_window_seconds": 18000
              },
              "secondary_window": {
                "used_percent": 21,
                "resets_at": 1773835200,
                "limit_window_seconds": 604800
              }
            }
            """.trimIndent(),
        )

        assertEquals("pro", status.planType)
        assertEquals(2, status.windows.size)

        val primary = assertNotNull(status.windows.firstOrNull { it.source == "primary_window" })
        assertEquals(62, primary.usedPercent)
        assertEquals(38, primary.remainingPercent)
        assertEquals(18_000L, primary.windowSeconds)
        assertNotNull(primary.resetAt)

        val secondary = assertNotNull(status.windows.firstOrNull { it.source == "secondary_window" })
        assertEquals(21, secondary.usedPercent)
        assertEquals(79, secondary.remainingPercent)
        assertEquals(604_800L, secondary.windowSeconds)
        assertEquals(1_773_835_200_000L, secondary.resetAt)
    }

    @Test
    fun `parse quota status also understands rate limit fields`() {
        val status = ChatGptQuotaSupport.parseQuotaStatus(
            """
            {
              "plan_type": "team",
              "rate_limit": {
                "primary_window": {
                  "used_percent": 44,
                  "reset_after_seconds": 60,
                  "window_seconds": 3600
                },
                "secondary_window": {
                  "remaining_percent": 80,
                  "resets_in_seconds": 120,
                  "rolling_window_seconds": 604800
                }
              },
              "code_review_rate_limit": {
                "used_percentage": 80,
                "resets_in_seconds": 120,
                "rolling_window_seconds": 86400
              }
            }
            """.trimIndent(),
        )

        assertEquals("team", status.planType)
        assertEquals(3, status.windows.size)
        assertTrue(status.summary().contains("Quota"))
        assertTrue(status.details().contains("Code review"))
        assertNotNull(status.windows.firstOrNull { it.source.endsWith("rate_limit.primary_window") })
        assertNotNull(status.windows.firstOrNull { it.source.endsWith("rate_limit.secondary_window") })
    }

    @Test
    fun `parse quota status falls back to credits when windows are absent`() {
        val status = ChatGptQuotaSupport.parseQuotaStatus(
            """
            {
              "plan_type": "team",
              "credits": {
                "has_credits": true,
                "balance": 12.5,
                "unlimited": false
              }
            }
            """.trimIndent(),
        )

        assertEquals("team", status.planType)
        assertTrue(status.windows.isEmpty())
        assertEquals("12.5", status.credits?.balance)
        assertTrue(status.summary().contains("Credits balance: 12.5"))
    }
}
