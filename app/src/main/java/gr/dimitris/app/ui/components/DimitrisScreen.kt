package gr.dimitris.app.ui.components

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import gr.dimitris.app.LocalOpenTalkBoard
import gr.dimitris.app.ui.theme.LocalFeedback
import gr.dimitris.app.ui.theme.Sizes

/**
 * Every screen: cream background, big optional back button top-left, title, the talk board one tap
 * away top-right, content, then a bottom slot for the primary action where a left thumb lands.
 */
@Composable
fun DimitrisScreen(
    title: String? = null,
    onBack: (() -> Unit)? = null,
    /** The small chat icon top-right. Off on screens that offer the talk board some other way. */
    talkButton: Boolean = true,
    bottom: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val feedback = LocalFeedback.current
    // The gesture and the arrow are the same "back" to him, so they do the same thing: a module that
    // has an answer to save gets to save it either way, instead of the navigator popping under it.
    BackHandler(enabled = onBack != null) { onBack?.invoke() }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding().padding(Sizes.screenPadding)
    ) {
        val openTalk = LocalOpenTalkBoard.current
        if (onBack != null || title != null || (talkButton && openTalk != null)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onClick = { feedback.tap(); onBack() }, modifier = Modifier.size(Sizes.touchMin)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Πίσω", modifier = Modifier.size(Sizes.icon))
                    }
                }
                // Weighted, so a long title wraps instead of pushing the talk icon off the screen.
                if (title != null) Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                if (talkButton && openTalk != null) {
                    if (title == null) Spacer(Modifier.weight(1f))
                    IconButton(onClick = { feedback.tap(); openTalk() }, modifier = Modifier.size(Sizes.touchMin)) {
                        // Not «Μίλα»: that is now the green button that opens the recogniser on
                        // three screens, and a screen reader must not read two controls the same.
                        Icon(Icons.Rounded.Forum, contentDescription = "Πίνακας επικοινωνίας", modifier = Modifier.size(Sizes.icon),
                            tint = MaterialTheme.colorScheme.secondary)
                    }
                }
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
