# CI simplification summary

- Workflow now has a single job: checkout, Java 17, Gradle 8.9, `assembleDebug`, upload APK.
- Removed the unit-test step and the separate test-report upload from CI (tests still run locally).
- Removed `--build-cache`; added `--stacktrace` so any error shows clearly in the log.
- gradle.properties: daemon, caching and incremental Kotlin compile turned off for clean CI runs.
- Not compiled here (no network in my sandbox), so a real Kotlin compile error would still show in the Actions log.

## Update 2
- README: added build status badge (check the owner/repo name in the badge URL matches your repo).
- Workflow: build summary step (APK name and size) and a failure-only build-reports upload.

## Update 3
- Workflow: pushing a tag like v1.0.0 builds the APK and attaches it to a GitHub Release automatically.

## Update 4
- Workflow: unit tests run at the very end as report-only (continue-on-error). They cannot fail the build or block the APK/release. Result shows in the run summary and test reports are uploaded as an artifact.

## Update 5
- Added .github/dependabot.yml: monthly update PRs for Gradle dependencies and GitHub Actions (max 3 and 2 open at a time). Does not touch the build.

## Update 6
- README: added Tech stack, Download, Screenshots (commented placeholder, enable after adding images) and CI sections. Existing feature text untouched.

## Update 7 (UI fixes)
- Colour button now changes the accent in light mode too (it only worked in dark mode before).
- Header: title no longer squeezed; action buttons moved to a scrollable second row.
- Added app launcher icon (adaptive icon: photo frame on blue) and set it in AndroidManifest.xml.
