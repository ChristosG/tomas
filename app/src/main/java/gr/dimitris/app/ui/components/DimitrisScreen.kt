package gr.dimitris.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import gr.dimitris.app.ui.theme.LocalFeedback
import gr.dimitris.app.ui.theme.Sizes

/**
 * Every screen: cream background, big optional back button top-left, title, content, then a
 * bottom slot for the primary action where a left thumb lands.
 */
@Composable
fun DimitrisScreen(
    title: String? = null,
    onBack: (() -> Unit)? = null,
    bottom: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val feedback = LocalFeedback.current
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding().padding(Sizes.screenPadding)
    ) {
        if (onBack != null || title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onClick = { feedback.tap(); onBack() }, modifier = Modifier.size(Sizes.touchMin)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Πίσω", modifier = Modifier.size(Sizes.icon))
                    }
                }
                if (title != null) Text(title, style = MaterialTheme.typography.headlineMedium)
            }
            Spacer(Modifier.height(Sizes.gap))
        }
        Column(Modifier.weight(1f), content = content)
        if (bottom != null) {
            Spacer(Modifier.height(Sizes.gap))
            bottom()
        }
    }
}
