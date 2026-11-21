package local.honor.transparentkeyboard;

/**
 * Attaches vendor no-arg BlurParamEx / GlassParamEx onto a newly created
 * liquid-glass material when those fields are still null. No Android types.
 * Does not write system properties, guess numeric recipes, or call
 * setWindowBlurParams / setHnMaterial.
 */
final class HonorLiquidGlassMaterials {
    static final String BLUR_PARAM_EX = "com.hihonor.android.graphics.material.atomiceffect.BlurParamEx";
    static final String GLASS_PARAM_EX = "com.hihonor.android.graphics.material.atomiceffect.GlassParamEx";

    interface Constructors {
        Object newBlurParamEx() throws Exception;
        Object newGlassParamEx() throws Exception;
    }

    private HonorLiquidGlassMaterials() {}

    static void attachNoArgParams(Object material) throws Exception {
        attachNoArgParams(material, new Constructors() {
            @Override public Object newBlurParamEx() throws Exception {
                return Class.forName(BLUR_PARAM_EX).getDeclaredConstructor().newInstance();
            }
            @Override public Object newGlassParamEx() throws Exception {
                return Class.forName(GLASS_PARAM_EX).getDeclaredConstructor().newInstance();
            }
        });
    }

    static void attachNoArgParams(Object material, Constructors ctors) throws Exception {
        if (material == null || ctors == null) return;
        if (invokeNoArg(material, "getBlurParamEx") == null) {
            invokeOneArg(material, "setBlurParamEx", ctors.newBlurParamEx());
        }
        if (invokeNoArg(material, "getGlassParamEx") == null) {
            invokeOneArg(material, "setGlassParamEx", ctors.newGlassParamEx());
        }
    }

    private static Object invokeNoArg(Object target, String name) throws Exception {
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (NoSuchMethodException e) {
            java.lang.reflect.Method m = findDeclared(target.getClass(), name);
            if (m == null) throw e;
            m.setAccessible(true);
            return m.invoke(target);
        }
    }

    private static void invokeOneArg(Object target, String name, Object arg) throws Exception {
        if (arg == null) throw new IllegalStateException("null-param:" + name);
        Class<?> argType = arg.getClass();
        try {
            target.getClass().getMethod(name, argType).invoke(target, arg);
            return;
        } catch (NoSuchMethodException ignored) {
            /* search declared / assignable */
        }
        java.lang.reflect.Method found = null;
        Class<?> cur = target.getClass();
        while (cur != null && cur != Object.class) {
            java.lang.reflect.Method[] methods = cur.getDeclaredMethods();
            for (int i = 0; i < methods.length; i++) {
                java.lang.reflect.Method m = methods[i];
                if (!name.equals(m.getName())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 1 && pts[0].isAssignableFrom(argType)) {
                    found = m;
                    break;
                }
            }
            if (found != null) break;
            cur = cur.getSuperclass();
        }
        if (found == null) throw new NoSuchMethodException(name);
        found.setAccessible(true);
        found.invoke(target, arg);
    }

    private static java.lang.reflect.Method findDeclared(Class<?> cls, String name) {
        Class<?> cur = cls;
        while (cur != null && cur != Object.class) {
            try {
                return cur.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }
}
