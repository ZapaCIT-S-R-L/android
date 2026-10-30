package ro.zapakid.app.family

import kotlinx.serialization.Serializable

// Families and kid pairing, as in zapakid-api's openapi.yaml (tags Families, Kid pairing).

@Serializable
data class FamilyParent(val id: String, val displayName: String? = null)

/** A kid: nickname, built-in avatar id (`robot-7`) and grade (-1 kindergarten, 0 preparatory, 1-12, null). */
@Serializable
data class Kid(
    val id: String,
    val nickname: String,
    val avatar: String,
    val grade: Int? = null,
    val createdAt: String = ""
)

@Serializable
data class Family(
    val id: String,
    val name: String? = null,
    val createdAt: String = "",
    val parents: List<FamilyParent> = emptyList(),
    val kids: List<Kid> = emptyList()
)

@Serializable
internal data class FamiliesResponse(val families: List<Family>)

@Serializable
internal data class CreateFamilyRequest(val name: String? = null)

@Serializable
data class OneTimeCode(val code: String, val expiresAt: String)

@Serializable
internal data class CodeRequest(val code: String)

@Serializable
internal data class AddKidRequest(val nickname: String, val avatar: String, val grade: Int?)

@Serializable
data class KidPairing(val code: String, val expiresAt: String, val kid: Kid)

@Serializable
data class KidSessionToken(val token: String, val expiresAt: String)

@Serializable
data class FamilyRef(val id: String, val name: String? = null)

@Serializable
data class PairedKid(val session: KidSessionToken, val kid: Kid, val family: FamilyRef)

/** Avatar ids are `robot-N`; the index picks the drawn robot. */
object Avatars {
    const val COUNT = 8

    fun id(index: Int) = "robot-${Math.floorMod(index, COUNT)}"

    fun index(id: String): Int = id.removePrefix("robot-").toIntOrNull()?.let { Math.floorMod(it, COUNT) } ?: 0
}

/** What the QR codes carry. Codes are secrets until used: never logged. */
object FamilyLinks {
    private const val JOIN = "https://zapakid.ro/join#"
    private const val PAIR = "zapakid://pair"
    private val INVITATION = Regex("^zki_[A-Za-z0-9_-]{43}$")
    private val PAIRING = Regex("^zkp_[A-Za-z0-9_-]{43}$")
    private val FINGERPRINT = Regex("^[0-9a-f]{16,128}$")

    /** Second parent: the code in the fragment, so it never reaches a server log. */
    fun invitation(code: String) = JOIN + code

    fun invitationCode(link: String): String? = link.trim().removePrefix(JOIN).takeIf(INVITATION::matches)

    /** Kid pairing: `zapakid://pair?code=<code>&fp=<parent identity key fingerprint, hex>`. */
    fun pairing(code: String, fingerprint: String?) = PAIR + "?code=" + code + (fingerprint?.let { "&fp=$it" } ?: "")

    data class Pairing(val code: String, val fingerprint: String?)

    fun parsePairing(text: String): Pairing? {
        val params =
            text.takeIf { it.startsWith("$PAIR?") }?.removePrefix("$PAIR?")?.split('&')?.mapNotNull {
                it.split('=', limit = 2).takeIf { kv -> kv.size == 2 }?.let { kv -> kv[0] to kv[1] }
            }?.toMap().orEmpty()
        val code = params["code"]?.takeIf(PAIRING::matches)
        val fp = params["fp"]
        val fpOk = fp == null || FINGERPRINT.matches(fp)
        return if (code != null && fpOk) Pairing(code, fp) else null
    }
}
