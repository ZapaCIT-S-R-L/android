# ZapaKid for Android

End-to-end encrypted family chat (text, photos, voice) between parents and their kids, and between connected
families. One app with a parent mode and a kid mode. ZapaKid can never decrypt the chats.

Kotlin, Jetpack Compose, minSdk 26. Licensed under the [AGPL-3.0](LICENSE).

## Build and test

Requirements: JDK 21 and the Android SDK (`local.properties` with `sdk.dir=...`, not committed).

```sh
./gradlew assembleDebug              # debug APK
./gradlew testDebugUnitTest          # JVM unit tests
./gradlew connectedDebugAndroidTest  # Compose UI / instrumented tests, needs an emulator
./gradlew lintDebug ktlintCheck detekt
npm test                             # repository scripts (CLA check, release version, OSV gate); Node 24
```

CI runs all of these on every pull request, the UI tests on an emulator (see `.github/workflows/ci.yml`).

## Security

- Dependencies are pinned exactly in `gradle/libs.versions.toml` and checked against
  `gradle/verification-metadata.xml` (Gradle dependency verification). OSV-Scanner runs on every PR and daily.
- Every release has a CycloneDX SBOM attached.
- No backups of app data (`allowBackup=false`); HTTPS only with system certificate authorities.
- Report vulnerabilities privately: see [SECURITY.md](SECURITY.md).

## Releases

Every merge to `main` is tagged `vMAJOR.MINOR.PATCH`: patch by default, minor with the `minor` label on the PR,
major with the `major` label.
