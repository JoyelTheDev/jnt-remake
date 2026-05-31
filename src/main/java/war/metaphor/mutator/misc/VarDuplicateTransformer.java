package war.metaphor.mutator.misc;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import war.configuration.ConfigurationSection;
import war.jnt.annotate.Level;
import war.jnt.annotate.Stability;
import war.jnt.dash.Logger;
import war.jnt.dash.Origin;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.Mutator;
import war.metaphor.tree.JClassNode;
import war.metaphor.util.asm.BytecodeUtil;

import java.lang.reflect.Modifier;
import java.util.*;

@Stability(Level.HIGH)
public class VarDuplicateTransformer extends Mutator {

    private static final int STORE_TO_LOAD_DELTA = ISTORE - ILOAD;

    private final int     chance;
    private final int     minVars;
    private final boolean skipInit;

    public VarDuplicateTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
        this.chance   = config == null ? 50 : Math.max(0, Math.min(100, config.getInt("chance",    50)));
        this.minVars  = config == null ?  1 : Math.max(1,               config.getInt("min-vars",   1));
        this.skipInit = config == null || config.getBoolean("skip-init", true);
    }

    @Override
    public void run(ObfuscatorContext base) {
        int duplicated = 0;
        for (JClassNode classNode : base.getClasses()) {
            if (classNode.isExempt())    continue;
            if (classNode.isInterface()) continue;

            for (MethodNode method : classNode.methods) {
                if (classNode.isExempt(method)) continue;
                if (Modifier.isAbstract(method.access)) continue;
                if (Modifier.isNative(method.access))   continue;
                if (method.instructions == null || method.instructions.size() == 0) continue;
                if (skipInit && (method.name.equals("<init>") || method.name.equals("<clinit>"))) continue;
                if (BytecodeUtil.leeway(method) < 10_000) continue;

                int before = processMethod(method);
                duplicated += before;
            }
        }
        Logger.INSTANCE.logln(war.jnt.dash.Level.INFO, Origin.METAPHOR,
                "VarDuplicateTransformer: Duplicated " + duplicated + " local variable slots");
    }

    private int processMethod(MethodNode method) {
        int firstLocalSlot = paramSlotCount(method);
        Map<Integer, int[]> varInfo = new LinkedHashMap<>();

        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof VarInsnNode var && isStore(insn.getOpcode())) {
                if (var.var < firstLocalSlot) continue;
                varInfo.computeIfAbsent(var.var, k -> new int[]{ var.getOpcode(), slotWidth(var.getOpcode()) });
            } else if (insn instanceof IincInsnNode iinc) {
                if (iinc.var < firstLocalSlot) continue;
                varInfo.computeIfAbsent(iinc.var, k -> new int[]{ ISTORE, 1 });
            }
        }

        if (varInfo.size() < minVars) return 0;

        Map<Integer, Integer> shadowOf = new HashMap<>(varInfo.size() * 2);
        int nextFreeSlot = method.maxLocals;

        for (Map.Entry<Integer, int[]> entry : varInfo.entrySet()) {
            shadowOf.put(entry.getKey(), nextFreeSlot);
            nextFreeSlot += entry.getValue()[1];
        }

        method.maxLocals = nextFreeSlot;
        method.maxStack = Math.max(method.maxStack, 2);

        AbstractInsnNode[] snapshot = method.instructions.toArray();

        for (AbstractInsnNode insn : snapshot) {
            int op = insn.getOpcode();

            if (insn instanceof VarInsnNode var) {
                Integer shadow = shadowOf.get(var.var);
                if (shadow == null) continue;

                if (isStore(op)) {
                    int loadOp = op - STORE_TO_LOAD_DELTA;
                    InsnList sync = new InsnList();
                    sync.add(new VarInsnNode(loadOp, var.var));
                    sync.add(new VarInsnNode(op,     shadow));
                    method.instructions.insert(insn, sync);
                } else if (isLoad(op) && rand.nextInt(100) < chance) {
                    var.var = shadow;
                }

            } else if (insn instanceof IincInsnNode iinc) {
                Integer shadow = shadowOf.get(iinc.var);
                if (shadow == null) continue;

                InsnList sync = new InsnList();
                sync.add(new VarInsnNode(ILOAD,  iinc.var));
                sync.add(new VarInsnNode(ISTORE, shadow));
                method.instructions.insert(insn, sync);
            }
        }

        return varInfo.size();
    }

    private static int paramSlotCount(MethodNode method) {
        int slots = Modifier.isStatic(method.access) ? 0 : 1;
        for (Type arg : Type.getArgumentTypes(method.desc)) {
            slots += arg.getSize();
        }
        return slots;
    }

    private static boolean isStore(int op) {
        return op >= ISTORE && op <= ASTORE;
    }

    private static boolean isLoad(int op) {
        return op >= ILOAD && op <= ALOAD;
    }

    private static int slotWidth(int storeOp) {
        return (storeOp == LSTORE || storeOp == DSTORE) ? 2 : 1;
    }
}