package com.ai.assistance.operit.api.chat.enhance

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelRequestConcurrencyPolicyTest {
    @Test
    fun regularRequestsStillRespectConfiguredLimit() {
        assertEquals(1, ModelRequestConcurrencyPolicy.CONFIGURED.effectiveLimit(1))
        assertEquals(20, ModelRequestConcurrencyPolicy.CONFIGURED.effectiveLimit(20))
        assertEquals(0, ModelRequestConcurrencyPolicy.CONFIGURED.effectiveLimit(0))
        assertEquals(0, ModelRequestConcurrencyPolicy.CONFIGURED.effectiveLimit(-1))
    }

    @Test
    fun independentSubagentRequestsHaveNoModelConcurrencyQuota() {
        for (limit in listOf(-1, 0, 1, 2, 20, Int.MAX_VALUE)) {
            assertEquals(0, ModelRequestConcurrencyPolicy.UNRESTRICTED.effectiveLimit(limit))
        }
    }
}