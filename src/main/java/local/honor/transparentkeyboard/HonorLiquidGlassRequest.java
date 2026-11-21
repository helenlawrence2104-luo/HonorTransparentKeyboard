package local.honor.transparentkeyboard;

/**
 * Android-free request wiring shared by HonorLiquidGlass.setRequested and tests.
 * attach then setRequested(true) matches the hook facade; Session.enable still
 * rechecks eligibility before setEnabled(true).
 */
final class HonorLiquidGlassRequest {
    private HonorLiquidGlassRequest() {}

    static void applyRequested(HonorLiquidGlassSession session, boolean on, Object pickedUsable) {
        if (session == null) return;
        if (on && pickedUsable != null) session.attach(pickedUsable);
        session.setRequested(on);
    }
}
