package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.collects.ApiProviderConfigs
import com.ai.assistance.operit.data.collects.ModelThinkingConfigDefaults
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.util.AppLogger
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XaiProviderReasoningTest {
    private var previousSystemLogEnabled = true
    private var previousFileLogEnabled = true

    @Before
    fun disableAndroidLogging() {
        // Local JVM tests cannot execute android.util.Log from the mockable Android jar.
        previousSystemLogEnabled = AppLogger.enableSystemLog
        previousFileLogEnabled = AppLogger.enableFileLogging
        AppLogger.enableSystemLog = false
        AppLogger.enableFileLogging = false
    }

    @After
    fun restoreLogging() {
        AppLogger.enableSystemLog = previousSystemLogEnabled
        AppLogger.enableFileLogging = previousFileLogEnabled
    }

    private fun mapping(modelName: String = "grok-4.6"): ThinkingQualityMapping =
        ThinkingQualityMappingRegistry.resolve(
            providerTypeId = ApiProviderType.XAI.name,
            modelName = modelName,
            thinkingConfigurations = ModelThinkingConfigDefaults.forProvider(ApiProviderType.XAI.name)
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
        assertEquals(
            listOf("low", "medium", "high", "xhigh"),
            listOf("low", "medium", "high", "xhigh").map {
                mapping().textValueFor(it)
            }
        )
    }

    @Test
    fun mapperPreservesTheSelectedEffort() {
        assertEquals(
            "high",
            mapping().textValueFor("high")
        )
    }

    @Test
    fun reasoningEffortUsesTheGrokFamilyRule() {
        listOf("grok-4.6", "grok-4.5-latest", "grok-3-mini").forEach { model ->
            val resolved = mapping(model)
            assertEquals(ThinkingQualityControl.LEVELS, resolved.control)
            assertEquals("reasoning_effort", resolved.parameterLabel)
            assertTrue(resolved.options.isNotEmpty())
        }
    }
}
