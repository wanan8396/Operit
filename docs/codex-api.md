# Built-in Codex app-server

Operit packages the official OpenAI Codex `codex-app-server` release, not a Rust build and not `libcodex.so`. CI pins `rust-v0.162.1`, downloads the official `codex-app-server-aarch64-unknown-linux-musl.tar.gz`, verifies the GitHub release API SHA-256 digest, archive contents, non-empty ELF and AArch64 machine type, and records provenance in `app/src/main/assets/codex/codex-app-server.json`. Apache-2.0 `LICENSE` and `NOTICE` are retained beside the binary.

The integration is **Responses API-only**: configuration uses provider `operit`, `env_key = OPERIT_CODEX_API_KEY`, `requires_openai_auth = false`, `wire_api = responses`, and an explicit `base_url`. CI's Linux x86_64 smoke starts the official server with `--listen stdio://` and a temporary `CODEX_HOME/config.toml`, then verifies `initialize` and `initialized`; it makes no real model or paid-API request.

PRoot and Android-device integration still require end-to-end validation. At this stage write operations are fail-closed when the protocol/server is unavailable; do not treat the CI smoke as phone execution verification.
