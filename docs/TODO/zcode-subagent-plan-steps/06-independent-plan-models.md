# Independent planning and implementation models

## Previous behavior

The checklist button only displayed PlanStepStore progress. update_plan stores a checklist without making a model request. Adding a model selector alone would therefore leave the actual parent request on its ordinary chat model.

## Model bindings

Append PLAN_GENERATION and PLAN_EXECUTION to FunctionType after SUBAGENT. Keep all existing enum positions and persisted mapping keys unchanged. The functional model configuration page now has independent Plan Generation, Plan Implementation and Subagent rows, each with configuration/model selection and connection testing.

Unset entries use the existing default binding: configId default, modelIndex 0. Each of the three rows includes Use Default Model to explicitly restore that binding. Choosing another model within the default configuration is still an explicit selection and preserves its model index. Do not infer a stage model from the chat or sibling stage, and do not reset existing subagent selections.

## Actual execution

Add Generate Plan and Implement Plan actions to the header checklist menu. Generate Plan uses the current composer request, or the latest user message when the composer is empty, and asks the selected planner to create a pending checklist via update_plan. It does not automatically implement the plan. Implement Plan submits the existing checklist and asks the implementation model to execute it, update progress and delegate independent work using one parallel run_subagents call. Each delegated child still reads the independent SUBAGENT mapping.

ChatViewModel passes an explicit PlanModelStage to MessageCoordinationDelegate. The delegate snapshots the corresponding functional mapping before computing context settings, then uses the full CHAT pipeline with explicit configId/modelIndex overrides. This ensures both model requests and context limits refer to the selected stage configuration, while role permissions, memory-space identity, history and workspace remain in the existing parent pipeline. Stage sends bypass group-response orchestration so another model does not replace the user-selected stage. Ordinary sends remain unchanged. Child-tool budgets, timeouts and cancellation remain unchanged.

Actions are disabled while the conversation is busy. Generate Plan requires a request, and Implement Plan requires an unfinished checklist. The view model rechecks these conditions before sending. Generation and implementation are explicit actions; changing checklist statuses does not silently switch models. The planning-only behavior is instructed in the stage request, not an additional sandbox guarantee. Existing user tool permissions still apply.

## Validation

PlanModelRoutingTest verifies independent config IDs/model indices, unset-stage default selection, and explicit nonzero indices in the default config. FunctionalConfigPlanMappingTest verifies existing settings gain the two default entries, all three selections remain separate, and deleting an implementation configuration resets only that stage. FunctionTypeTest verifies appended identities and existing ordering.

Runtime acceptance: configure planner A, implementer B and child C; enter a task and choose Generate Plan, then choose Implement Plan. Check provider/model metadata and requests: planning uses A, implementation uses B and delegated child requests use C. Clear one selection with Use Default Model and verify only that function uses default index 0. Multiple independent child tasks must appear running together before completion. These runtime checks remain pending until a built APK is tested.

GitHub Actions runs the added mapping/routing tests with the parallel regression tests, followed by :app:packageRelease. Keep application ID com.ai.assistance.operit and do not download Gradle or APK locally.

[DONE] Settings, explicit stage routing, actions and regression-test selection. Cloud result recorded after submission.