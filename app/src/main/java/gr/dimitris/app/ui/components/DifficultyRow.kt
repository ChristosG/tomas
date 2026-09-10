package gr.dimitris.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.difficulty.Difficulty
import gr.dimitris.app.ui.theme.LocalFeedback
import gr.dimitris.app.ui.theme.Sizes
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Every dot carries it, so a test can count them and tap the one it means. */
const val DIFFICULTY_DOT_TAG = "difficulty-dot"

/** The one line the row ever says: a tap he is not allowed to make, explained without blame. */
const val BOUNDED_BY_CAREGIVER = "Ο φροντιστής έβαλε όριο."

/** What a screen reader calls the row. The dots themselves are read as "δυσκολία 3", one each. */
const val DIFFICULTY_LABEL = "Δυσκολία"

/**
 * The five dots: how hard this module is, set by him, here and nowhere else (spec §13).
 *
 * One row, on the first screen of each module, directly under the title. That is the whole of the
 * interface — no menu, no slider, no settings screen he would have to be taught to find. He told us
 * the app is too easy; he cannot say "put the numbers up a bit", so the answer has to be something
 * he can *point* at, on the screen he is already looking at, in the second he has the thought.
 *
 * Filled dots up to [value], outlines above it, so "how hard is this" is answered by how much of the
 * row is solid rather than by a number he has to read. Outside [floor]..[ceiling] a dot is dimmed and
 * refuses the tap: those are the caregiver's bounds, and the refusal says [BOUNDED_BY_CAREGIVER]
 * under the row until he taps something else. Not a toast, not a timer — a line he can read twice,
 * and that goes when he does the thing it is about.
 *
 * The dots are 72 dp **tall**, which is the app's touch floor ([Sizes.touchMin]), and share the row's
 * width between them: five 72 dp-wide circles plus the word «Δυσκολία» is 400 dp, which no phone this
 * runs on has. Each cell is the target, not the circle drawn inside it, so the thumb has the full
 * height and its share of the width to land in.
 */
@Composable
fun DifficultyRow(
    value: Int,
    floor: Int,
    ceiling: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var refused by remember { mutableStateOf(false) }
    val feedback = LocalFeedback.current
    Column(modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin),
        ) {
            // Weighted but not filled, and one line: at a large font scale «Δυσκολία» would otherwise
            // take its full intrinsic width first and squeeze the five cells under the circle they
            // have to hold. The label gives way before the targets do.
            Text(
                DIFFICULTY_LABEL, style = MaterialTheme.typography.titleLarge, maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.size(Sizes.gapSmall))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                for (n in Difficulty.MIN..Difficulty.MAX) {
                    val allowed = n in floor..ceiling
                    Dot(
                        n = n,
                        filled = n <= value,
                        selected = n == value,
                        allowed = allowed,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            // A refused tap changes nothing and says so, in the app's own "not that"
                            // signal rather than in the tap buzz an accepted one gets: for a man who
                            // reads slowly, the Greek line is the weaker half of the answer.
                            if (allowed) {
                                feedback.tap()
                                refused = false
                                // The dot he is already on is not a change. Saying so here rather
                                // than in the store is what keeps a brush against the row from
                                // rebuilding the sitting under his hand — `onChange` is wired to a
                                // module reload in four of the seven screens.
                                if (n != value) onChange(n)
                            } else {
                                feedback.nudge()
                                refused = true
                            }
                        },
                    )
                }
            }
        }
        if (refused) {
            Text(
                BOUNDED_BY_CAREGIVER,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.secondary,
                // Announced when it appears: he may be looking at his thumb, and a screen reader
                // that never mentions it leaves the refusal indistinguishable from a dead row.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Spacer(Modifier.height(Sizes.gapSmall))
    }
}

/**
 * One dot. The whole cell is the target and the circle is what is drawn in it; a dot outside the
 * bounds is dimmed but still answerable, because a dot that does nothing at all when touched teaches
 * him that the row is broken rather than that someone has set a limit.
 *
 * The semantics say all three things a screen reader needs and the eye already gets: which dot this
 * is, whether it is the one he is set to ([selected]), and whether it is his to tap at all
 * ([allowed]). Without them TalkBack reads five identical controls.
 */
@Composable
private fun Dot(n: Int, filled: Boolean, selected: Boolean, allowed: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .height(Sizes.touchMin)
            // One target read as one thing: "Δυσκολία 3", rather than an unlabelled circle.
            .semantics {
                contentDescription = "$DIFFICULTY_LABEL $n"
                role = Role.RadioButton
                this.selected = selected
                if (!allowed) disabled()
            }
            .testTag(DIFFICULTY_DOT_TAG)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(DOT).alpha(if (allowed) 1f else DIMMED)
                .then(if (filled) Modifier.background(ink, CircleShape) else Modifier.border(BORDER, ink, CircleShape)),
        )
    }
}

/** The circle inside the 72 dp cell: as big as five of them and the label fit across a phone. */
private val DOT = 44.dp

/** Thick enough that an outline reads as an empty dot rather than as a faint one. */
private val BORDER = 3.dp

/** Dimmed, not hidden: he can see that there are five and that two of them are not his today. */
private const val DIMMED = 0.3f

/** The dots and the caregiver's fence around them, as one read. */
private data class Dots(val value: Int, val floor: Int, val ceiling: Int)

/**
 * True while the practice route is running one *named* thing — the caregiver's «Δοκίμασέ το» or
 * «Παίξ' το». Provided by [gr.dimitris.app.today.PracticeScreen]; false everywhere else.
 *
 * The row hides itself there. That screen reaches a module with `sessionId == null` and index 0, so
 * it looks exactly like his own first screen, but it is a caregiver checking one word she has just
 * written — and a tap on the dots would write **his** difficulty for a module she is only sampling.
 */
val LocalSingleItemPractice = staticCompositionLocalOf { false }

/**
 * The row wired to one module's stored difficulty: the three numbers come out of [gr.dimitris.app.core.settings.Settings]
 * and a tap goes straight back into it, which is all any module screen should have to know.
 *
 * [onChanged] is for the modules that build their sitting from the difficulty at the moment the
 * screen opens — «Αριθμοί», «Προτάσεις», «Γράψε», «Δεξί χέρι» generate their own content — and have
 * to build it again when he moves the dots. It runs only for a tap that really moved them. The
 * item-based modules need nothing here: their plan comes from
 * [gr.dimitris.app.today.PracticeViewModel], which is watching the same flow.
 */
@Composable
fun ModuleDifficultyRow(module: ModuleId, modifier: Modifier = Modifier, onChanged: () -> Unit = {}) {
    if (LocalSingleItemPractice.current) return
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    // One read, not three: every write to any preference key re-emits all of DataStore's map, and
    // three collectors per row meant three recompositions for one tap.
    val dots by remember(module, graph) {
        combine(
            graph.settings.difficulty(module),
            graph.settings.difficultyFloor(module),
            graph.settings.difficultyCeiling(module),
        ) { value, floor, ceiling -> Dots(value, floor, ceiling) }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = Dots(Difficulty.DEFAULT, Difficulty.MIN, Difficulty.MAX))
    DifficultyRow(
        value = dots.value, floor = dots.floor, ceiling = dots.ceiling, modifier = modifier,
        onChange = { n ->
            scope.launch {
                runCatching { graph.settings.setDifficulty(module, n) }
                    .onFailure { graph.errors.record("difficulty $module", it) }
                onChanged()
            }
        },
    )
}
