package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.collects.ApiProviderConfigs
import com.ai.assistance.operit.data.collects.ModelThinkingConfigDefaults
import com.ai.assistance.operit.data.model.ApiProviderType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XaiProviderReasoningTest {
    private val thinkingConfigurations = ModelThinkingConfigDefaults.forProvider(ApiProviderType.XAI.name)

    private fun mapping(modelName: String): ThinkingQualityMapping =
        ThinkingQualityMappingRegistry.resolve(
            providerTypeId = ApiProviderType.XAI.name,
            modelName = modelName,
            thinkingConfigurations = thinkingConfigurations
        )

    @Test
    fun defaultConfigUsesTheOfficialXaiEndpointAndModel() {
        assertEquals(
            "grok-4.6",
            ApiProviderConfigs.getDefaultModelName(ApiProviderType.XAI)
        )
        assertEquals(
            "https://api.x.ai/v1/chat/completions",
            ApiProviderConfigs.getDefaultApiEndpoint(ApiProviderType.XAI)
        )
        assertEquals(
            "https://api.x.ai/v1/models",
            ModelListFetcher.getModelsListUrl(
                "https://api.x.ai/v1/chat/completions",
                ApiProviderType.XAI
            )
        )
    }

    @Test
    fun enabledOptionsMapToXaiEfforts() {
        val mapping = mapping("grok-4.6")
        assertEquals(
            listOf("low", "medium", "high", "xhigh"),
            listOf("low", "medium", "high", "xhigh").map {
                mapping.textValueFor(it)
            }
        )
    }

    @Test
    fun mapperPreservesTheSelectedEffort() {
        val requestJson = JSONObject().put("model", "grok-4.6")
        ThinkingConfigurationApplier.apply(
            requestJson = requestJson,
            providerTypeId = ApiProviderType.XAI.name,
            modelName = "grok-4.6",
            apiEndpoint = "https://api.x.ai/v1/chat/completions",
            thinkingConfigurations = thinkingConfigurations,
            enableThinking = true,
            optionId = "high"
        )
        assertEquals("high", requestJson.getString("reasoning_effort"))
    }

    @Test
    fun reasoningEffortUsesTheGrokFamilyRule() {
        for (model in listOf("grok-4.6", "grok-4.5-latest", "grok-3-mini")) {
            assertEquals(model, ThinkingQualityControl.LEVELS, mapping(model).control)
            assertTrue(model, mapping(model).reasoningRequired)
        }
    }

    @Test
    fun nonGrokModelsDoNotReceiveReasoningEffort() {
        val requestJson = JSONObject().put("model", "other-model")
        ThinkingConfigurationApplier.apply(
            requestJson = requestJson,
            providerTypeId = ApiProviderType.XAI.name,
            modelName = "other-model",
            apiEndpoint = "https://api.x.ai/v1/chat/completions",
            thinkingConfigurations = thinkingConfigurations,
            enableThinking = true,
            optionId = "high"
        )
        assertEquals(ThinkingQualityControl.UNSUPPORTED, mapping("other-model").control)
        assertFalse(requestJson.has("reasoning_effort"))
    }
}
