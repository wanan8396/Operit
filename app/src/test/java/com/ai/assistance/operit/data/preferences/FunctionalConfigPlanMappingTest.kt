package com.ai.assistance.operit.data.preferences

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.ai.assistance.operit.data.model.FunctionType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class FunctionalConfigPlanMappingTest {
    @Test
    fun existingSettingsAddBothPlanStagesWithDefaultModel() {
        val mapping = normalize("""{"CHAT":{"configId":"parent","modelIndex":4},"SUBAGENT":{"configId":"child","modelIndex":2}}""")
        assertEquals(FunctionType.values().map { it.name }.toSet(), mapping.keys)
        assertEquals(FunctionConfigMapping("parent", 4), mapping["CHAT"])
        assertEquals(FunctionConfigMapping("child", 2), mapping["SUBAGENT"])
        assertEquals(FunctionConfigMapping(), mapping["PLAN_GENERATION"])
        assertEquals(FunctionConfigMapping(), mapping["PLAN_EXECUTION"])
    }

    @Test
    fun savesAllThreeIndependentConfigAndModelSelections() {
        val mapping = normalize("""{"PLAN_GENERATION":{"configId":"plan","modelIndex":1},"PLAN_EXECUTION":{"configId":"execute","modelIndex":2},"SUBAGENT":{"configId":"child","modelIndex":3}}""")
        assertEquals(FunctionConfigMapping("plan", 1), mapping["PLAN_GENERATION"])
        assertEquals(FunctionConfigMapping("execute", 2), mapping["PLAN_EXECUTION"])
        assertEquals(FunctionConfigMapping("child", 3), mapping["SUBAGENT"])
    }

    @Test
    fun removedStageConfigResetsOnlyThatStageToDefault() {
        val mappings = mapOf(
            FunctionType.PLAN_GENERATION to FunctionConfigMapping("plan", 1),
            FunctionType.PLAN_EXECUTION to FunctionConfigMapping("execute", 2),
            FunctionType.SUBAGENT to FunctionConfigMapping("child", 3),
        )
        val repair = remapDeletedConfigReferences(mappings, "execute")
        assertEquals(listOf(FunctionType.PLAN_EXECUTION), repair.affectedFunctions)
        assertEquals(FunctionConfigMapping(), repair.mapping[FunctionType.PLAN_EXECUTION])
        assertEquals(mappings[FunctionType.PLAN_GENERATION], repair.mapping[FunctionType.PLAN_GENERATION])
        assertEquals(mappings[FunctionType.SUBAGENT], repair.mapping[FunctionType.SUBAGENT])
    }

    private fun normalize(raw: String): Map<String, FunctionConfigMapping> {
        val preferences = mutablePreferencesOf(FunctionalConfigManager.FUNCTION_CONFIG_MAPPING to raw)
        FunctionalConfigManager.migratePreferencesFromVersionZero(preferences)
        return Json.decodeFromString(requireNotNull(preferences[FunctionalConfigManager.FUNCTION_CONFIG_MAPPING]))
    }
}