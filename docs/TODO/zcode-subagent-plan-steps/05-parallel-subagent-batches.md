# Parallel subagent batches

## Root cause

Removing the old Semaphore(2) removed an admission quota, but did not change the single-task request protocol. A parent which calls run_subagent, waits for its result, then calls it again still launches tasks sequentially. The tool scheduler can only overlap calls submitted together. Some providers also restrict a model response to a single tool call.

A second independent gate existed in MultiServiceManager: each model configuration may have maxConcurrentRequests set to 1, and RequestConcurrencyRegistry shares that semaphore across all services for the same config ID.

## Implementation

Add run_subagents as an additive native tool. Its tasks parameter is a nonempty JSON array string of standard subagent requests. Validate the whole array before launching anything, then create all async children inside one parent coroutineScope and awaitAll only after submission. Use the same streaming StandardSubagentTool for each child so model selection, task budget, timeout, permissions, progress and cancellation cleanup stay unchanged. Do not call the synchronous invoke bridge.

The batch runner has no child-count limit or semaphore. Parent cancellation joins all child cleanup. Each ordinary child failure is returned alongside successful siblings in submission order, not raised as a failure which discards the other results. The batch tool returns status completed or completed_with_errors, totalAgents, succeeded, failed, totalDurationMs and results. ToolResult.success describes delivery of the entire batch; inspect each result.success to determine child success.

Delegated EnhancedAIService instances select ModelRequestConcurrencyPolicy.UNRESTRICTED. Ordinary chat/function services keep CONFIGURED. This removes the model-config concurrency gate only for delegated requests, without editing saved settings. Requests-per-minute limits are still shared and respected; API-side throttling and device resources are unchanged.

The single run_subagent protocol remains available. Both tools are denied to children by SubagentPolicy, including proxy-resolved names. The parent prompt tells the model to group independent tasks in one run_subagents call. UI summaries retain the localized 子智能体 label, and the existing progress panel shows each running child separately. For custom role-card tool allowlists, enable run_subagents in tool permissions.

## Example

The native tool call takes a JSON array encoded in tasks:

```json
{
  "tasks": "[{\"description\":\"Inspect UI\",\"prompt\":\"Read the UI files and identify the settings entry point. Do not edit files.\",\"subagent_type\":\"Explore\"},{\"description\":\"Inspect tests\",\"prompt\":\"Read existing tests and summarize missing coverage. Do not edit files.\",\"subagent_type\":\"Explore\"},{\"description\":\"Inspect documentation\",\"prompt\":\"Read developer documentation and summarize build constraints. Do not edit files.\",\"subagent_type\":\"Explore\"}]"
}
```

Do not use a tasks array for dependent jobs or conflicting writes. Waiting for the final batch result is not detached background execution. run_in_background=true remains invalid.

## Verification

SubagentBatchRunnerTest holds six child flows behind independent gates and asserts all six have started before releasing any gate. Releasing gates in reverse order verifies actual overlap and ordered result delivery. Further tests start two batches totalling 40 children, verify failure isolation, and cancel 12 active children to verify every finally block completes. These are deterministic coroutine tests, not wall-clock speed comparisons.

SubagentBatchRequestTest covers 100 child requests without an arbitrary batch-size quota, types, required fields, invalid profiles, unsupported parameters and background rejection. SubagentBatchOutputTest checks successful and failed entries are both retained. ModelRequestConcurrencyPolicyTest checks a model limit of 1 still applies to normal requests but not to delegated services. Existing lifecycle and permission tests remain selected in CI.

GitHub Actions runs :app:testReleaseUnitTest with these classes, then :app:packageRelease. Application ID remains com.ai.assistance.operit. No local Gradle or APK download is used. Runtime acceptance: submit three independent tasks in one call and observe three running rows before any completes; cancel the parent and verify all rows terminate. Keep request-rate settings high enough for the intended test rate or set their documented unlimited value, 0, when configuring a local test provider.

[DONE] Implementation and regression tests. Cloud and device results are recorded separately; no live overlap result is claimed before verification.
