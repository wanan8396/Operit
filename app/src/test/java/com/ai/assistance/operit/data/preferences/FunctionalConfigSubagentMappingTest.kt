package com.ai.assistance.operit.data.preferences

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.ai.assistance.operit.data.model.FunctionType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class FunctionalConfigSubagentMappingTest {
    @Test
    fun normalizationAddsSubagentWithoutInheritingChatModel() {
        val mapping = normalize("""{"CHAT":{"configId":"parent","modelIndex":3}}""")

        assertEquals(FunctionType.values().map { it.name }.toSet(), mapping.keys)
        assertEquals(FunctionConfigMapping("parent", 3), mapping[FunctionType.CHAT.name])
        assertEquals(
            FunctionConfigMapping(FunctionalConfigManager.DEFAULT_CONFIG_ID, 0),
            mapping[FunctionType.SUBAGENT.name],
        )
    }

    @Test
    fun normalizationPreservesIndependentSubagentConfigAndModelIndex() {
        val mapping = normalize(
            """{"CHAT":{"configId":"parent","modelIndex":3},"SUBAGENT":{"configId":"child","modelIndex":2}}""",
        )

        assertEquals(FunctionConfigMapping("parent", 3), mapping[FunctionType.CHAT.name])
        assertEquals(FunctionConfigMapping("child", 2), mapping[FunctionType.SUBAGENT.name])
    }

    @Test
    fun deletingSubagentConfigLeavesParentMappingUnchanged() {
        val parent = FunctionConfigMapping("parent", 3)
        val repair = remapDeletedConfigReferences(
            mapOf(FunctionType.CHAT to parent, FunctionType.SUBAGENT to FunctionConfigMapping("child", 2)),
            "child",
        )

        assertEquals(listOf(FunctionType.SUBAGENT), repair.affectedFunctions)
        assertEquals(parent, repair.mapping[FunctionType.CHAT])
        assertEquals(FunctionConfigMapping(FunctionalConfigManager.DEFAULT_CONFIG_ID, 0), repair.mapping[FunctionType.SUBAGENT])
    }

    private fun normalize(raw: String): Map<String, FunctionConfigMapping> {
        val preferences = mutablePreferencesOf(FunctionalConfigManager.FUNCTION_CONFIG_MAPPING to raw)
        FunctionalConfigManager.migratePreferencesFromVersionZero(preferences)
        return Json.decodeFromString(requireNotNull(preferences[FunctionalConfigManager.FUNCTION_CONFIG_MAPPING]))
    }
}
