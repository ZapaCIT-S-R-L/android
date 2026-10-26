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
`gradle/verification-metadata.xml`. After adding or bumping a dependency (including Dependabot PRs), refresh it:

```sh
./scripts/update-verification-metadata.sh
```

Review the diff: only the artifacts you changed should appear.

## Contributor License Agreement

This project is licensed under the [AGPL-3.0](LICENSE). Before we can merge your first pull request, you sign the
[ZapaKid Contributor License Agreement](CLA.md): add a row with your GitHub username to
[CLA-SIGNATURES.md](CLA-SIGNATURES.md) in that pull request. The `cla` check confirms it. You keep the copyright in
your contribution, and ZapaCIT SRL can distribute it, including through the app stores.
