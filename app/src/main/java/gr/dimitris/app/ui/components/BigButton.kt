package gr.dimitris.app.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import gr.dimitris.app.ui.theme.LocalFeedback
import gr.dimitris.app.ui.theme.Sizes

enum class ButtonTone { Primary, Secondary, Success }

/**
 * [contentDescription] is what a screen reader says for the icon; give it whenever the button has no
 * visible word of its own. [iconOnly] tightens the padding so such a button still fits where it is
 * squeezed next to a wider one.
 */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: ButtonTone = ButtonTone.Primary,
    enabled: Boolean = true,
    contentDescription: String? = null,
    iconOnly: Boolean = false,
) {
    val feedback = LocalFeedback.current
    val container = when (tone) {
        ButtonTone.Primary -> MaterialTheme.colorScheme.primary
        ButtonTone.Secondary -> MaterialTheme.colorScheme.secondary
        ButtonTone.Success -> MaterialTheme.colorScheme.tertiary
    }
    Button(
        onClick = { feedback.tap(); onClick() },
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = Sizes.touchMin),
        shape = RoundedCornerShape(Sizes.corner),
        colors = ButtonDefaults.buttonColors(containerColor = container),
        contentPadding = contentPadding(iconOnly),
    ) {
        Content(text, icon, contentDescription)
    }
}

/** See [BigButton] for [contentDescription] and [iconOnly]. */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    iconOnly: Boolean = false,
) {
    val feedback = LocalFeedback.current
    OutlinedButton(
        onClick = { feedback.tap(); onClick() },
        modifier = modifier.fillMaxWidth().heightIn(min = Sizes.touchMin),
        shape = RoundedCornerShape(Sizes.corner),
        contentPadding = contentPadding(iconOnly),
    ) {
        Content(text, icon, contentDescription)
    }
}

/** A button with no word of its own keeps only enough padding to stay a 72dp target. */
private fun contentPadding(iconOnly: Boolean) =
    if (iconOnly) PaddingValues(12.dp) else PaddingValues(horizontal = 24.dp, vertical = 16.dp)

@Composable
private fun RowScope.Content(text: String, icon: ImageVector?, contentDescription: String?) {
    if (icon != null) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(Sizes.icon))
        if (text.isNotEmpty()) Spacer(Modifier.width(12.dp))
    }
    if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.labelLarge)
}
