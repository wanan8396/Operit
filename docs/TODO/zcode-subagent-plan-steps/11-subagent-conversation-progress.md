# Subagent tasks in the conversation

The header checklist already shows current-round child progress, but the conversation previously required opening that menu or reading raw delegation results to know each child's task.

The shared ChatArea scroll container now displays a current-round task list below the latest conversation messages for both bubble and cursor styles. Each row shows the delegation description, localized state, attempt count after a retry and the active tool name when available. It reads the same session-isolated SubagentProgressStore as the checklist menu. No child reasoning or raw tool output is copied into message history, prompt context or exports.

Running and retrying tasks have white circle outlines. Completed tasks have green circle outlines. Cancellation is neutral, and terminal failures use the error color. A dark disk behind the circle preserves contrast on light themes and wallpaper. Status labels use the theme text color rather than white text on a light surface.

Rows are keyed by agent ID. Retries update the existing row, successful siblings remain completed and a new delegation round replaces the previous rows. Progress updates participate in the existing auto-scroll logic; scrolling away is still respected. An older history window does not display current-round tasks beside old messages.

## Verification

- [DONE] Connect live progress to the shared conversation renderer without changing message content
- [DONE] Add explicit status projection tests, including unknown status rejection
- [DONE] Add store-to-display lifecycle regression: task names, active tool, retry identity, completion, new round, late events, session isolation and cancellation
- [DONE] Add SubagentConversationStatusTest to the selected Release CI test suite
- [PENDING] GitHub Actions Release unit tests and packaging for this revision
- [PENDING] Device acceptance for bubble/cursor styles, light/dark themes and wallpaper
- [PENDING] Device acceptance for task visibility, white-to-green transitions, retry attempts, new-round replacement and manual-scroll preservation

No local Gradle build or APK download is performed. JVM lifecycle tests do not verify pixel colors or Android runtime composition; cloud build results and device acceptance must be reported separately.
