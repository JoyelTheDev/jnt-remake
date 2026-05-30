package war.metaphor.mutator.virtualization;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

public final class VmReflect {

    private static final ConcurrentHashMap<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Field>    FIELD_CACHE  = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Method>   METHOD_CACHE = new ConcurrentHashMap<>();

    private VmReflect() {}

    private static Class<?> resolveClass(String internalName) {
        return CLASS_CACHE.computeIfAbsent(internalName, n -> {
            String binary = n.replace('/', '.');
            try {
                return Class.forName(binary);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("VM: class not found: " + binary, e);
            }
        });
    }

    public static Object getStatic(String owner, String name, String desc) throws Exception {
        String key = owner + '.' + name;
        Field f = FIELD_CACHE.computeIfAbsent(key, k -> {
            try {
                Field fld = resolveClass(owner).getDeclaredField(name);
                fld.setAccessible(true);
                return fld;
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        return box(f.get(null), desc);
    }

    public static void putStatic(String owner, String name, String desc, Object value) throws Exception {
        String key = owner + '.' + name;
        Field f = FIELD_CACHE.computeIfAbsent(key, k -> {
            try {
                Field fld = resolveClass(owner).getDeclaredField(name);
                fld.setAccessible(true);
                return fld;
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        f.set(null, unbox(value, desc));
    }

    public static Object getField(String owner, String name, String desc, Object instance) throws Exception {
        String key = owner + '.' + name;
        Field f = FIELD_CACHE.computeIfAbsent(key, k -> {
            try {
                Field fld = resolveClass(owner).getDeclaredField(name);
                fld.setAccessible(true);
                return fld;
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        return box(f.get(instance), desc);
    }

    public static void putField(String owner, String name, String desc, Object value, Object instance) throws Exception {
        String key = owner + '.' + name;
        Field f = FIELD_CACHE.computeIfAbsent(key, k -> {
            try {
                Field fld = resolveClass(owner).getDeclaredField(name);
                fld.setAccessible(true);
                return fld;
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        f.set(instance, unbox(value, desc));
    }

    public static Object invoke(String owner, String name, String desc,
                                Object[] stack, int sp) throws Exception {
        Class<?> cls = resolveClass(owner);
        Class<?>[] paramTypes = parseParamTypes(desc);
        String retDesc = desc.substring(desc.indexOf(')') + 1);
        boolean isVoid = retDesc.equals("V");
        int argCount = paramTypes.length;

        String key = owner + '.' + name + desc;
        Method m = METHOD_CACHE.computeIfAbsent(key, k -> {
            try {
                Method mth = cls.getDeclaredMethod(name, paramTypes);
                mth.setAccessible(true);
                return mth;
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        boolean isStatic = java.lang.reflect.Modifier.isStatic(m.getModifiers());
        Object receiver = isStatic ? null : stack[sp - argCount];
        int firstArgIdx = isStatic ? (sp - argCount + 1) : (sp - argCount + 1);

        Object[] args = new Object[argCount];
        for (int i = 0; i < argCount; i++) {
            args[i] = stack[firstArgIdx + i];
        }

        Object result = m.invoke(receiver, args);

        int consumed = argCount + (isStatic ? 0 : 1);
        int newSp = sp - consumed;

        if (!isVoid) {
            stack[newSp + 1] = box(result, retDesc);
            stack[sp] = newSp + 1;
        } else {
            stack[sp] = newSp;
        }
        return stack[sp];
    }

    public static Object allocate(String internalName) throws Exception {
        Class<?> cls = resolveClass(internalName);
        return sun.misc.Unsafe.class.getDeclaredMethod("allocateInstance", Class.class)
                .invoke(getUnsafe(), cls);
    }

    public static Object newarray(int typeCode, int length) {
        return switch (typeCode) {
            case 4  -> new boolean[length];
            case 5  -> new char[length];
            case 6  -> new float[length];
            case 7  -> new double[length];
            case 8  -> new byte[length];
            case 9  -> new short[length];
            case 10 -> new int[length];
            case 11 -> new long[length];
            default -> throw new IllegalArgumentException("Unknown array type: " + typeCode);
        };
    }

    public static Object anewarray(String internalName, int length) throws Exception {
        return Array.newInstance(resolveClass(internalName), length);
    }

    public static Object multianewarray(String desc, int dims, Object[] stack, int sp) throws Exception {
        int[] lengths = new int[dims];
        for (int i = 0; i < dims; i++) {
            lengths[i] = ((Number) stack[sp - dims + 1 + i]).intValue();
        }
        String componentDesc = desc.substring(dims);
        Class<?> componentType = descToClass(componentDesc);
        Object arr = Array.newInstance(componentType, lengths);
        int newSp = sp - dims;
        stack[newSp + 1] = arr;
        stack[sp] = newSp + 1;
        return stack[sp];
    }

    public static Object checkcast(String internalName, Object obj) throws Exception {
        if (obj == null) return null;
        Class<?> cls = resolveClass(internalName);
        if (!cls.isInstance(obj)) {
            throw new ClassCastException(obj.getClass().getName() + " cannot be cast to " + internalName);
        }
        return obj;
    }

    public static int instanceof_(String internalName, Object obj) throws Exception {
        if (obj == null) return 0;
        return resolveClass(internalName).isInstance(obj) ? 1 : 0;
    }

    private static Object box(Object raw, String desc) {
        if (raw == null) return null;
        return raw;
    }

    private static Object unbox(Object boxed, String desc) {
        return boxed;
    }

    private static Class<?>[] parseParamTypes(String desc) throws ClassNotFoundException {
        String params = desc.substring(1, desc.indexOf(')'));
        java.util.List<Class<?>> types = new java.util.ArrayList<>();
        int i = 0;
        while (i < params.length()) {
            char c = params.charAt(i);
            if (c == 'L') {
                int semi = params.indexOf(';', i);
                types.add(resolveClass(params.substring(i + 1, semi)));
                i = semi + 1;
            } else if (c == '[') {
                int j = i;
                while (params.charAt(j) == '[') j++;
                String arrDesc;
                if (params.charAt(j) == 'L') {
                    int semi = params.indexOf(';', j);
                    arrDesc = params.substring(i, semi + 1);
                    i = semi + 1;
                } else {
                    arrDesc = params.substring(i, j + 1);
                    i = j + 1;
                }
                types.add(descToClass(arrDesc));
            } else {
                types.add(primitiveClass(c));
                i++;
            }
        }
        return types.toArray(new Class<?>[0]);
    }

    private static Class<?> primitiveClass(char c) {
        return switch (c) {
            case 'I' -> int.class;
            case 'J' -> long.class;
            case 'F' -> float.class;
            case 'D' -> double.class;
            case 'Z' -> boolean.class;
            case 'B' -> byte.class;
            case 'C' -> char.class;
            case 'S' -> short.class;
            case 'V' -> void.class;
            default  -> throw new IllegalArgumentException("Unknown primitive: " + c);
        };
    }

    private static Class<?> descToClass(String desc) throws ClassNotFoundException {
        if (desc.startsWith("[")) {
            return Class.forName(desc.replace('/', '.'));
        }
        if (desc.startsWith("L") && desc.endsWith(";")) {
            return resolveClass(desc.substring(1, desc.length() - 1));
        }
        return primitiveClass(desc.charAt(0));
    }

    private static Object getUnsafe() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return f.get(null);
    }
}
