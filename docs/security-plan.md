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

## Play Integrity (API endpoints are drafted in the API's openapi.yaml)

1. App asks the API for a one-time nonce: `POST /v1/integrity/nonce` → `{nonce, expires_at}` (bound to the session
   or the install).
2. App calls the Standard Play Integrity API with a `requestHash` of the action (sign-in, kid pairing, key upload),
   prepared once with the Cloud project number.
3. App sends the token with the action: `X-Integrity-Token` header, or `POST /v1/integrity/verify`.
4. API decodes it with Google (server-side, its own service account), checks package name, certificate digest,
   `requestHash`, recency, `MEETS_DEVICE_INTEGRITY`, `PLAY_RECOGNIZED`, licensed app.
5. Policy: fail closed for kid pairing and account creation; fail open with a flag (logged, rate-limited) for
   message sending, so a broken Play service doesn't cut a family off.

Needs from Stefan: Play Integrity enabled in Play Console, linked Google Cloud project number.

## Key verification (libsignal safety numbers)

- Each pair of parents (and parent–kid) can compare a safety number: libsignal `NumericFingerprintGenerator`
  (version 2, both identity keys, stable identifiers). Shown as 60 digits and as a QR code; scanning the other
  phone's QR marks the contact verified.
- When a contact's identity key changes, the chat shows a visible, non-dismissable-until-read notice
  ("<name>'s security code changed") and the verified mark is removed. For a verified contact, sending pauses until
  the parent confirms.
- Kid phones show the notice to the kid in simple words and alert the parent's phone.
