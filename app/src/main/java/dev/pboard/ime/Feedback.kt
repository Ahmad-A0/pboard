package dev.pboard.ime

import android.content.Context
import android.media.AudioManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View
import dev.pboard.ime.config.Config
import dev.pboard.ime.layout.Codes

/**
 * Key-press haptics and sounds. Haptics use the platform's keyboard haptic constants (as Gboard does by default),
 * which respect the system "keyboard vibration" setting; a custom duration falls back to the vibrator.
 */
class Feedback(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)

    fun keyDown(view: View, code: Int) {
        val cfg = Config.current
        if (cfg.hapticOnKeypress) {
            if (cfg.hapticDurationMs > 0 && vibrator?.hasVibrator() == true) {
                vibrator.vibrate(VibrationEffect.createOneShot(cfg.hapticDurationMs.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
        if (cfg.soundOnKeypress) {
            val fx = when (code) {
                Codes.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                Codes.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                Codes.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            audio?.playSoundEffect(fx, cfg.soundVolume)
        }
    }

    /** Step tick for spacebar-cursor and delete-swipe steps (Gboard uses the text-handle-move haptic). */
    fun tick(view: View) {
        if (Config.current.hapticOnKeypress) view.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
    }
}
