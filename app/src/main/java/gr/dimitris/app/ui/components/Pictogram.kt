package gr.dimitris.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import gr.dimitris.app.ui.theme.Sizes
import java.io.File

/**
 * One card's picture at a fixed size, with the placeholder where the picture is not.
 *
 * A picture that was deleted or moved falls back to the frame icon rather than to a gap, so the word
 * under it still works: the cards a caregiver adds do not all have photographs, and a card whose
 * image file went missing in a backup is still a card he can read.
 *
 * Shared because two screens draw exactly this and phase 13 was about to make it three: the sentence
 * builder's strip and typed board, and «Γράψε»'s typed level. Two copies of a fallback drift apart —
 * one of them learns about `error =` and the other does not — and the one that drifts is the one he
 * is looking at.
 */
@Composable
fun Pictogram(file: File?, modifier: Modifier = Modifier, size: Dp = Sizes.stripPicture) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (file != null && file.exists()) {
            AsyncImage(
                model = file, contentDescription = null, contentScale = ContentScale.Fit,
                error = rememberVectorPainter(Icons.Rounded.Image), modifier = Modifier.size(size),
            )
        } else {
            Icon(
                Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(size),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
