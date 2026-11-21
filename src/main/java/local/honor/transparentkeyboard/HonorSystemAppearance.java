package local.honor.transparentkeyboard;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.provider.Settings;

final class HonorSystemAppearance {
    private static final String ENABLED = "TRANSPARENCY_MAIN_SWITCH";
    private static final String MATERIAL = "transparency_material";
    private static final String DEGREE = "transparency_value";

    final boolean enabled;
    final int material;
    final int degree;

    private HonorSystemAppearance(boolean enabled, int material, int degree) {
        this.enabled = enabled;
        this.material = material;
        this.degree = degree;
    }

    static HonorSystemAppearance read(ContentResolver resolver, int fallbackMaterial, int fallbackDegree) {
        boolean enabled = Settings.Global.getInt(resolver, ENABLED, 1) == 1;
        int systemMaterial = Settings.System.getInt(resolver, MATERIAL, 0);
        // Honor UIKit StyleType.getMaterialType maps system 2 to liquid and 1 to frosted.
        int material = systemMaterial == 2 ? 0 : systemMaterial == 1 ? 1 : fallbackMaterial;
        int degree = Settings.System.getInt(resolver, DEGREE, fallbackDegree);
        return new HonorSystemAppearance(enabled, material, VisualControls.clamp(degree, 0, 100));
    }

    static void observe(ContentResolver resolver, ContentObserver observer) {
        resolver.registerContentObserver(Settings.Global.getUriFor(ENABLED), false, observer);
        resolver.registerContentObserver(Settings.System.getUriFor(MATERIAL), false, observer);
        resolver.registerContentObserver(Settings.System.getUriFor(DEGREE), false, observer);
    }
}
