---
title: Android fork build and test repair
status: draft
fork_repository: "https://github.com/wanan8396/Operit"
---

# Android fork build and test repair

## Observed failures

Release assembly completed in Actions run 41770087817, but its finalizer expected
`app-release.apk` instead of the unsigned APK produced without publisher keys.
The workflow does not configure publisher keys or a rotation lineage.

Actions run 41770087338 also failed to compile JVM tests: a test directly invoked
a protected request builder, and xAI tests referenced removed mapper functions.

## Changes

- Read APK filenames from AGP built-artifact metadata.
- Attach automatic rotation signing only when `APK_ROTATION_*` settings exist.
  Explicit rotation tasks continue to require every key and lineage property.
- Keep the production request builder protected and use the existing reflective
  test pattern.
- Test xAI through the current configuration registry and request applier,
  including an unsupported-model case.

## Verification

Run `:app:assembleRelease` without publisher keys and check that Actions uploads
`app-release-unsigned.apk`. This artifact needs signing before installation.

Run `:app:testDebugUnitTest` and inspect the uploaded test report.

Publisher rotation must additionally be verified with private signing material:
run `:app:assembleRelease` and `:app:assembleNightly` with the complete
`APK_ROTATION_*` settings, then use `apksigner verify --verbose --print-certs`.
Private publisher signing is not exercised by the fork workflow.
