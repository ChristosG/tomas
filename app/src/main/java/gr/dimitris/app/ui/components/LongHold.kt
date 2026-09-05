package gr.dimitris.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/** Fires [onHold] once the pointer has stayed down for [millis] without lifting. */
fun Modifier.longHold(millis: Long, onHold: () -> Unit): Modifier = pointerInput(millis) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val liftedEarly = withTimeoutOrNull(millis) { waitForUpOrCancellation(); true } ?: false
        if (!liftedEarly) onHold()
    }
}
