package com.lynko.app

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Minimal IME so the desktop keyboard can type on the phone without root.
 * LinkService hands text/special keys to ImeBridge; when the user selects
 * "Lynko keyboard" (or it's auto-picked), pending input is committed.
 */
class LynkoIME : InputMethodService() {

    companion object {
        const val TAG = "lynko-ime"
        @Volatile var active: Boolean = false
            private set
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        active = true
        drain()
    }

    override fun onFinishInput() {
        active = false
        super.onFinishInput()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        drain()
    }

    private fun drain() {
        while (true) {
            val item = ImeBridge.queue.poll() ?: break
            apply(item)
        }
    }

    private fun apply(item: ImeBridge.Item) {
        val ic = currentInputConnection ?: return
        when (item) {
            is ImeBridge.Item.Text -> ic.commitText(item.text, 1)
            is ImeBridge.Item.Key -> {
                val code = when (item.name) {
                    "BACK" -> KeyEvent.KEYCODE_BACK
                    "HOME" -> KeyEvent.KEYCODE_HOME
                    "RECENTS" -> KeyEvent.KEYCODE_APP_SWITCH
                    "ENTER" -> KeyEvent.KEYCODE_ENTER
                    "DEL" -> KeyEvent.KEYCODE_DEL
                    "ESC" -> KeyEvent.KEYCODE_ESCAPE
                    "TAB" -> KeyEvent.KEYCODE_TAB
                    "UP" -> KeyEvent.KEYCODE_DPAD_UP
                    "DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN
                    "LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT
                    "RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT
                    else -> KeyEvent.KEYCODE_UNKNOWN
                }
                if (code == KeyEvent.KEYCODE_BACK) {
                    requestHideSelf(0)
                } else if (code != KeyEvent.KEYCODE_UNKNOWN) {
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                }
            }
        }
    }
}

/** Queue bridging LinkService (WS thread) to the IME (main thread). */
object ImeBridge {
    sealed class Item {
        data class Text(val text: String) : Item()
        data class Key(val name: String) : Item()
    }
    val queue = ConcurrentLinkedQueue<Item>()

    fun pushText(text: String) {
        queue.add(Item.Text(text))
        Log.i("lynko", "ime: queued text (${text.length} chars)")
    }

    fun pushKey(name: String) {
        queue.add(Item.Key(name))
        Log.i("lynko", "ime: queued key $name")
    }
}
