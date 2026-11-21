package local.honor.transparentkeyboard;

/**
 * Parsed IME hook settings. KeyboardHook.load reads through this object so
 * tests cover the actual clamps and field mapping, not a parallel model.
 * Does not write the store.
 */
final class HookSettings {
    int degree;
    int material;
    int blur;
    int keys;
    int glass;
    int clarity;
    int clarityInNight;
    int haptic;
    int hapticIntensity;
    int hapticDuration;
    int hapticRhythm;
    int hapticInterval;
    int hwWash;
    int hnLiquidTest;
    long restart;

    interface Source {
        int getInt(String key, int def);
        long getLong(String key, long def);
    }

    static HookSettings from(Source source) {
        HookSettings h = new HookSettings();
        if (source == null) return fallback();
        h.degree = VisualControls.clamp(source.getInt("degree", PreviewSettings.DEFAULT_DEGREE), 0, 100);
        h.material = source.getInt("material", PreviewSettings.DEFAULT_MATERIAL) == 1 ? 1 : 0;
        h.blur = VisualControls.clamp(source.getInt("blur", PreviewSettings.DEFAULT_BLUR), -1, 60);
        h.keys = VisualControls.clamp(source.getInt("keys", PreviewSettings.DEFAULT_KEYS), 0, 100);
        h.glass = VisualControls.clamp(source.getInt("glass", PreviewSettings.DEFAULT_GLASS), 0, 100);
        h.clarity = VisualControls.clamp(source.getInt("clarity", PreviewSettings.DEFAULT_CLARITY), 0, 100);
        h.clarityInNight = source.getInt("clarityInNight", PreviewSettings.DEFAULT_CLARITY_IN_NIGHT) == 1 ? 1 : 0;
        h.haptic = source.getInt("haptic", PreviewSettings.DEFAULT_HAPTIC) == 1 ? 1 : 0;
        h.hapticIntensity = VisualControls.clamp(source.getInt("hapticIntensity", PreviewSettings.DEFAULT_HAPTIC_INTENSITY), 0, 100);
        h.hapticDuration = VisualControls.clamp(source.getInt("hapticDuration", PreviewSettings.DEFAULT_HAPTIC_DURATION), 5, 80);
        h.hapticRhythm = VisualControls.clamp(source.getInt("hapticRhythm", PreviewSettings.DEFAULT_HAPTIC_RHYTHM), 0, 2);
        h.hapticInterval = VisualControls.clamp(source.getInt("hapticInterval", PreviewSettings.DEFAULT_HAPTIC_INTERVAL), 20, 120);
        h.hwWash = source.getInt("hwWash", 0) == 1 ? 1 : 0;
        h.hnLiquidTest = source.getInt("hnLiquidTest", PreviewSettings.DEFAULT_HN_LIQUID_TEST) == 1 ? 1 : 0;
        h.restart = source.getLong("restart", 0);
        return h;
    }

    static HookSettings fallback() {
        return from(new Source() {
            @Override public int getInt(String key, int def) { return def; }
            @Override public long getLong(String key, long def) { return def; }
        });
    }
}
