package ro.zapakid.app.family

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ro.zapakid.app.R
import ro.zapakid.calls.ui.LocalMetrics
import ro.zapakid.calls.ui.LocalPalette
import ro.zapakid.calls.ui.RobotAvatar

object FamilyMembersTags {
    const val RETRY = "family_members_retry"
    const val ERROR = "family_members_error"
}

/**
 * The kids' Family tab: everyone in the family as friendly cards. Read-only: kids can't change the family.
 * While loading, a spinner; when the read fails (offline, or the API doesn't take kid tokens yet), the reason and
 * a retry button.
 */
@Composable
fun FamilyMembersScreen(state: KidFamilyState, onRetry: () -> Unit) {
    val metrics = LocalMetrics.current
    val tints = LocalPalette.current.cardTints
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(metrics.spacingDp.dp),
            verticalArrangement = Arrangement.spacedBy(metrics.spacingDp.dp)
        ) {
            Text(
                (state as? KidFamilyState.Loaded)?.family?.name ?: stringResource(R.string.family_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() }
            )
            val family =
                when (state) {
                    KidFamilyState.Loading -> {
                        CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                        return@Column
                    }

                    is KidFamilyState.Failed -> {
                        Text(
                            stringResource(errorText(state.error)),
                            color = MaterialTheme.colorScheme.error,
                            modifier =
                                Modifier
                                    .testTag(FamilyMembersTags.ERROR)
                                    .semantics { liveRegion = LiveRegionMode.Polite }
                        )
                        Button(
                            onRetry,
                            Modifier.fillMaxWidth().heightIn(min = metrics.minTapDp.dp).testTag(FamilyMembersTags.RETRY)
                        ) { Text(stringResource(R.string.kid_pair_try_again)) }
                        return@Column
                    }

                    is KidFamilyState.Loaded -> state.family
                }
            val people =
                family.parents.map { (it.displayName ?: "") to (stringResource(R.string.family_parent) to 0) } +
                    family.kids.map { it.nickname to (gradeLabel(it.grade) to Avatars.index(it.avatar)) }
            people.forEachIndexed { index, (name, detail) ->
                val tint = if (tints.isEmpty()) MaterialTheme.colorScheme.surface else Color(tints[index % tints.size])
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = metrics.minTapDp.dp)
                        .then(if (metrics.shadows) Modifier.shadow(2.dp, MaterialTheme.shapes.large) else Modifier)
                        .background(tint, MaterialTheme.shapes.large)
                        .padding(metrics.spacingDp.dp)
                        .semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(metrics.spacingDp.dp)
                ) {
                    RobotAvatar(index = detail.second, size = 48.dp, sparkles = metrics.sparkles)
                    Column {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            detail.first,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
