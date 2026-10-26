package com.zapacit.cuzaai.auth.signin

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/**
 * Sign in with Google through Credential Manager (the "Sign in with Google" button flow).
 * [serverClientId] is the OAuth *web* client ID the API verifies tokens against (public, not a secret).
 */
class GoogleIdTokenProvider(private val activity: () -> Activity, private val serverClientId: String) :
    IdTokenProvider {
    override suspend fun signIn(): IdToken {
        if (serverClientId.isBlank()) throw SignInNotConfigured("Google sign-in")
        val nonce = Random256.next()
        val option =
            GetSignInWithGoogleOption
                .Builder(serverClientId)
                .setNonce(nonce)
                .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val host = activity()
        val credential =
            try {
                CredentialManager.create(host).getCredential(host, request).credential
            } catch (e: GetCredentialCancellationException) {
                throw SignInCancelled().apply { initCause(e) }
            } catch (e: NoCredentialException) {
                // No Google account on this phone: the sheet can't offer one.
                throw SignInFailed("No Google account available").apply { initCause(e) }
            } catch (e: GetCredentialException) {
                throw SignInFailed("Google sign-in failed: ${e.type}").apply { initCause(e) }
            }
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            throw SignInFailed("Unexpected credential type")
        }
        val token =
            try {
                GoogleIdTokenCredential.createFrom(credential.data)
            } catch (e: GoogleIdTokenParsingException) {
                throw SignInFailed("Unreadable Google credential").apply { initCause(e) }
            }
        return IdToken(idToken = token.idToken, nonce = nonce)
    }
}
