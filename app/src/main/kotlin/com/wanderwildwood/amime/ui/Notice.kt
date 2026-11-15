package com.wanderwildwood.amime.ui

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD

/**
 * A short thing to say (a backup saved, a quick save loaded), along the foot of the page, until
 * the next press or Back. On this panel a toast, gone after a few seconds, could be gone before
 * it had been read.
 */
object Notice {
    var text by mutableStateOf<String?>(null)
        private set

    private val main = Handler(Looper.getMainLooper())

    /** From any thread. */
    fun say(message: String) {
        main.post { text = message }
    }

    fun clear() {
        text = null
    }

    /** For an activity's dispatchTouchEvent: a finger lifting ends the notice. */
    fun pressed(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_UP) clear()
    }

    /** For an activity's dispatchKeyEvent: Back, or any button, ends the notice. */
    fun pressed(event: KeyEvent) {
        if (event.action == KeyEvent.ACTION_DOWN) clear()
    }
}

/**
 * Calls [onPress] as each press anywhere on this layout ends, before that press does anything
 * itself, so a press that says something new leaves the new thing said. Nothing is consumed.
 */
fun Modifier.onEachPress(onPress: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.any { it.pressed })
            onPress()
        }
    }

/** The notice, if there is one: a rule and its words, full width. */
@Composable
fun NoticeStrip(modifier: Modifier = Modifier) {
    val text = Notice.text ?: return
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        HorizontalDividerMMD()
        TextMMD(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
