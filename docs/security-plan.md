# Android security plan (v1)

Order of priority: security, then cost, then availability. No external audit before launch, so all chat
cryptography stays inside libsignal; the app adds no primitives of its own (the session file uses the platform
Keystore's AES-GCM, nothing hand-made).

| Area | Status | Where |
| --- | --- | --- |
| Session token encrypted at rest, Keystore key (StrongBox when available) | Done | `auth/KeystoreSessionCipher.kt` |
| No backups (`allowBackup=false`, all data-extraction domains excluded) | Done | `AndroidManifest.xml` |
| HTTPS only, system CAs only, TLS 1.3 only on Android 10+ | Done | `network_security_config.xml`, `auth/Tls.kt` |
| Exact versions, Gradle dependency verification, OSV-Scanner on PRs and daily, SBOM per release | Done | `gradle/`, `.github/workflows/` |
| Chat database encrypted | Planned | below |
| Chat keys: libsignal, Keystore-wrapped, Block Store backup | Planned | below |
| FLAG_SECURE on chat screens | Planned | below |
| No chat content in logs, crash reports, notifications | Planned | below |
| Play Integrity | Planned | below |
| Safety numbers and key-change warning | Planned | below |

## Chat storage

- Room on SQLCipher (`net.zetetic:sqlcipher-android`). The 256-bit database passphrase is random, generated once,
  and stored only wrapped by a Keystore AES-GCM key (StrongBox when available). Not `setUnlockedDeviceRequired`:
  messages arriving while the phone is locked must still be decrypted for the notification. It is never derived
  from anything the server knows.
- libsignal's identity key, prekeys and sessions live in that encrypted database (libsignal's store interfaces).
- Media (photos, voice) are encrypted per file by the sender with libsignal attachment keys; on the phone they stay
  in app-private storage, decrypted only into memory for display/playback.

## Key backup (Android Block Store)

- `allowBackup=false` stays. The only backup is deliberate: the libsignal identity key pair and the database
  passphrase go to Block Store with `setShouldBackupToCloud(true)`, which Google stores end-to-end encrypted with the
  phone's screen lock. Only when `isEndToEndEncryptionAvailable()` is true; otherwise no cloud backup, and the new
  phone recovers keys from another family phone (QR transfer) or starts fresh.
- Signing in with Google/Apple alone never restores keys: the API holds none.

## No content leaks

- No chat text, names, or tokens in `Log` (a lint rule / detekt rule forbids `Log.*` with message arguments in chat
  code; release builds strip `Log.d/v/i` with R8).
- Crash reporting, if added, gets stack traces only, with no breadcrumbs or user data.
- Push payloads carry no content: a data message "something new" wakes the app, which fetches and decrypts.
  Notification text is generated on the phone; the lock-screen version hides the content
  (`VISIBILITY_PRIVATE` with a public version that says only "New message").
- `FLAG_SECURE` on every chat, media and key-verification screen (no screenshots, no recents thumbnail, not shown
  on casts). Kid mode applies it app-wide.

## Play Integrity (required on sign-in from launch; API endpoints are drafts in openapi.yaml)

1. `POST /v1/attestation/challenge` → `{challenge, expiresAt}` (single use, 5 minutes, no session needed).
2. The app requests a Standard Play Integrity token with `requestHash` = SHA-256 of the request it protects
   (prepared once per app start with the Cloud project number), or a classic token with the challenge as nonce.
3. `POST /v1/attestation/google {integrityToken, challenge}`, or the token in the `ZapaKid-Attestation` header on
   the protected request (sign-in, kid pairing, key upload).
4. The API decodes it with Google and requires `PLAY_RECOGNIZED`, package `com.zapacit.cuzaai`, the release signing
   certificate, `MEETS_DEVICE_INTEGRITY`, `LICENSED` and a fresh matching hash or nonce. Failure: `403
   attestation_failed`, and the app shows a clear "this app or phone can't be verified" screen.
5. Fails closed on sign-in, account creation and kid pairing. Emulator and debug builds can't pass, so CI tests use
   the stubbed API; a staging-only allowlist for test devices is an API decision.

Needs from Stefan: Play Integrity enabled in Play Console, linked Google Cloud project number.

## Key verification (libsignal safety numbers)

- Each pair of parents (and parent–kid) can compare a safety number: libsignal `NumericFingerprintGenerator`
  (version 2, both identity keys, stable identifiers). Shown as 60 digits and as a QR code; scanning the other
  phone's QR marks the contact verified.
- When a contact's identity key changes, the chat shows a visible, non-dismissable-until-read notice
  ("<name>'s security code changed") and the verified mark is removed. For a verified contact, sending pauses until
  the parent confirms.
- Kid phones show the notice to the kid in simple words and alert the parent's phone.
