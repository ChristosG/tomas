package gr.dimitris.app.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import gr.dimitris.app.modules.Module
import gr.dimitris.app.ui.theme.LocalFeedback
import gr.dimitris.app.ui.theme.Sizes

/** The scrolling tile grid. Tagged so a test can scroll to a module below the fold. */
const val MODULE_GRID_TAG = "module-grid"

/**
 * The practice tiles on Today: two columns, and small enough that the modules there are now — eight,
 * once the arcade and sing-then-say are switched on, and «SQL» since phase 13 — fit on a phone screen
 * under the greeting.
 *
 * Where they still do not fit (a short screen, a large font scale, the "missing Greek voice" card
 * above them) the grid scrolls, and [TILE_HEIGHT] is chosen so the row that does not fit is cut
 * across rather than landing exactly on the edge: a half-tile is what tells him there is more below.
 * [CONTENT_BOTTOM] keeps that last row clear of the fixed bottom buttons.
 */
@Composable
fun ModuleGrid(modules: List<Module>, onOpen: (Module) -> Unit, modifier: Modifier = Modifier) {
    val feedback = LocalFeedback.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(Sizes.gapSmall),
        verticalArrangement = Arrangement.spacedBy(Sizes.gapSmall),
        contentPadding = PaddingValues(bottom = CONTENT_BOTTOM),
        modifier = modifier.fillMaxWidth().testTag(MODULE_GRID_TAG),
    ) {
        items(modules, key = { it.id.name }) { m ->
            Card(
                onClick = { feedback.tap(); onOpen(m) },
                shape = RoundedCornerShape(Sizes.corner),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.heightIn(min = TILE_HEIGHT),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Icon(m.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Sizes.icon))
                    Text(
                        m.titleGreek, style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Still well past the 72dp target, small enough that four rows of tiles fit a phone screen. */
private val TILE_HEIGHT = 104.dp

/** So the last row ends clear of the bottom buttons instead of underneath them. */
private val CONTENT_BOTTOM = 12.dp
