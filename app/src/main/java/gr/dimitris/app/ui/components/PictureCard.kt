package gr.dimitris.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import gr.dimitris.app.ui.theme.LocalFeedback
import gr.dimitris.app.ui.theme.Sizes
import java.io.File

/**
 * [imageFile] is already resolved (see MediaFiles.resolve). A picture that was deleted or moved
 * falls back to the placeholder icon, so the item still works with its word and its voice.
 */
@Composable
fun PictureCard(
    imageFile: File?,
    label: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val feedback = LocalFeedback.current
    Card(
        onClick = { feedback.tap(); onClick() },
        modifier = modifier.sizeIn(minWidth = Sizes.pictureCard, minHeight = Sizes.pictureCard),
        shape = RoundedCornerShape(Sizes.corner),
        border = if (selected) BorderStroke(4.dp, MaterialTheme.colorScheme.secondary) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                if (imageFile != null && imageFile.exists()) {
                    AsyncImage(
                        model = imageFile,
                        contentDescription = label,
                        contentScale = ContentScale.Fit,
                        error = rememberVectorPainter(Icons.Rounded.Image),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (label != null) {
                Text(label, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, maxLines = 2)
            }
        }
    }
}
