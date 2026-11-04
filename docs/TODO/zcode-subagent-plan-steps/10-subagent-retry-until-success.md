# Retry subagents until success

Valid child executions retry failures and per-attempt timeouts without a total attempt limit. Invalid delegation parameters are rejected before a child starts, not retried. Parent cancellation propagates immediately and interrupts retry waits. A new service and fresh finite tool-call/time budget are created for every attempt. Retry delays increase from 1 to 30 seconds to avoid a tight error loop.

The same agent ID and round are retained. The card shows retry state, attempt count and latest failure; the next attempt resets only its tool progress. Successful sibling tasks are not restarted. A batch awaits all children until success or parent cancellation. Starting a later round still removes previous cards; retry events cannot recreate absent cards.

Retry prompts include the previous error and any available final summary, directing the agent to inspect workspace outputs and continue only unfinished work. Files are not rolled back and side effects are not guaranteed idempotent. Permanent provider/configuration errors will continue retrying until corrected or cancelled, and repeated requests may consume billed tokens.

- [DONE] Implement cancellable unlimited attempt loop and per-attempt cleanup
- [DONE] Retain current-round card identity and show attempt/failure state
- [PENDING] Release CI tests and package
- [PENDING] Device acceptance: fail then recover, timeout then recover, cancel during retry wait, successful sibling runs once
