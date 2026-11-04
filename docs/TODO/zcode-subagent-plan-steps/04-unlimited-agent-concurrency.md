# Unrestricted subagent concurrency

Remove the global Semaphore(2) and all slot acquisition/release operations. There is no application-level agent-count or concurrency quota. Tool batches already launch run_subagent calls concurrently. Provider limits and device resources still apply.

Chat tool summaries and parameter-dialog titles display the localized Subagent label. Protocol names run_subagent and run_in_background remain unchanged; background execution is not enabled. Per-task timeout, tool budget, permissions and parent-scoped cancellation remain enforced. The progress store retains bounded completed history, not a launch quota.

[DONE] Implementation. Release CI and runtime validation pending.
