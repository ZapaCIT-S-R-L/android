package com.zapacit.cuzaai.auth

import okhttp3.ConnectionSpec
import okhttp3.TlsVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TlsTest {
    @Test
    fun android10AndNewerUseOnlyTls13() {
        val specs = Tls.connectionSpecs(sdkInt = 29)
        assertEquals(listOf(TlsVersion.TLS_1_3), specs.single().tlsVersions)
    }

    @Test
    fun olderAndroidFallsBackToModernTls12() {
        val spec = Tls.connectionSpecs(sdkInt = 26).single()
        assertEquals(listOf(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2), spec.tlsVersions)
    }

    @Test
    fun cleartextIsNeverAllowed() {
        for (sdk in listOf(26, 28, 29, 37)) {
            assertFalse(Tls.connectionSpecs(sdk).contains(ConnectionSpec.CLEARTEXT))
        }
    }
}
