package local.honor.transparentkeyboard;

/**
 * One-shot public status report used by KeyboardHook and tests.
 * report-only: never writes settings. Re-entry is suppressed. Hook exceptions
 * are recorded and swallowed so a status update cannot crash the IME.
 */
final class PublicStatusDispatch {
    interface ReportSink {
        void report(String line);
    }

    private boolean inFlight;
    int published;
    int suppressedReentries;
    int exceptions;
    Throwable lastError;
    String lastLine;

    boolean isInFlight() { return inFlight; }

    void publish(ReportSink sink, String line) {
        if (inFlight) {
            suppressedReentries++;
            return;
        }
        inFlight = true;
        try {
            lastLine = line;
            if (sink != null) sink.report(line);
            published++;
        } catch (Throwable t) {
            exceptions++;
            lastError = t;
        } finally {
            inFlight = false;
        }
    }
}
