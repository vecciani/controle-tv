package com.controletv

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A keyboard for the TV that is typed from the phone. Unlike `input text`, committing
 * through the input connection supports any character, accents included.
 */
class KeyboardService : InputMethodService() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View = TextView(this).apply {
        text = "Digitando pelo celular"
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#22D3EE"))
        setBackgroundColor(Color.parseColor("#E607070C"))
        setPadding(0, 18, 0, 18)
    }

    private fun hasTextField(): Boolean {
        val info = currentInputEditorInfo ?: return false
        return currentInputConnection != null && info.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_NULL
    }

    private fun apply(backspaces: Int, text: String, enter: Boolean) {
        val connection = currentInputConnection ?: return
        if (backspaces > 0) {
            // deleteSurroundingText ignores selections, so delete the selection first if there is one
            val selected = connection.getSelectedText(0)
            if (!selected.isNullOrEmpty()) {
                connection.commitText("", 1)
                if (backspaces > 1) connection.deleteSurroundingText(backspaces - 1, 0)
            } else {
                connection.deleteSurroundingText(backspaces, 0)
            }
        }
        if (text.isNotEmpty()) connection.commitText(text, 1)
        if (enter) {
            val action = currentInputEditorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
            if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                connection.performEditorAction(action)
            } else {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            }
        }
    }

    companion object {
        @Volatile
        private var instance: KeyboardService? = null
        private val main = Handler(Looper.getMainLooper())

        /** Returns false when our keyboard isn't attached to a text field. */
        fun type(backspaces: Int, text: String, enter: Boolean): Boolean {
            val service = instance ?: return false
            var handled = false
            val done = CountDownLatch(1)
            main.post {
                if (service.hasTextField()) {
                    service.apply(backspaces, text, enter)
                    handled = true
                }
                done.countDown()
            }
            done.await(2, TimeUnit.SECONDS)
            return handled
        }
    }
}
