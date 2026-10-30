package ro.zapakid.app.shell

import ro.zapakid.calls.crypto.CallKeySealer
import ro.zapakid.chat.crypto.MessageSealer

/**
 * The end-to-end sealers for calls and chat. Only the libsignal implementation may be wired in (a file named
 * Libsignal*.kt in app/src/main; test/sealers.test.js and the release check in app/build.gradle.kts enforce it).
 * Until this phone is registered with the key directory there is none: chats and calls show "setting up".
 */
class Sealers(val calls: CallKeySealer, val chat: MessageSealer)

interface SealerSource {
    /** The sealers for this phone, or null while its keys aren't set up. */
    suspend fun sealers(): Sealers?

    companion object {
        val None =
            object : SealerSource {
                override suspend fun sealers(): Sealers? = null
            }
    }
}
