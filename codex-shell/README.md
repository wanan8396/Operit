# Operit Codex thin shell (standalone build)

This is an independent, minimal Android entry point in the Operit repository, not the existing Operit Compose chat screen. It deliberately does **not** depend on `:app`, `EnhancedAIService`, package/MCP managers, prompt hooks, tool parsers, native inference engines, or the legacy agent loop. The existing Operit application is unchanged. UI parity with the existing Operit application is not yet implemented.

## Features

- Send only the current user message and optional Codex `thread_id`.
- SSE text updates, Markdown display, backend progress-event labels.
- Local session history in app-private storage; new/switch session.
- Stop closes the SSE connection; backend interrupts the Codex turn after detecting disconnect.
- Disconnect before explicit successful `done` is an error, not completion.
- Backend access token stays in memory. OpenAI API key belongs only on backend.
- Remote endpoints require HTTPS; cleartext is restricted in the client to `localhost`/`127.0.0.1`.

## Build

JDK 21, Gradle 8.13, Android SDK platform 36 (build tools 36.0.0).

```sh
gradle -p codex-shell testDebugUnitTest assembleDebug
```

Run from repository root. This independent build does not run the legacy root Gradle build. GitHub Actions `Codex Shell APK` supplies the pinned Gradle version and uploads the debug APK.

Application ID: `com.ai.assistance.operit.codexshell` (installs beside the existing Operit app).

## Connect

1. Run `wanan99123/operit-codex-backend` in Linux/Ubuntu/PRoot.
2. In the shell, set `http://127.0.0.1:8088/v1/chat/stream` for a backend running on the same device. A remote service must be behind HTTPS.
3. Enter the backend's `CODEX_SERVER_TOKEN` if configured. This is **not** the provider API key.
4. Send a message. `thread_id` is stored on receipt and reused for the next turn. A new session deliberately sends no old history or Operit prompts.

## Current limitations

- Text only; no attachments, voice, device tools, automatic server installation/startup, or background foreground-service runtime.
- Backend must run separately; PRoot is not equivalent to a security sandbox.
- Interactive Codex permission/input requests currently fail closed. They are not silently approved; a later version can add an explicit approval UI.
- Activity destruction cancels the active request; running state is not restored as an active turn.
- History is local and unencrypted; Android backups are disabled. Protect the device accordingly.
- No actual provider inference/end-to-end device test is implied by the fake-server regression tests.
