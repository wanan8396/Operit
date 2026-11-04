# Current subagent round

## Display contract

Each valid run_subagents batch starts one new progress round before launching its children. Each standalone run_subagent starts its own round. Starting a round removes all previous cards in that chat, including completed, failed, timed-out and cancelled cards. Children in the same batch share the round and do not clear each other. Other chats are unaffected.

Round identity is internal, not a user tool parameter. Delayed starts from a superseded or cleared round are ignored. Tool events and finalizers update only existing agent IDs, so they cannot recreate removed cards. Clearing the display does not cancel prior execution or delete historical tool results from conversation history.

## Failure handling

A child failure is returned to the parent with its error. Child timeout closes its service and reports timed_out. A batch awaits all children and preserves individual successes and errors in submission order; an ordinary child failure does not cancel siblings. Parent cancellation cancels the whole batch and performs child cleanup. Execution failures and child timeouts now retry until success or parent cancellation, as specified in [automatic retry](10-subagent-retry-until-success.md). No rollback of child file changes is performed.

## Verification

- [DONE] Start rounds at valid single/batch invocation boundaries
- [DONE] Remove terminal-card history accumulation
- [DONE] Test round replacement, same-batch preservation, chat isolation and stale events
- [PENDING] Cloud Release unit tests and packaging
- [PENDING] Device verification: finish or fail one batch, launch a second batch and confirm only second-batch cards remain
