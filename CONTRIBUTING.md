# Contributing

Thanks for helping. Issues and pull requests are welcome.

- **Small pull requests**, one change each, with a clear description.
- **Tests first:** add or change the test that shows the change (`app/src/test` for JVM tests,
  `app/src/androidTest` for Compose UI tests), then the code. Every bug fix gets a regression test.
- **Style:** `./gradlew lintDebug ktlintCheck detekt` must pass (`./gradlew ktlintFormat` fixes most of it).
- **Accessibility:** every control has a content description or visible label, touch targets are at least 48dp,
  and screens work at 200% font size.
- **No credentials** in code, commits or issues: no keystores, no `google-services.json`, no tokens. CI scans every
  pull request with gitleaks.
- **Cryptography** comes only from libsignal and the Android Keystore. No home-made primitives.

## Dependencies

Versions are pinned exactly in `gradle/libs.versions.toml`. Gradle verifies every downloaded file against
`gradle/verification-metadata.xml` (SHA-256), so a new or bumped dependency fails CI until that file is refreshed.

**Your own change:** refresh it with the workflow below (preferred: it runs on Linux with an emulator, so it also
covers aapt2 for every platform and the emulator test runner), or locally with
`./scripts/update-verification-metadata.sh` (add `WITH_DEVICE=1` with an emulator running).

**Dependabot Gradle PRs:** refreshed by hand, by a maintainer. There is no bot with a write token that commits to
pull requests.

1. Actions → **verification-metadata** → Run workflow → choose the Dependabot branch.
2. Download the `verification-metadata` artifact when the run finishes.
3. Review the diff against the branch: only the bumped artifacts (and their new transitive dependencies) may
   change. Anything else is a reason to stop and look.
4. Commit it to the Dependabot branch (`git commit -m "Verification metadata for <bump>"`) and push. CI then runs
   again with strict verification.

Never commit a refreshed file you haven't reviewed, and never turn verification off (`--dependency-verification
lenient` / `off`) in CI.

**libsignal** comes only from Signal's Maven repository (`settings.gradle.kts`, exclusive to `org.signal`). To bump
it: change `libsignal` in `gradle/libs.versions.toml` to the new release tag of
[signalapp/libsignal](https://github.com/signalapp/libsignal/releases), refresh the verification metadata, and run
`./scripts/ci-local.sh`; CI also scans libsignal's Rust `Cargo.lock` at that tag with OSV-Scanner.

## Before every push

`./scripts/ci-local.sh` runs the CI checks on your machine (JDK 21, Node 24, osv-scanner, gitleaks, actionlint):
build, unit tests, lint, OSV, gitleaks, and the emulator tests on a local headless emulator (`scripts/emulator.sh`;
install it once with `sdkmanager "emulator" "system-images;android-37.0;google_apis;arm64-v8a"`). It refuses to start
the emulator next to an iOS build or simulator, or with a physical phone attached, and shuts it down after the run.
Push only when it passes.

## Contributor License Agreement

This project is licensed under the [AGPL-3.0](LICENSE). Before we can merge your first pull request, you sign the
[ZapaKid Contributor License Agreement](CLA.md): add a row with your GitHub username to
[CLA-SIGNATURES.md](CLA-SIGNATURES.md) in that pull request. The `cla` check confirms it. You keep the copyright in
your contribution, and ZapaCIT SRL can distribute it, including through the app stores.
