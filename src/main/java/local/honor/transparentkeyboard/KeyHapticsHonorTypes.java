package local.honor.transparentkeyboard;

/**
 * Keyboard-type filter for the Honor IME haptic path, taken from
 * {@code com.baidu.input.oem.HonorVibrator} and
 * {@code com.hihonor.android.os.VibratorExConst} in the installed IME.
 * Not a guessed list of unrelated vibrator APIs.
 *
 * Observed on device: {@code haptic.common.click_keyboard_strong} and
 * {@code haptic.common.click_keyboard_delet}. HonorVibrator also hardcodes
 * the {@code _strong} suffix; the const class uses {@code click_keyboard},
 * {@code _1}..{@code _5}, {@code _delet}, {@code _future},
 * {@code _longpressdelet}, {@code _space}.
 */
final class KeyHapticsHonorTypes {
    static final String PREFIX = "haptic.common.click_keyboard";

    private KeyHapticsHonorTypes() {}

    static boolean isKeyboardType(String type) {
        if (type == null || type.length() == 0) return false;
        return type.startsWith(PREFIX);
    }
}
