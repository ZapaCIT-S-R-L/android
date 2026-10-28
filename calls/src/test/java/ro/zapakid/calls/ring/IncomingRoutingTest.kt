package ro.zapakid.calls.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review of #46, items 2 and 3: how an incoming call reaches the user. */
class IncomingRoutingTest {
    private val log = mutableListOf<String>()

    private fun route(canRing: Boolean = true, telecom: Boolean = true, addFails: Boolean = false) =
        IncomingRouting.report(
            canRing = canRing,
            telecomAllowed = telecom,
            ways =
                object : IncomingRouting.Ways {
                    override fun register() {
                        log += "register"
                    }

                    override fun addToTelecom() {
                        if (addFails) throw SecurityException("no phone account")
                        log += "telecom"
                    }

                    override fun showNotification() {
                        log += "notify"
                    }

                    override fun missed() {
                        log += "missed"
                    }
                }
        )

    @Test
    fun theAccountIsRegisteredBeforeTheCallIsAdded() {
        route()
        assertEquals(listOf("register", "telecom"), log)
    }

    @Test
    fun aSecurityExceptionFromTelecomFallsBackToTheNotification() {
        route(addFails = true)
        assertEquals(listOf("register", "notify"), log)
    }

    @Test
    fun withoutTelecomPermissionTheNotificationRings() {
        route(telecom = false)
        assertEquals(listOf("notify"), log)
    }

    @Test
    fun aPhoneThatCannotShowTheRingingIsAMissedCallNotAnEndlessRing() {
        route(canRing = false)
        assertEquals(listOf("missed"), log)
    }

    @Test
    fun canRingNeedsNotificationsAndTheFullScreenIntentOnAndroid14() {
        assertFalse(CallNotifications.canRing(notificationsEnabled = false, sdk = 33, fullScreenAllowed = true))
        assertTrue(CallNotifications.canRing(notificationsEnabled = true, sdk = 33, fullScreenAllowed = false))
        assertFalse(CallNotifications.canRing(notificationsEnabled = true, sdk = 34, fullScreenAllowed = false))
        assertTrue(CallNotifications.canRing(notificationsEnabled = true, sdk = 34, fullScreenAllowed = true))
    }
}
