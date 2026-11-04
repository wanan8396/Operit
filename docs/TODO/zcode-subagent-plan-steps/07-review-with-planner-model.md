# Review using the planner model

## Scope

Add Review after Generate Plan and Implement Plan in the header checklist menu. Review always uses the same PLAN_GENERATION configId and modelIndex as planning. Do not add a review FunctionType, independent configuration row or persisted review mapping. Changing the planner selection also changes the reviewer on the next request. An unset planner selects default configuration index 0 for both operations.

## Execution

PlanModelStage.REVIEW maps directly to FunctionType.PLAN_GENERATION. The existing MessageCoordinationDelegate stage routing snapshots that binding before computing request context settings. It submits the review through the full parent chat pipeline, retaining conversation history, role permissions and workspace.

Review is available for any nonempty plan, including fully completed plans, and disabled while the conversation is busy. ChatViewModel rechecks the same conditions before sending. The review prompt includes the current checklist and requests evidence-based read-only inspection of the plan, implementation, changed files and existing verification records. It asks for severity, evidence/locations, fixes and regression checks, or the examined scope and unverified areas when no finding is identified. It does not request implementation, delegation or checklist changes. This is review-task instruction within existing tool permissions, not a new security sandbox.

No automatic review or automatic fixes are added. Users explicitly choose Review after planning or implementation.

## Verification

Extend PlanModelRoutingTest to assert the REVIEW function identity, exact equality with the planner config/index, effect of changing the planner, default selection when the planner is unset, and independence from implementation and child models. The existing CI test selection already includes this class.

Device acceptance: choose planner A at a nonzero model index, implementer B and child C; review a completed checklist and confirm the review request model is A at the same index as planning. Change only A and review again to confirm immediate use of the new selection. Remove the planner selection via Use Default Model and confirm both generation and review select default index 0. Empty plans and busy conversations must disable Review. Cloud compilation and device evidence are reported separately.

[DONE] Menu, request prompt, shared routing and regression tests implemented. Release CI pending.