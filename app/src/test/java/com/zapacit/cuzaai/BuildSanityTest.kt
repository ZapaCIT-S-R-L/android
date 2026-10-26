package com.zapacit.cuzaai

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildSanityTest {
    @Test
    fun applicationIdIsRoZapakidApp() {
        assertEquals("ro.zapakid.app", BuildConfig.APPLICATION_ID)
    }
}
