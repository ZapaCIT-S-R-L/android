package ro.zapakid.app.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A QR code on screen: what it encodes and until when it works. */
data class QrCode(val payload: String, val expiresAt: String, val forKid: Kid? = null)

enum class FamilyError { NETWORK, TOO_MANY, INVALID_CODE, ALREADY_IN_FAMILY, TOO_MANY_KIDS, SERVER }

data class FamilyUiState(
    val loading: Boolean = true,
    val family: Family? = null,
    val busy: Boolean = false,
    val qr: QrCode? = null,
    val error: FamilyError? = null
)

/**
 * The parent's family screen: creates the family on first open, invites the second parent, adds kids and shows
 * the kid's pairing QR. [token] is the parent session; [fingerprint] the parent's identity key fingerprint for the
 * pairing QR (null until the key-setup PRs land; the kid's phone then skips the out-of-band check).
 */
class FamilyViewModel(
    private val api: FamilyApi,
    private val token: () -> String?,
    private val fingerprint: suspend () -> String? = { null }
) : ViewModel() {
    private val mutableState = MutableStateFlow(FamilyUiState())
    val state: StateFlow<FamilyUiState> = mutableState

    fun load() = work(loading = true) {
        val family = api.myFamily(session())
        mutableState.update { it.copy(family = family) }
    }

    fun inviteParent() = work {
        val family = mutableState.value.family ?: return@work
        val code = api.inviteParent(session(), family.id)
        mutableState.update { it.copy(qr = QrCode(FamilyLinks.invitation(code.code), code.expiresAt)) }
    }

    fun join(link: String) = work {
        val code = FamilyLinks.invitationCode(link) ?: throw FamilyApiException(GONE, "invitation_invalid", "bad link")
        val family = api.join(session(), code)
        mutableState.update { it.copy(family = family) }
    }

    fun addKid(nickname: String, avatar: Int, grade: Int?) = work {
        val family = mutableState.value.family ?: return@work
        val kid = api.addKid(session(), family.id, nickname, Avatars.id(avatar), grade)
        mutableState.update { it.copy(family = family.copy(kids = family.kids + kid)) }
        showPairing(family, kid)
    }

    fun pairKid(kid: Kid) = work {
        val family = mutableState.value.family ?: return@work
        showPairing(family, kid)
    }

    private suspend fun showPairing(family: Family, kid: Kid) {
        val pairing = api.pairKid(session(), family.id, kid.id)
        mutableState.update {
            it.copy(qr = QrCode(FamilyLinks.pairing(pairing.code, fingerprint()), pairing.expiresAt, kid))
        }
    }

    fun closeQr() = mutableState.update { it.copy(qr = null) }

    fun clearError() = mutableState.update { it.copy(error = null) }

    private fun session(): String = token() ?: throw FamilyApiException(UNAUTHORIZED, "signed_out", "no session")

    @Suppress("TooGenericExceptionCaught")
    private fun work(loading: Boolean = false, block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, loading = loading || it.loading, error = null) }
        viewModelScope.launch {
            try {
                block()
                mutableState.update { it.copy(busy = false, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(busy = false, loading = false, error = errorOf(e)) }
            }
        }
    }

    companion object {
        private const val GONE = 410
        private const val UNAUTHORIZED = 401

        fun errorOf(e: Exception): FamilyError = when {
            e is java.io.IOException -> FamilyError.NETWORK
            e !is FamilyApiException -> FamilyError.SERVER
            e.code in setOf("too_many_invitations", "too_many_pairings") -> FamilyError.TOO_MANY
            e.code in setOf("invitation_invalid", "pairing_code_invalid") -> FamilyError.INVALID_CODE
            e.code == "already_in_family" -> FamilyError.ALREADY_IN_FAMILY
            e.code == "too_many_kids" -> FamilyError.TOO_MANY_KIDS
            else -> FamilyError.SERVER
        }
    }
}
