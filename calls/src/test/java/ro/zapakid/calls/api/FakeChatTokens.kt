package ro.zapakid.calls.api

/** Hands out chat-token-1, then chat-token-2 after a refresh. */
class FakeChatTokens : ChatTokenProvider {
    var refreshes = 0
        private set

    override suspend fun token(): String = "chat-token-${refreshes + 1}"

    override suspend fun refresh(): String {
        refreshes++
        return token()
    }
}
