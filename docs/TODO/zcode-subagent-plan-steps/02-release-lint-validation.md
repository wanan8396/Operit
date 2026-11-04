# Release lint validation

Cloud run [37972771750](https://github.com/wanan99123/Operit/actions/runs/37972771750) tested commit 170dc07e654a3a47e905e55d8fbba9a34b03f3ec. The Test plan and subagent contracts step passed. Release packaging then failed at :app:lintVitalRelease with one ExtraTranslation error.

The Japanese resource follow_chat_model_for_all_functions had no default-locale definition and no code references. Remove that unused translation rather than disabling Release lint or introducing a new unused default string. This preserves existing model-selection behavior and the application ID com.ai.assistance.operit.

Verification: confirm the obsolete key is absent from application sources and resources, run git diff --check, and push the fix to trigger the existing Release unit tests and :app:packageRelease in GitHub Actions. Cloud packaging after this fix and device UI/cancellation checks remain pending.

[DONE] Obsolete resource removed. Release validation is pending the new cloud run.
