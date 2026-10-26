package com.zapacit.cuzaai

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildSanityTest {
    @Test
    fun applicationIdIsTheExistingPlayListing() {
        assertEquals("com.zapacit.cuzaai", BuildConfig.APPLICATION_ID)
    }
}
