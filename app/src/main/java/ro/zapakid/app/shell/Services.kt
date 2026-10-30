package ro.zapakid.app.shell

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import ro.zapakid.calls.CallController
import ro.zapakid.chat.ChatRepository
import ro.zapakid.chat.ChatSync

/**
 * What one sign-in owns, and what a sign-out has to give up. Kept apart from [Services] so the shutdown machinery
 * can be tested against the real session, store and call log without a chat stack or a media engine.
 */
interface OwnedSession {
    /** The account this session belongs to: its store, its key and its call log are scoped to it. */
    val account: String

    /** Ends the call and gives up its media, socket and keys, on a scope that outlives the composition. */
    fun releaseCall(cleanup: CoroutineScope): Job

    /** The jobs that use the account's chat store; a sign-out cancels and awaits them before it closes. */
    fun workers(): List<Job>
}

/** The signed-in shell's services: what the screens render, and what a sign-out releases. */
class Services(
    override val account: String,
    val chats: ChatRepository,
    val sync: ChatSync,
    val calls: CallController,
    private val syncJob: Job
) : OwnedSession {
    override fun releaseCall(cleanup: CoroutineScope): Job = calls.signOut(cleanup)

    override fun workers(): List<Job> = listOf(syncJob)
}
