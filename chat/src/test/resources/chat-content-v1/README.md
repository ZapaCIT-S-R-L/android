# Sealed message content, version 1

The plaintext inside a sealed chat message, as both apps write it (iOS `ChatContent.swift`, Android
`ChatContent.kt`). Each app must decode every file here and encode the same message to the same JSON
(key order and number formatting aside). Keep this directory identical in zapakid-ios and zapakid-android.

- `text.json`, `image.json`, `voice.json`, `call.json`: one per body kind.
- `unknown-version.json`: must be refused.

Pointer fields: `key` and `digest` base64, `size` is the ciphertext size, `duration` in seconds.
`sentAt` is milliseconds since the epoch (a number; iOS may write a fraction).
