package gr.dimitris.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SuccessMark(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = visible, enter = fadeIn() + scaleIn(initialScale = 0.5f), exit = fadeOut(), modifier = modifier) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = "Σωστά", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(96.dp))
    }
}
