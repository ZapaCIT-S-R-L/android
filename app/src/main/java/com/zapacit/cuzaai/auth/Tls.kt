package com.zapacit.cuzaai.auth

import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.TlsVersion

/** TLS for the API: 1.3 only where the platform has it (Android 10+), modern TLS 1.2 before that. Never cleartext. */
object Tls {
    private const val TLS13_PLATFORM_SDK = 29

    fun connectionSpecs(sdkInt: Int): List<ConnectionSpec> = if (sdkInt >= TLS13_PLATFORM_SDK) {
        listOf(ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS).tlsVersions(TlsVersion.TLS_1_3).build())
    } else {
        listOf(
            ConnectionSpec
                .Builder(ConnectionSpec.RESTRICTED_TLS)
                .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
                .cipherSuites(
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
                    CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
                ).build()
        )
    }
}
