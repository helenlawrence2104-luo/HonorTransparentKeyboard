package local.honor.transparentkeyboard;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Preview settings write policy. Persistent store is the only source of truth
 * for initialization, instance restore, resume, and reopen after upgrade.
 * Loading never writes. Only explicit user commits write.
 */
public final class PreviewSettingsController {
    private final PreviewSettings.Store store;
    private PreviewSettings persisted;
    private PreviewSettings displayed;
    private boolean initializing = true;
    private int writeCount;

    public PreviewSettingsController(PreviewSettings.Store store) {
        this.store = store;
        this.persisted = PreviewSettings.fromStore(store);
        this.displayed = this.persisted.copy();
    }

    public boolean isInitializing() { return initializing; }
    public void beginInit() { initializing = true; }
    public void endInit() { initializing = false; }
    public int writeCount() { return writeCount; }
    public PreviewSettings persisted() { return persisted.copy(); }
    public PreviewSettings displayed() { return displayed.copy(); }

    /** One-way load from the store. Never writes, never calls a saving preset. */
    public PreviewSettings loadFromPersisted() {
        persisted = PreviewSettings.fromStore(store);
        displayed = persisted.copy();
        return displayed.copy();
    }

    /**
     * Discard widget / savedInstanceState values. Persistent values win.
     * Stale restored UI, including a checked liquid-glass switch, is not written back.
     */
    public PreviewSettings discardRestoredWidgets(PreviewSettings ignoredRestoredUi) {
        return loadFromPersisted();
    }

    /** Resume, return from background, or reopen after upgrade: same one-way load, no write. */
    public PreviewSettings reloadFromPersisted() {
        return loadFromPersisted();
    }

    /** Explicit user edit of the current on-screen values. */
    public boolean commitUserChange(PreviewSettings next) {
        if (initializing || next == null) return false;
        displayed = next.copy();
        writeDisplayed();
        return true;
    }

    /** Explicit tap on 更通透. */
    public boolean commitUserClearerPreset() {
        if (initializing) return false;
        displayed = displayed.clearerPreset();
        writeDisplayed();
        return true;
    }

    /** Explicit tap on 恢复默认. Always leaves hnLiquidTest off. */
    public boolean commitUserResetDefaults() {
        if (initializing) return false;
        displayed = PreviewSettings.factoryReset();
        writeDisplayed();
        return true;
    }

    public PreviewSettings storeSnapshot() {
        return PreviewSettings.fromStore(store);
    }

    public Map<String, Integer> storeCopy() {
        PreviewSettings s = PreviewSettings.fromStore(store);
        return new LinkedHashMap<String, Integer>(s.toStoreValues());
    }

    private void writeDisplayed() {
        store.putInts(displayed.toStoreValues());
        persisted = displayed.copy();
        writeCount++;
    }
}
