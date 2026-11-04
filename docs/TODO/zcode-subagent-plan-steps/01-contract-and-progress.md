# Synchronous contract and progress projection

The development-only task/context_text arguments are replaced with description/prompt and explicit general-purpose or Explore profiles. Operit retains max_tool_calls and timeout_seconds as bounded runtime extensions. run_in_background=true is rejected; no detached job is launched.

SubagentPolicy filters advertised tools and blocks execution, including resolved proxy targets. Children cannot delegate or update the parent plan. Explore allows only the enumerated native read/search tools, not arbitrary shells or packages.

The completed result is a single JSON object. Intermediate running results are not concatenated into the completed payload. Child tool lifecycle events use per-invocation IDs after parameter injection to avoid duplicate progress entries.

The tool-progress counter includes cancellation, failure and timeout terminal states. Once an agent finishes, late tool events are ignored so its displayed progress cannot regress.

The header checklist opens a bounded-height menu containing plan steps and session-specific child progress. Progress is in-memory metadata only and is removed when the chat is deleted or cleared. It is not durable plan mode, plan-file approval, or background-agent support.

Verification covers input bounds, profiles, policy restrictions, completed JSON fields, session isolation, bounded history, cancellation terminal states, and late events after clear. Cloud Release compilation and device UI checks are still pending.

[DONE] Implementation and test coverage. Validation is tracked separately.
