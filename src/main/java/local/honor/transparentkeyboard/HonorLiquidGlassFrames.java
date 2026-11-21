package local.honor.transparentkeyboard;

/**
 * Chooses an IME frame for a liquid-glass request, and holds the enable
 * eligibility rule HonorLiquidGlass.usable(View) and Session.enable both use.
 * Android-free so tests can vary self visibility, window visibility, and
 * ancestor isShown independently. This is not a substitute for a real View.
 */
final class HonorLiquidGlassFrames {
    private HonorLiquidGlassFrames() {}

    /**
     * Enable eligibility. All four must be true.
     * attached: View.isAttachedToWindow()
     * selfVisible: View.getVisibility() == VISIBLE
     * windowVisible: View.getWindowVisibility() == VISIBLE
     * shown: View.isShown() (this view and ancestors). Does not replace windowVisible:
     * AOSP isShown walks ancestor visibility and returns true at a non-View parent,
     * and does not read window visibility.
     */
    static boolean usable(boolean attached, boolean selfVisible, boolean windowVisible, boolean shown) {
        return attached && selfVisible && windowVisible && shown;
    }

    /**
     * Prefer a still-usable active frame. Otherwise the first usable candidate.
     * Callers must pass candidates most-recent first, with usable[i] already
     * computed by {@link #usable(boolean, boolean, boolean, boolean)}.
     */
    static Object pick(Object active, boolean activeUsable, Object[] candidates, boolean[] usable) {
        if (activeUsable) return active;
        if (candidates == null || usable == null) return null;
        int n = Math.min(candidates.length, usable.length);
        for (int i = 0; i < n; i++) {
            if (usable[i] && candidates[i] != null) return candidates[i];
        }
        return null;
    }
}
