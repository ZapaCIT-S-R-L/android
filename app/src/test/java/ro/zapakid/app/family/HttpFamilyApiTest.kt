package ro.zapakid.app.family

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import ro.zapakid.app.auth.RequestAttestation

/** Families and kid pairing against a mocked account API that follows zapakid-api's openapi.yaml. */
class HttpFamilyApiTest {
    private val server = MockWebServer()
    private lateinit var api: HttpFamilyApi
    private val hashes = mutableListOf<String>()

    private val familyJson =
        """{"id":"f1","name":"Pop","createdAt":"2026-10-27T10:00:00Z",
           "parents":[{"id":"p1","displayName":"Ana"}],
           "kids":[{"id":"k1","nickname":"Mia","avatar":"robot-3","grade":2,"createdAt":"2026-10-27T10:00:00Z"}]}"""
    private val code = "zkp_" + "a".repeat(43)

    @Before
    fun setUp() {
        server.start()
        api =
            HttpFamilyApi(
                server.url("/v1/"),
                OkHttpClient(),
                object : RequestAttestation {
                    override suspend fun token(requestHash: String): String = "att-token".also { hashes += requestHash }
                }
            )
    }

    @After
    fun tearDown() = server.close()

    private fun sent() = server.takeRequest()

    @Test
    fun myFamilyCreatesOrReturnsTheFamilyWithTheSession() = runTest {
        server.enqueue(MockResponse(code = 201, body = familyJson))

        val family = api.myFamily("zks_tok", name = "Pop")

        assertEquals("Mia", family.kids.single().nickname)
        assertEquals(3, Avatars.index(family.kids.single().avatar))
        val request = sent()
        assertEquals("POST", request.method)
        assertEquals("/v1/families", request.url.encodedPath)
        assertEquals("Bearer zks_tok", request.headers["Authorization"])
        assertEquals("Pop", Json.parseToJsonElement(request.body!!.utf8()).jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun listingFamiliesIsAGet() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"families":[$familyJson]}"""))

        assertEquals("f1", api.families("zks_tok").single().id)
        assertEquals("GET", sent().method)
    }

    @Test
    fun invitingASecondParentAndJoining() = runTest {
        server.enqueue(
            MockResponse(code = 201, body = """{"code":"zki_${"b".repeat(43)}","expiresAt":"2026-10-30T10:00:00Z"}""")
        )
        server.enqueue(MockResponse(code = 200, body = familyJson))

        val invitation = api.inviteParent("zks_tok", "f1")
        api.join("zks_other", invitation.code)

        assertEquals("/v1/families/f1/parents/invitations", sent().url.encodedPath)
        val join = sent()
        assertEquals("/v1/families/join", join.url.encodedPath)
        assertEquals(
            invitation.code,
            Json.parseToJsonElement(join.body!!.utf8()).jsonObject["code"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun addingAKidSendsNicknameAvatarAndGrade() = runTest {
        server.enqueue(
            MockResponse(
                code = 201,
                body = """{"id":"k2","nickname":"Ion","avatar":"robot-1","grade":null,"createdAt":"x"}"""
            )
        )

        api.addKid("zks_tok", "f1", "  Ion ", Avatars.id(1), null)

        val body = Json.parseToJsonElement(sent().body!!.utf8()).jsonObject
        assertEquals("Ion", body["nickname"]!!.jsonPrimitive.content)
        assertEquals("robot-1", body["avatar"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["grade"])
    }

    @Test
    fun kindergartenIsGradeMinusOne() = runTest {
        server.enqueue(
            MockResponse(
                code = 201,
                body = """{"id":"k2","nickname":"Ion","avatar":"robot-1","grade":-1,"createdAt":"x"}"""
            )
        )

        api.addKid("zks_tok", "f1", "Ion", "robot-1", -1)

        assertEquals(-1, Json.parseToJsonElement(sent().body!!.utf8()).jsonObject["grade"]!!.jsonPrimitive.int)
    }

    @Test
    fun pairingCodeAndRedeemWithAttestationButNoSession() = runTest {
        val kid = """{"id":"k1","nickname":"Mia","avatar":"robot-3","grade":2,"createdAt":"x"}"""
        val kidToken = "zkk_" + "c".repeat(43)
        val family = """{"id":"f1","name":"Pop"}"""
        server.enqueue(MockResponse(code = 201, body = """{"code":"$code","expiresAt":"x","kid":$kid}"""))
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"session":{"token":"$kidToken","expiresAt":"x"},"kid":$kid,"family":$family}"""
            )
        )

        val pairing = api.pairKid("zks_tok", "f1", "k1")
        val paired = api.redeem(pairing.code)

        assertEquals("/v1/families/f1/kids/k1/pairing", sent().url.encodedPath)
        val redeem = sent()
        assertEquals("/v1/pairing/redeem", redeem.url.encodedPath)
        assertNull(redeem.headers["Authorization"])
        assertEquals("att-token", redeem.headers["ZapaKid-Attestation"])
        assertEquals(1, hashes.size)
        assertEquals("Mia", paired.kid.nickname)
        assertEquals("f1", paired.family.id)
    }

    @Test
    fun anUsedPairingCodeIsAClearError() = runTest {
        server.enqueue(
            MockResponse(code = 410, body = """{"error":{"code":"pairing_code_invalid","message":"Ask a parent"}}""")
        )

        try {
            api.redeem(code)
            fail("expected an error")
        } catch (e: FamilyApiException) {
            assertEquals(410, e.status)
            assertEquals("pairing_code_invalid", e.code)
        }
    }

    @Test
    fun qrPayloads() {
        val invite = "zki_" + "b".repeat(43)
        assertEquals("https://zapakid.ro/join#$invite", FamilyLinks.invitation(invite))
        assertEquals(invite, FamilyLinks.invitationCode(FamilyLinks.invitation(invite)))
        assertNull(FamilyLinks.invitationCode("https://evil.example/join#$invite"))

        val qr = FamilyLinks.pairing(code, "ab12cd34ef567890")
        assertEquals("zapakid://pair?code=$code&fp=ab12cd34ef567890", qr)
        assertEquals(FamilyLinks.Pairing(code, "ab12cd34ef567890"), FamilyLinks.parsePairing(qr))
        assertEquals(FamilyLinks.Pairing(code, null), FamilyLinks.parsePairing(FamilyLinks.pairing(code, null)))
        assertNull(FamilyLinks.parsePairing("zapakid://pair?code=nope"))
        assertNull(FamilyLinks.parsePairing("https://zapakid.ro/pair?code=$code"))
        assertNull(FamilyLinks.parsePairing("zapakid://pair?code=$code&fp=<script>"))
    }
}
