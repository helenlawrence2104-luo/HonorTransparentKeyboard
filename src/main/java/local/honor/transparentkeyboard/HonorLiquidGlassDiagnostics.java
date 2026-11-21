package local.honor.transparentkeyboard;

import java.lang.reflect.Method;

/**
 * Read-only diagnostic formatting for liquid-glass requests. No Android types.
 * Does not call vendor write/set APIs. Outputs never include exception messages
 * or other free-form text; only class names, limited frames, and fixed codes.
 */
final class HonorLiquidGlassDiagnostics {
    private static final int MAX_FRAMES = 8;
    private static final int MAX_CAUSES = 6;

    private HonorLiquidGlassDiagnostics() {}

    static String causeChain(Throwable error) {
        if (error == null) return "cause=null";
        StringBuilder sb = new StringBuilder();
        Throwable cur = error;
        int depth = 0;
        while (cur != null && depth < MAX_CAUSES) {
            if (depth > 0) sb.append(" | causedBy=");
            else sb.append("cause=");
            sb.append(safeName(cur.getClass()));
            String frames = keyFrames(cur);
            if (!frames.isEmpty()) sb.append(" @ ").append(frames);
            cur = cur.getCause();
            depth++;
        }
        return sb.toString();
    }

    /** Type and null codes only. No field values, no exception messages. */
    static String inspectMaterial(Object material) {
        if (material == null) return "material=null";
        StringBuilder sb = new StringBuilder();
        sb.append("material=").append(safeName(material.getClass()));
        appendParam(sb, material, "getBlurParamEx", "blurParamEx");
        appendParam(sb, material, "getGlassParamEx", "glassParamEx");
        return sb.toString();
    }

    /** Public Preview/Provider hint: type/null only, never cause text or numbers. */
    static String publicHint(String materialSummary) {
        if (materialSummary == null || materialSummary.isEmpty()) return "";
        return materialSummary;
    }

    static String eventLine(String op, boolean frameVisible, boolean surfaceValid, int pid, String materialSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("op=").append(sanitizeToken(op));
        sb.append(" pid=").append(pid);
        sb.append(" frameVisible=").append(frameVisible);
        sb.append(" surfaceValid=").append(surfaceValid);
        if (materialSummary != null && !materialSummary.isEmpty()) {
            sb.append(' ').append(materialSummary);
        }
        return sb.toString();
    }

    static String safeName(Class<?> cls) {
        if (cls == null) return "null";
        String n = cls.getName();
        return n == null ? "unknown" : n;
    }

    private static String sanitizeToken(String op) {
        if (op == null) return "unknown";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < op.length() && i < 32; i++) {
            char c = op.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_') sb.append(c);
            else break;
        }
        return sb.length() == 0 ? "unknown" : sb.toString();
    }

    private static String keyFrames(Throwable error) {
        StackTraceElement[] frames = error.getStackTrace();
        if (frames == null || frames.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (int i = 0; i < frames.length && kept < MAX_FRAMES; i++) {
            StackTraceElement f = frames[i];
            if (f == null) continue;
            String cn = f.getClassName() == null ? "" : f.getClassName();
            if (!keepFrame(cn, kept)) continue;
            if (kept > 0) sb.append(',');
            sb.append(cn).append('.').append(f.getMethodName()).append(':').append(f.getLineNumber());
            kept++;
        }
        return sb.toString();
    }

    private static boolean keepFrame(String className, int alreadyKept) {
        if (alreadyKept < 3) return true;
        return className.startsWith("local.honor.transparentkeyboard")
                || className.startsWith("com.hihonor.")
                || className.startsWith("android.view.SurfaceControl")
                || className.startsWith("java.lang.reflect")
                || className.contains("HnLiquidGlass")
                || className.contains("HnMaterial");
    }

    private static void appendParam(StringBuilder sb, Object material, String methodName, String label) {
        sb.append(' ').append(label).append('=');
        try {
            Method m = findNoArg(material.getClass(), methodName);
            if (m == null) {
                sb.append("method-absent");
                return;
            }
            m.setAccessible(true);
            Object value = m.invoke(material);
            if (value == null) {
                sb.append("null");
                return;
            }
            sb.append(safeName(value.getClass()));
        } catch (Throwable t) {
            Throwable shown = t;
            if (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) {
                shown = t.getCause();
            }
            sb.append("inspect-error:").append(safeName(shown.getClass()));
        }
    }

    private static Method findNoArg(Class<?> cls, String name) {
        Class<?> cur = cls;
        while (cur != null && cur != Object.class) {
            try {
                return cur.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                try {
                    return cur.getMethod(name);
                } catch (NoSuchMethodException ignored2) {
                    cur = cur.getSuperclass();
                }
            }
        }
        return null;
    }
}
