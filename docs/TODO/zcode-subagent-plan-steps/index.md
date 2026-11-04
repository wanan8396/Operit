---
fork: https://github.com/wanan99123/Operit
branch: feat/plan-steps-and-subagents
status: implementation-complete-validation-pending
---

# ZCode subagent and plan progress alignment

The previous native subagent API accepted task/context_text and returned a flat summary. This development branch changes the contract to ZCode's synchronous Agent shape while keeping Operit's finite execution budgets.

## Scope

- Remove the added bottom token meter; retain the existing header token gauge.
- Add a checklist button immediately left of the header token gauge.
- Expose session-isolated update_plan/read_plan tools and show step progress.
- Accept description/prompt/subagent_type/run_in_background, with general-purpose and Explore profiles.
- Reject background execution and nested delegation. Explore is enforced as read-only before execution.
- Return completed agentId/agentType/description/prompt/content/totalToolUseCount/totalDurationMs/totalTokens.
- Mirror bounded child-tool lifecycle metadata into the parent-session progress panel, without raw output or reasoning.
Subagents now use the independent SUBAGENT functional model mapping. The functional configuration screen exposes its configuration, model selection and connection test. Parent role, permissions and workspace remain inherited; both general-purpose and Explore use the selected child model.


## Parallel execution

Multiple independent tasks now use one `run_subagents` call. All child flows start before results are awaited, and only delegated model services ignore the model-config concurrency gate. The single-task API is unchanged. See [Parallel subagent batches](05-parallel-subagent-batches.md) for the protocol, failure handling and deterministic overlap tests.

## Independent plan models

The functional model settings now expose separate Plan Generation, Plan Implementation and Subagent selections. The checklist menu offers explicit Generate Plan and Implement Plan actions that route actual requests through the chosen stage model. Unset stages use the default configuration at model index 0. See [Independent planning and implementation models](06-independent-plan-models.md) for routing and verification.

## Review

The checklist menu also offers Review, which uses the exact same model configuration and index as Plan Generation. No separate reviewer model is introduced. It reviews nonempty plans, including completed plans, against conversation and workspace evidence without requesting automatic edits. See [Review using the planner model](07-review-with-planner-model.md).

## Verification

Release unit tests and :app:packageRelease run in GitHub Actions. No local Gradle or APK download is required. The application ID remains com.ai.assistance.operit. Without the matching release key, the APK is unsigned and cannot be installed or used for an in-place upgrade.

Implementation is complete. Release unit tests passed in cloud run 37972771750; packaging was blocked by an obsolete Japanese translation. See [Release lint validation](02-release-lint-validation.md) for the fix and verification record. Packaging after the fix and runtime UI/cancellation checks remain pending.

## Automatic phase routing iteration

The development branch now uses [automatic phase model routing](08-automatic-phase-model-routing.md), which supersedes the stage menu actions in 06 and 07. The interface retains only checklist and subagent progress. Cloud and device validation are recorded separately.

[Current subagent round](09-current-subagent-round.md) defines replacement of prior progress cards and existing failure/cancellation behavior.

[Retry until success](10-subagent-retry-until-success.md) supersedes fail-once delivery for valid child executions.

[Conversation progress](11-subagent-conversation-progress.md) adds current-round task descriptions and white/green state circles directly to the conversation.
