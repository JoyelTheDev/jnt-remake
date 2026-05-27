package war.metaphor.mutator.data.strings;

import org.objectweb.asm.tree.*;
import war.configuration.ConfigurationSection;
import war.jnt.annotate.Level;
import war.jnt.annotate.Stability;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.Mutator;
import war.metaphor.tree.JClassNode;
import war.metaphor.util.asm.BytecodeUtil;

import java.util.*;

/**
 * StringPoolTransformer
 *
 * Collects every string constant in a class into a single private static
 * {@code String[]} field (the "pool") and replaces each {@code LDC "..."} with
 * a {@code GETSTATIC} + integer index load + {@code AALOAD}.
 *
 * Before:
 * <pre>
 *   LDC "Hello, World!"
 * </pre>
 *
 * After:
 * <pre>
 *   GETSTATIC Owner.IlIl1lIl [Ljava/lang/String;
 *   ICONST_0
 *   AALOAD
 * </pre>
 *
 * Strings inside {@code <clinit>} are skipped to avoid circular init.
 * Duplicate literals map to the same pool slot.
 * Interfaces are skipped (no reliable static init side-effects).
 *
 * Registration in Metaphor.java:
 *   .mutator("string.pool", StringPoolTransformer.class)
 *
 * config.yml — just enable/disable, no other knobs:
 *   string.pool:
 *     enabled: true
 *
 * Recommended: run BEFORE string.light / string.stack so the literals
 * inside the pool are subsequently encrypted by those passes.
 */
@Stability(Level.HIGH)
public class StringPoolTransformer extends Mutator {
    private static final String POOL_DESC = "[Ljava/lang/String;";
    private static final char[] ILLUSION  = "IlI1lIl1lIIl1lI".toCharArray();
    public StringPoolTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
    }

    @Override
    public void run(ObfuscatorContext base) {
        for (JClassNode cn : base.getClasses()) {
            if (cn.isExempt())    continue;
            if (cn.isInterface()) continue;
            processClass(cn);
        }
    }
    private void processClass(JClassNode cn) {
        LinkedHashMap<String, Integer> poolIndex = new LinkedHashMap<>();
        List<Candidate> targets = new ArrayList<>();
        for (MethodNode mn : cn.methods) {
            if (cn.isExempt(mn))            continue;
            if (mn.name.equals("<clinit>")) continue;
            BytecodeUtil.translateConcatenation(mn);
            for (AbstractInsnNode ain : mn.instructions) {
                if (!BytecodeUtil.isString(ain)) continue;
                String value = BytecodeUtil.getString(ain);
                if (value == null) continue;
                poolIndex.computeIfAbsent(value, k -> poolIndex.size());
                targets.add(new Candidate(mn, ain, value));
            }
        }
        if (poolIndex.isEmpty()) return;
        String[] pool = new String[poolIndex.size()];
        poolIndex.forEach((v, i) -> pool[i] = v);
        String fieldName = uniqueFieldName(cn);
        cn.fields.add(new FieldNode(
                ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC | ACC_FINAL,
                fieldName, POOL_DESC, null, null));
        MethodNode clinit = cn.getStaticInit();
        InsnList   init   = buildInitialiser(cn.name, fieldName, pool);
        AbstractInsnNode first = firstReal(clinit);
        if (first == null) clinit.instructions.insert(init);
        else               clinit.instructions.insertBefore(first, init);
        for (Candidate c : targets) {
            InsnList replacement = buildAccess(cn.name, fieldName, poolIndex.get(c.value));
            if (!BytecodeUtil.hasSpace(c.method, replacement)) continue;
            c.method.instructions.insertBefore(c.ldc, replacement);
            c.method.instructions.remove(c.ldc);
        }
    }
    private InsnList buildInitialiser(String owner, String fieldName, String[] pool) {
        InsnList il = new InsnList();
        il.add(pushInt(pool.length));
        il.add(new TypeInsnNode(ANEWARRAY, "java/lang/String"));
        for (int i = 0; i < pool.length; i++) {
            il.add(new InsnNode(DUP));
            il.add(pushInt(i));
            il.add(new LdcInsnNode(pool[i]));
            il.add(new InsnNode(AASTORE));
        }
        il.add(new FieldInsnNode(PUTSTATIC, owner, fieldName, POOL_DESC));
        return il;
    }

    private InsnList buildAccess(String owner, String fieldName, int slot) {
        InsnList il = new InsnList();
        il.add(new FieldInsnNode(GETSTATIC, owner, fieldName, POOL_DESC));
        il.add(pushInt(slot));
        il.add(new InsnNode(AALOAD));
        return il;
    }

    private static AbstractInsnNode pushInt(int value) {
        if (value >= -1 && value <= 5)
            return new InsnNode(ICONST_0 + value);
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE)
            return new IntInsnNode(BIPUSH, value);
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE)
            return new IntInsnNode(SIPUSH, value);
        return new LdcInsnNode(value);
    }

    private static AbstractInsnNode firstReal(MethodNode mn) {
        for (AbstractInsnNode ain : mn.instructions)
            if (ain.getOpcode() >= 0) return ain;
        return null;
    }

    private String uniqueFieldName(JClassNode cn) {
        Set<String> used = new HashSet<>();
        for (FieldNode f : cn.fields) used.add(f.name);
        String name;
        do {
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 8 + rand.nextInt(8); i++)
                sb.append(ILLUSION[rand.nextInt(ILLUSION.length)]);
            name = sb.toString();
        } while (used.contains(name));
        return name;
    }
    private record Candidate(MethodNode method, AbstractInsnNode ldc, String value) {}
}
