# Built-in Codex app-server

Operit packages the official OpenAI Codex `codex-app-server` release, not a Rust build and not `libcodex.so`. CI pins `rust-v0.162.1`, downloads the official `codex-app-server-aarch64-unknown-linux-musl.tar.gz`, verifies the GitHub release API SHA-256 digest, archive contents, non-empty ELF and AArch64 machine type, and records provenance in `app/src/main/assets/codex/codex-app-server.json`. Apache-2.0 `LICENSE` and `NOTICE` are fetched from the matching upstream tag; failure to fetch either fails preparation rather than creating placeholder notices. The archive basename is selected by target prefix, not an assumed exact archive name.

The integration is **Responses API-only**: configuration uses provider `operit`, `env_key = OPERIT_CODEX_API_KEY`, `requires_openai_auth = false`, `wire_api = responses`, and an explicit `base_url`. CI's Linux x86_64 smoke starts the official server with `--listen stdio://` and a temporary `CODEX_HOME/config.toml`, then verifies `initialize` and `initialized`; it makes no real model or paid-API request.

CI prepares the aarch64 asset for Android packaging and separately fetches the x86_64 release asset to `$RUNNER_TEMP` for Linux host protocol smoke. The smoke validates `initialize` (without JSON-RPC error), `initialized`, and `thread/start` using a temporary provider named `operit`, `env_key = OPERIT_CODEX_API_KEY`, and `requires_openai_auth = false`; it makes no paid API request.

There is currently no Android-device/PRoot end-to-end integration validation. The smoke is host-binary protocol verification only, and does not claim all Operit tools are wired into Codex.
