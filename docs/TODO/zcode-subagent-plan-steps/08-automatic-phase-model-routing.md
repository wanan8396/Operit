# Automatic phase model routing

This development iteration supersedes the explicit menu actions described in 06 and 07. The user requests automatic routing without Generate Plan, Implement Plan or Review buttons.

## Request contract

- The first user request in a new chat uses the default configuration at model index 0.
- Later ordinary chat uses the ordinary composer or role-card binding.
- Planning and review use the same PLAN_GENERATION configuration and index.
- Implementation uses PLAN_EXECUTION.
- Delegated executions use their SUBAGENT override and never inherit the parent phase.
- Unset stage selections use the default configuration at model index 0 as requested.

## Automatic transition

The session-scoped update_plan tool accepts optional model_stage: generation, implementation, review or chat. The agent sets the phase in a standalone call before doing the corresponding work. It can bootstrap planning with todos=[] and model_stage=generation, so the next model request generates the actual pending checklist using the planner model.

Existing callers that omit model_stage derive the phase from the complete checklist: empty means chat, all completed means review, any in_progress means implementation, otherwise generation. An explicit phase has precedence over checklist progress. Invalid phase or checklist input is rejected before either store changes. Child agents cannot update the parent plan or phase.

The model execution context reads the live phase before each model hop, including tool-result continuations within one user turn. It compares the resulting configuration and model index with its current service lease. On a changed target it closes only its own old lease, acquires the stage service, refreshes provider-specific system/tool instructions and uses the selected model's context limits. Conversation and tool-result history remain shared across model hops.

The previous implementation returned the initial cached snapshot on every hop. That pinned all later work to the composer model despite separate stage settings. The new path resolves the actual target before accepting a cached lease. Each hop acquires a current lease before accepting its cached snapshot, comparing configuration, selected index and model parameters as well as mapping. Configuration edits also invalidate custom service cache entries without interrupting already leased requests. Pre-send summary checks use the current phase context window as well.

## Interface

The checklist panel retains completed counters, current step, the full checklist and child-agent progress. The three model-stage action buttons and their request templates are removed. No user button is needed to change phases.

The saved assistant message shows the final model actually used by the streamed turn. Debug logs record chat id, stage, configuration id, index and provider/model at every changed target. One streamed turn can contain several models; the final label does not represent all earlier hops.

## Validation checklist

- [DONE] Remove stage buttons, callback parameters and sendPlanStage request construction
- [DONE] Introduce session-scoped phase state and optional tool protocol
- [DONE] Re-evaluate target before every model hop and refresh provider instructions
- [DONE] Keep independent child model routing and leased-service cancellation boundaries
- [DONE] Add AutomaticPlanModelRoutingTest, PlanModelStageStoreTest and PlanModelStageValidationTest
- [DONE] Include new routing test in Release CI selection
- [PENDING] Cloud :app:testReleaseUnitTest and :app:packageRelease
- [PENDING] Device validation with different default, composer, planner, implementation and child models

## Device verification

Use distinct models for each setting. Start a new chat with a multi-step request. Confirm the first request log names the default model. Let the agent enter generation, implementation and review; compare each Automatic model route log with the corresponding setting and verify that switching happens inside the same tool loop without menu interaction.

Verify a later user continuation remains on the current phase, review uses the planner's exact index, model_stage=chat returns later hops to the ordinary binding, and another chat has independent phase state. Launch a child batch during implementation and confirm each child uses SUBAGENT, not PLAN_EXECUTION. Finally, change a stage mapping and confirm the next model request uses it without restarting the app.

No local Gradle or APK download is required. Package identity remains com.ai.assistance.operit. Cloud and device validation results must be recorded separately from source inspection.
