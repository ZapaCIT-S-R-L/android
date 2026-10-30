@file:Suppress("MatchingDeclarationName")

package ro.zapakid.app.family

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ro.zapakid.app.R
import ro.zapakid.calls.ui.RobotAvatar

object FamilyTags {
    const val SCREEN = "family"
    const val INVITE = "family_invite"
    const val ADD_KID = "family_add_kid"
    const val KID = "family_kid"
    const val NICKNAME = "kid_nickname"
    const val SAVE_KID = "kid_save"
    const val QR_SHEET = "qr_sheet"
    const val ERROR = "family_error"
}

/** The family: parents, kids (robot, nickname, grade), and the buttons to grow it. */
@Composable
fun FamilyScreen(
    state: FamilyUiState,
    onInviteParent: () -> Unit,
    onAddKid: (nickname: String, avatar: Int, grade: Int?) -> Unit,
    onPairKid: (Kid) -> Unit,
    onCloseQr: () -> Unit,
    onDismissError: () -> Unit
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize().testTag(FamilyTags.SCREEN)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                state.family?.name ?: stringResource(R.string.family_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() }
            )
            if (state.loading) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                return@Column
            }
            val family = state.family ?: return@Column
            Section(stringResource(R.string.family_parents))
            for (parent in family.parents) {
                Text(
                    parent.displayName ?: stringResource(R.string.family_parent),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            if (family.parents.size < 2) {
                OutlinedButton(
                    onInviteParent,
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(FamilyTags.INVITE)
                ) {
                    Text(stringResource(R.string.family_invite_parent))
                }
            }
            Section(stringResource(R.string.family_kids))
            if (family.kids.isEmpty()) {
                Text(
                    stringResource(R.string.family_no_kids),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            for (kid in family.kids) KidRow(kid) { onPairKid(kid) }
            Button({ adding = true }, Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(FamilyTags.ADD_KID)) {
                Text(stringResource(R.string.family_add_kid))
            }
        }
    }
    if (adding) {
        AddKidDialog(onDismiss = { adding = false }, onSave = { name, avatar, grade ->
            adding = false
            onAddKid(name, avatar, grade)
        })
    }
    state.qr?.let { QrDialog(it, onCloseQr) }
    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = onDismissError,
            confirmButton = { TextButton(onDismissError) { Text(stringResource(android.R.string.ok)) } },
            text = { Text(stringResource(errorText(error)), Modifier.testTag(FamilyTags.ERROR)) }
        )
    }
}

@Composable
private fun Section(title: String) =
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })

@Composable
private fun KidRow(kid: Kid, onPair: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag(FamilyTags.KID),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RobotAvatar(index = Avatars.index(kid.avatar), size = 48.dp)
        Column(Modifier.weight(1f)) {
            Text(kid.nickname, style = MaterialTheme.typography.titleMedium)
            Text(
                gradeLabel(kid.grade),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onPair, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.family_pair_phone)) }
    }
}

@Composable
fun gradeLabel(grade: Int?): String = when (grade) {
    null -> stringResource(R.string.grade_not_set)
    -1 -> stringResource(R.string.grade_kindergarten)
    0 -> stringResource(R.string.grade_preparatory)
    else -> stringResource(R.string.grade_n, grade)
}

private val GRADES: List<Int?> = listOf(null, -1, 0) + (1..12)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddKidDialog(onDismiss: () -> Unit, onSave: (String, Int, Int?) -> Unit) {
    var nickname by rememberSaveable { mutableStateOf("") }
    var avatar by rememberSaveable { mutableIntStateOf(0) }
    var grade by remember { mutableStateOf<Int?>(null) }
    val valid = nickname.trim().length in 1..MAX_NICKNAME
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.family_add_kid)) },
        confirmButton = {
            Button({
                onSave(nickname.trim(), avatar, grade)
            }, enabled = valid, modifier = Modifier.testTag(FamilyTags.SAVE_KID)) {
                Text(stringResource(R.string.family_save_kid))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.family_cancel)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it.replace("\n", "").take(MAX_NICKNAME) },
                    label = { Text(stringResource(R.string.family_nickname)) },
                    supportingText = { Text(stringResource(R.string.family_nickname_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(FamilyTags.NICKNAME)
                )
                Text(stringResource(R.string.family_pick_robot), style = MaterialTheme.typography.titleSmall)
                val robot = stringResource(R.string.family_robot_n)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (i in 0 until Avatars.COUNT) {
                        val chosen = avatar == i
                        Surface(
                            shape = CircleShape,
                            modifier =
                                Modifier
                                    .border(
                                        if (chosen) 3.dp else 0.dp,
                                        if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        CircleShape
                                    )
                                    .clickable { avatar = i }
                                    .semantics {
                                        role = Role.RadioButton
                                        selected = chosen
                                        contentDescription = robot.format(i + 1)
                                    }
                        ) { RobotAvatar(index = i, size = 40.dp) }
                    }
                }
                Text(stringResource(R.string.family_grade), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (g in GRADES) {
                        FilterChip(selected = grade == g, onClick = {
                            grade = g
                        }, label = { Text(gradeLabel(g)) })
                    }
                }
            }
        }
    )
}

@Composable
private fun QrDialog(qr: QrCode, onClose: () -> Unit) {
    val forKid = qr.forKid
    val title = if (forKid !=
        null
    ) {
        stringResource(R.string.family_pair_title, forKid.nickname)
    } else {
        stringResource(R.string.family_invite_title)
    }
    val body = if (forKid !=
        null
    ) {
        stringResource(R.string.family_pair_body)
    } else {
        stringResource(R.string.family_invite_body)
    }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClose) { Text(stringResource(R.string.family_done)) } },
        title = { Text(title, textAlign = TextAlign.Center) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag(FamilyTags.QR_SHEET),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QrImage(qr.payload, description = title)
                Text(body, textAlign = TextAlign.Center)
            }
        }
    )
}

internal fun errorText(error: FamilyError): Int = when (error) {
    FamilyError.NETWORK -> R.string.family_error_network
    FamilyError.TOO_MANY -> R.string.family_error_too_many
    FamilyError.INVALID_CODE -> R.string.family_error_invalid_code
    FamilyError.ALREADY_IN_FAMILY -> R.string.family_error_already
    FamilyError.TOO_MANY_KIDS -> R.string.family_error_too_many_kids
    FamilyError.SERVER -> R.string.family_error_server
}

private const val MAX_NICKNAME = 40
