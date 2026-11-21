package local.honor.transparentkeyboard;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Persistent Preview visual and haptic values. No Android types. */
public final class PreviewSettings {
    public static final int DEFAULT_DEGREE = 92;
    public static final int DEFAULT_MATERIAL = 0;
    public static final int DEFAULT_BLUR = 4;
    public static final int DEFAULT_KEYS = 68;
    public static final int DEFAULT_GLASS = 78;
    public static final int DEFAULT_CLARITY = 65;
    public static final int DEFAULT_CLARITY_IN_NIGHT = 0;
    public static final int DEFAULT_HAPTIC = 1;
    public static final int DEFAULT_HAPTIC_INTENSITY = 60;
    public static final int DEFAULT_HAPTIC_DURATION = 20;
    public static final int DEFAULT_HAPTIC_RHYTHM = 0;
    public static final int DEFAULT_HAPTIC_INTERVAL = 40;
    public static final int DEFAULT_HN_LIQUID_TEST = 0;

    public int degree = DEFAULT_DEGREE;
    public int material = DEFAULT_MATERIAL;
    public int blur = DEFAULT_BLUR;
    public int keys = DEFAULT_KEYS;
    public int glass = DEFAULT_GLASS;
    public int clarity = DEFAULT_CLARITY;
    public int clarityInNight = DEFAULT_CLARITY_IN_NIGHT;
    public int haptic = DEFAULT_HAPTIC;
    public int hapticIntensity = DEFAULT_HAPTIC_INTENSITY;
    public int hapticDuration = DEFAULT_HAPTIC_DURATION;
    public int hapticRhythm = DEFAULT_HAPTIC_RHYTHM;
    public int hapticInterval = DEFAULT_HAPTIC_INTERVAL;
    public int hnLiquidTest = DEFAULT_HN_LIQUID_TEST;

    public interface Store {
        int getInt(String key, int def);
        void putInts(Map<String, Integer> values);
    }

    public static PreviewSettings fallback() {
        return new PreviewSettings();
    }

    public static PreviewSettings fromStore(Store store) {
        PreviewSettings s = fallback();
        if (store == null) return s;
        s.degree = store.getInt("degree", DEFAULT_DEGREE);
        s.material = store.getInt("material", DEFAULT_MATERIAL);
        s.blur = store.getInt("blur", DEFAULT_BLUR);
        s.keys = store.getInt("keys", DEFAULT_KEYS);
        s.glass = store.getInt("glass", DEFAULT_GLASS);
        s.clarity = store.getInt("clarity", DEFAULT_CLARITY);
        s.clarityInNight = store.getInt("clarityInNight", DEFAULT_CLARITY_IN_NIGHT);
        s.haptic = store.getInt("haptic", DEFAULT_HAPTIC);
        s.hapticIntensity = store.getInt("hapticIntensity", DEFAULT_HAPTIC_INTENSITY);
        s.hapticDuration = store.getInt("hapticDuration", DEFAULT_HAPTIC_DURATION);
        s.hapticRhythm = store.getInt("hapticRhythm", DEFAULT_HAPTIC_RHYTHM);
        s.hapticInterval = store.getInt("hapticInterval", DEFAULT_HAPTIC_INTERVAL);
        s.hnLiquidTest = store.getInt("hnLiquidTest", DEFAULT_HN_LIQUID_TEST);
        return s;
    }

    public Map<String, Integer> toStoreValues() {
        Map<String, Integer> m = new LinkedHashMap<String, Integer>();
        m.put("degree", degree);
        m.put("material", material);
        m.put("blur", blur);
        m.put("keys", keys);
        m.put("glass", glass);
        m.put("clarity", clarity);
        m.put("clarityInNight", clarityInNight);
        m.put("haptic", haptic);
        m.put("hapticIntensity", hapticIntensity);
        m.put("hapticDuration", hapticDuration);
        m.put("hapticRhythm", hapticRhythm);
        m.put("hapticInterval", hapticInterval);
        m.put("hnLiquidTest", hnLiquidTest);
        return m;
    }

    public PreviewSettings copy() {
        PreviewSettings s = new PreviewSettings();
        s.degree = degree;
        s.material = material;
        s.blur = blur;
        s.keys = keys;
        s.glass = glass;
        s.clarity = clarity;
        s.clarityInNight = clarityInNight;
        s.haptic = haptic;
        s.hapticIntensity = hapticIntensity;
        s.hapticDuration = hapticDuration;
        s.hapticRhythm = hapticRhythm;
        s.hapticInterval = hapticInterval;
        s.hnLiquidTest = hnLiquidTest;
        return s;
    }

    public boolean sameValues(PreviewSettings o) {
        return o != null
                && degree == o.degree && material == o.material && blur == o.blur
                && keys == o.keys && glass == o.glass && clarity == o.clarity
                && clarityInNight == o.clarityInNight && haptic == o.haptic
                && hapticIntensity == o.hapticIntensity && hapticDuration == o.hapticDuration
                && hapticRhythm == o.hapticRhythm && hapticInterval == o.hapticInterval
                && hnLiquidTest == o.hnLiquidTest;
    }

    public int hapticDurationProgress() { return Math.max(0, hapticDuration - 5); }
    public int hapticIntervalProgress() { return Math.max(0, hapticInterval - 20); }
    public static int durationFromProgress(int progress) { return progress + 5; }
    public static int intervalFromProgress(int progress) { return progress + 20; }
    public int materialRadioId() { return material == 1 ? 1101 : 1100; }
    public int rhythmRadioId() { return hapticRhythm == 1 ? 1301 : (hapticRhythm == 2 ? 1302 : 1300); }
    public static int materialFromRadioId(int id) { return id == 1101 ? 1 : 0; }
    public static int rhythmFromRadioId(int id) { return id == 1301 ? 1 : (id == 1302 ? 2 : 0); }

    /** Values applied when the user taps 恢复默认. hnLiquidTest stays off. */
    public static PreviewSettings factoryReset() {
        return fallback();
    }

    /** Apply the appearance preset without overwriting haptics or experimental switches. */
    public PreviewSettings defaultAppearance() {
        PreviewSettings s = copy();
        s.degree = DEFAULT_DEGREE;
        s.material = DEFAULT_MATERIAL;
        s.blur = DEFAULT_BLUR;
        s.keys = DEFAULT_KEYS;
        s.glass = DEFAULT_GLASS;
        s.clarity = DEFAULT_CLARITY;
        return s;
    }

    /** Values applied when the user taps 更通透. Other fields stay as currently displayed. */
    public PreviewSettings clearerPreset() {
        PreviewSettings s = copy();
        s.keys = 70;
        s.degree = 100;
        s.material = 0;
        s.blur = 0;
        return s;
    }

    @Override public String toString() {
        return "PreviewSettings{degree=" + degree + ", material=" + material + ", blur=" + blur
                + ", keys=" + keys + ", glass=" + glass + ", clarity=" + clarity
                + ", clarityInNight=" + clarityInNight + ", haptic=" + haptic
                + ", hapticIntensity=" + hapticIntensity + ", hapticDuration=" + hapticDuration
                + ", hapticRhythm=" + hapticRhythm + ", hapticInterval=" + hapticInterval
                + ", hnLiquidTest=" + hnLiquidTest + "}";
    }

    @Override public boolean equals(Object o) {
        return o instanceof PreviewSettings && sameValues((PreviewSettings) o);
    }

    @Override public int hashCode() {
        return Objects.hash(degree, material, blur, keys, glass, clarity, clarityInNight,
                haptic, hapticIntensity, hapticDuration, hapticRhythm, hapticInterval, hnLiquidTest);
    }
}
