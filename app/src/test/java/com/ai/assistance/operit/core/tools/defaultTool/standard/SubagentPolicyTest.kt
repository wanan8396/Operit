package com.ai.assistance.operit.core.tools.defaultTool.standard

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SubagentPolicyTest {
    @Test fun parentRetainsTools() {
        assertNull(SubagentPolicy.denial("run_subagent", false, null))
        assertNull(SubagentPolicy.denial("run_subagents", false, null))
        assertNull(SubagentPolicy.denial("update_plan", false, null))
    }

    @Test fun childrenCannotDelegateOrModifyParentPlan() {
        for (name in listOf("run_subagent", "run_subagents", "pkg:run_subagent", "pkg:run_subagents", "Agent", "Task",
            "update_plan", "EnterPlanMode", "ExitPlanMode")) {
            assertNotNull(SubagentPolicy.denial(name, true, SubagentProfile.GENERAL_PURPOSE))
        }
    }

    @Test fun exploreAllowsReadsAndRejectsWriteAndPackagePaths() {
        for (name in SubagentPolicy.exploreTools) {
            assertNull(SubagentPolicy.denial(name, true, SubagentProfile.EXPLORE))
        }
        for (name in listOf("edit_file", "delete_file", "download_file", "use_package",
            "package_proxy", "super_admin:terminal", "untrusted:read_file")) {
            assertNotNull(SubagentPolicy.denial(name, true, SubagentProfile.EXPLORE))
        }
        assertNull(SubagentPolicy.denial("edit_file", true, SubagentProfile.GENERAL_PURPOSE))
    }
}
