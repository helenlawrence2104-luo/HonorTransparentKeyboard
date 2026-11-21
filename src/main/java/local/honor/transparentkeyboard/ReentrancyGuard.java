package local.honor.transparentkeyboard;

/**
 * Guards session status listeners. Nested setStatus still updates the field
 * (caller does that first) but a nested notify is suppressed. Exceptions are
 * swallowed so a listener cannot abort enable/disable mid-flight after the
 * status string was already assigned.
 */
final class ReentrancyGuard {
    private boolean inFlight;
    int runs;
    int suppressed;
    int exceptions;
    Throwable lastError;

    boolean inFlight() { return inFlight; }

    boolean run(Runnable action) {
        if (inFlight) {
            suppressed++;
            return false;
        }
        inFlight = true;
        try {
            if (action != null) action.run();
            runs++;
            return true;
        } catch (Throwable t) {
            exceptions++;
            lastError = t;
            return false;
        } finally {
            inFlight = false;
        }
    }
}
