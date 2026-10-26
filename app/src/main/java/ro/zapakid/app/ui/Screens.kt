package ro.zapakid.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ro.zapakid.app.R
import ro.zapakid.app.auth.Parent
import ro.zapakid.app.auth.SignInError

private val MinTouchTarget = 48.dp

/** Scrollable, centred column: everything stays reachable at large font sizes. */
@Composable
private fun ScreenColumn(tag: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxSize().testTag(tag)) {
        Column(
            modifier =
                Modifier
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() }
    )
}

@Composable
fun SignInScreen(
    busy: Boolean,
    error: SignInError?,
    retryAfterSeconds: Long?,
    onGoogle: () -> Unit,
    onApple: () -> Unit
) {
    ScreenColumn(tag = "sign-in") {
        Title(stringResource(R.string.app_name))
        Text(stringResource(R.string.sign_in_intro), textAlign = TextAlign.Center)

        // TODO(branding): use the official Google and Apple button artwork before release.
        val googleLabel = stringResource(R.string.sign_in_google)
        OutlinedButton(
            onClick = onGoogle,
            enabled = !busy,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget)
                    .semantics { contentDescription = googleLabel }
        ) { Text(googleLabel) }

        val appleLabel = stringResource(R.string.sign_in_apple)
        Button(
            onClick = onApple,
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget)
                    .semantics { contentDescription = appleLabel }
        ) { Text(appleLabel) }

        if (busy) {
            val signingIn = stringResource(R.string.signing_in)
            CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = signingIn })
        }
        if (error != null) {
            Text(
                text = errorText(error, retryAfterSeconds),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}

@Composable
private fun errorText(error: SignInError, retryAfterSeconds: Long?): String {
    if (error == SignInError.RATE_LIMITED && retryAfterSeconds != null) {
        val minutes = ((retryAfterSeconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE).coerceAtLeast(1).toInt()
        return pluralStringResource(R.plurals.error_rate_limited_minutes, minutes, minutes)
    }
    return stringResource(error.message())
}

private const val SECONDS_PER_MINUTE = 60L

private fun SignInError.message(): Int = when (this) {
    SignInError.NETWORK -> R.string.error_network
    SignInError.SERVER -> R.string.error_server
    SignInError.NOT_CONFIGURED -> R.string.error_not_configured
    SignInError.PROVIDER -> R.string.error_provider
    SignInError.RATE_LIMITED -> R.string.error_rate_limited
}

/**
 * Placeholder until the legal text is reviewed.
 * TODO(parental-agreement): real text, versioning, and the consent record through the API.
 */
@Composable
fun AgreementScreen(onContinue: () -> Unit) {
    ScreenColumn(tag = "agreement") {
        Title(stringResource(R.string.agreement_title))
        Text(stringResource(R.string.agreement_placeholder), textAlign = TextAlign.Center)
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) {
            Text(stringResource(R.string.agreement_continue))
        }
    }
}

@Composable
fun HomeScreen(parent: Parent, onSignOut: () -> Unit) {
    ScreenColumn(tag = "home") {
        Title(stringResource(R.string.app_name))
        val who = parent.displayName ?: parent.email
        Text(
            text = if (who != null) stringResource(R.string.signed_in_as, who) else stringResource(R.string.signed_in),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        val signOut = stringResource(R.string.sign_out)
        OutlinedButton(
            onClick = onSignOut,
            modifier = Modifier.heightIn(min = MinTouchTarget).semantics { contentDescription = signOut }
        ) { Text(signOut) }
    }
}

@Composable
fun BlockedScreen(onBack: () -> Unit) {
    ScreenColumn(tag = "blocked") {
        Title(stringResource(R.string.blocked_title))
        Text(stringResource(R.string.blocked_body), textAlign = TextAlign.Center)
        OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = MinTouchTarget)) {
            Text(stringResource(R.string.blocked_back))
        }
    }
}

@Composable
fun AgeDeclinedScreen(onTryAgain: () -> Unit) {
    ScreenColumn(tag = "age-declined") {
        Title(stringResource(R.string.age_declined_title))
        Text(stringResource(R.string.age_declined_body), textAlign = TextAlign.Center)
        Button(onClick = onTryAgain, modifier = Modifier.heightIn(min = MinTouchTarget)) {
            Text(stringResource(R.string.age_declined_try_again))
        }
    }
}

@Composable
fun AttestationFailedScreen(onBack: () -> Unit) {
    ScreenColumn(tag = "attestation-failed") {
        Title(stringResource(R.string.attestation_failed_title))
        Text(stringResource(R.string.attestation_failed_body), textAlign = TextAlign.Center)
        OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = MinTouchTarget)) {
            Text(stringResource(R.string.blocked_back))
        }
    }
}
