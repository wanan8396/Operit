# Official Codex engine with Operit API configuration

## Configure

1. Initialize Operit's Ubuntu terminal on an ARM64 device.
2. In model settings choose **Codex (API Key / Local Engine)** (`CODEX_API`), not the legacy ChatGPT OAuth provider.
3. Fill the existing API endpoint, API Key and model fields. Key-pool selection uses Operit's `ApiKeyProvider`; custom headers use child environment indirection.
4. The endpoint must implement the **OpenAI Responses API**. `https://host/v1` and `https://host/v1/responses` are accepted. A `/chat/completions`-only or Anthropic Messages endpoint is not compatible with the official engine.
5. Bind that model config to the relevant Operit function/chat model. This change does not overwrite unrelated user model configs.

There is no OAuth login step for `CODEX_API`. Legacy `OPENAI_CODEX` remains a separate backwards-compatible OAuth provider. The selected API provider/model must actually support the Responses operations used by Codex; an arbitrary existing key does not guarantee compatibility.

## Implementation and provenance

CI pins the official `openai/codex` release `rust-v0.162.1`, verifies the GitHub release archive SHA-256 digest and ELF architecture, and packages `codex-app-server-aarch64-unknown-linux-musl` as an APK asset, with upstream LICENSE/NOTICE and provenance metadata. It is an executable, **not a JNI library**, and Operit is compiled in GitHub Actions. This is not a from-source Android Rust build.

The private app-server runs through Operit's existing PRoot/Ubuntu environment with stdio JSON-RPC. A private CODEX_HOME config selects provider `operit`, `wire_api = responses`, `env_key = OPERIT_CODEX_API_KEY`, `requires_openai_auth = false`. The key is injected into the child environment, not command arguments or config.toml. Each service owns its own process, and each request rehydrates conversation history into a new ephemeral thread; there is no hardcoded global active-chat thread.

Subscriptions are established before `turn/start`. RPC calls time out and fail when the process exits. The stream ends on the matching `turn/completed`, cancellation issues `turn/interrupt`, and process release fails outstanding requests. There is no simulator fallback or fabricated model list.

## Verification gates

CI checks download provenance and exercises a real official x86_64 app-server with `initialize`, `initialized` and `thread/start` under an isolated API-key provider. No actual model inference or paid API call is made by this smoke. Android Gradle/APK compilation is a separate gate. A successful Linux smoke is **not** Android end-to-end validation.

Device verification remains necessary: app-process PRoot permissions, server startup, DNS/TLS inside Ubuntu, API endpoint/key authorization, streamed inference, cancellation, separate-chat history and repeated service release. If the Ubuntu terminal cannot resolve the configured API host, correct that environment first; CI cannot validate device DNS.

## Current scope / incomplete work

- Default thread sandbox is read-only. Native approval requests are declined until a native Android approval UI is implemented; other unsupported server requests receive a JSON-RPC error. PRoot is not claimed to be a security sandbox.
- Operit's Android/UI tools and packages are **not yet exported as Codex MCP or dynamic tools**. This is an engine/provider integration, not a completed replacement of the whole Operit agent/tool framework.
- Only text/history is currently bridged. Images/audio/attachments, persistent thread resume, thinking separation, parameter mapping and full usage accounting need follow-up work.
- No production credentials are placed in CI or the APK. API-model authentication is only testable on the configured device/provider.

<!-- Build trigger for full Operit APK with embedded Codex CLI: 2026-10-15 -->
