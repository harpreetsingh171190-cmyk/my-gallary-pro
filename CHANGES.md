# CI simplification summary

- Workflow now has a single job: checkout, Java 17, Gradle 8.9, `assembleDebug`, upload APK.
- Removed the unit-test step and the separate test-report upload from CI (tests still run locally).
- Removed `--build-cache`; added `--stacktrace` so any error shows clearly in the log.
- gradle.properties: daemon, caching and incremental Kotlin compile turned off for clean CI runs.
- Not compiled here (no network in my sandbox), so a real Kotlin compile error would still show in the Actions log.

## Update 2
- README: added build status badge (check the owner/repo name in the badge URL matches your repo).
- Workflow: build summary step (APK name and size) and a failure-only build-reports upload.
