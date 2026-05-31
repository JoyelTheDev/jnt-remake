package war.metaphor.mutator.flow;

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
import java.util.ArrayList;
import java.util.List;

@Stability(Level.VERY_HIGH)
public class DeadCodeInjectorTransformer extends Mutator {

    private static final int TEMPLATE_COUNT = 5;

    private final int maxInjections;
    private final int chance;

    public DeadCodeInjectorTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
        this.maxInjections = Math.max(1, config.getInt("injections-per-method", 3));
        this.chance        = Math.max(1, Math.min(config.getInt("chance", 80), 100));
    }

    @Override
    public void run(ObfuscatorContext base) {
        int totalInjected = 0;
        for (JClassNode classNode : base.getClasses()) {
            if (classNode.isExempt()) continue;
            for (MethodNode method : classNode.methods) {
                if (classNode.isExempt(method)) continue;
                if (Modifier.isAbstract(method.access)) continue;
                if (BytecodeUtil.leeway(method) < 30000) continue;
                totalInjected += processMethod(method);
            }
        }
        Logger.INSTANCE.logln(war.jnt.dash.Level.INFO, Origin.METAPHOR,
                "DeadCodeInjectorTransformer: Injected " + totalInjected + " dead code blocks");
    }

    private int processMethod(MethodNode method) {
        List<AbstractInsnNode> sites = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            int op = insn.getOpcode();
            boolean isExit = (op >= IRETURN && op <= RETURN) || op == ATHROW;
            if (!isExit) continue;
            AbstractInsnNode next = insn.getNext();
            if (next == null || next instanceof LabelNode) {
                sites.add(insn);
            }
        }

        if (sites.isEmpty()) return 0;
        int injected = 0;
        for (AbstractInsnNode site : sites) {
            if (injected >= maxInjections) break;
            if (rand.nextInt(100) >= chance) continue;
            if (BytecodeUtil.leeway(method) < 10000) break;
            InsnList block = buildDeadBlock(method);
            method.instructions.insert(site, block);
            injected++;
        }
        return injected;
    }

    private InsnList buildDeadBlock(MethodNode method) {
        int template = rand.nextInt(TEMPLATE_COUNT);
        return switch (template) {
            case 0 -> deadFakeCall(method);
            case 1 -> deadFakeArith(method);
            case 2 -> deadFakeField();
            case 3 -> deadFakeArray();
            default -> deadFakeException();
        };
    }

    private InsnList deadFakeCall(MethodNode method) {
        InsnList out = new InsnList();
        int var = method.maxLocals++;
        method.maxStack = Math.max(method.maxStack, method.maxLocals + 3);
        out.add(new TypeInsnNode(NEW, "java/lang/StringBuilder"));
        out.add(new InsnNode(DUP));
        out.add(new LdcInsnNode("_" + Integer.toHexString(rand.nextInt())));
        out.add(new MethodInsnNode(INVOKESPECIAL,
                "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false));
        out.add(BytecodeUtil.makeInteger(rand.nextInt(0xFFFF)));
        out.add(new MethodInsnNode(INVOKEVIRTUAL,
                "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false));
        out.add(new MethodInsnNode(INVOKEVIRTUAL,
                "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
        out.add(new VarInsnNode(ASTORE, var));
        return out;
    }

    private InsnList deadFakeArith(MethodNode method) {
        InsnList out = new InsnList();
        method.maxStack = Math.max(method.maxStack, 4);
        int a = rand.nextInt(0x7FFF) + 1;
        int b = rand.nextInt(0x7FFF) + 1;
        out.add(BytecodeUtil.makeInteger(a));
        out.add(BytecodeUtil.makeInteger(b));
        out.add(new InsnNode(IMUL));
        out.add(BytecodeUtil.makeInteger(rand.nextInt(0xFF) + 1));
        out.add(new InsnNode(IREM));
        out.add(BytecodeUtil.makeInteger(rand.nextInt(0xF) + 1));
        out.add(new InsnNode(ISHL));
        out.add(new InsnNode(POP));
        return out;
    }

    private InsnList deadFakeField() {
        InsnList out = new InsnList();
        out.add(new MethodInsnNode(INVOKESTATIC,
                "java/lang/System", "lineSeparator", "()Ljava/lang/String;", false));
        out.add(new InsnNode(POP));
        return out;
    }

    private InsnList deadFakeArray() {
        InsnList out = new InsnList();
        int size = rand.nextInt(8) + 2;
        out.add(BytecodeUtil.makeInteger(size));
        out.add(new IntInsnNode(NEWARRAY, T_INT));
        out.add(new InsnNode(ARRAYLENGTH));
        out.add(new InsnNode(POP));
        return out;
    }

    private InsnList deadFakeException() {
        InsnList out = new InsnList();
        String[] types = {
            "java/lang/RuntimeException",
            "java/lang/IllegalStateException",
            "java/lang/IllegalArgumentException",
            "java/lang/UnsupportedOperationException"
        };
        String type = types[rand.nextInt(types.length)];
        out.add(new TypeInsnNode(NEW, type));
        out.add(new InsnNode(DUP));
        out.add(new LdcInsnNode("err_" + Integer.toHexString(rand.nextInt())));
        out.add(new MethodInsnNode(INVOKESPECIAL,
                type, "<init>", "(Ljava/lang/String;)V", false));
        out.add(new InsnNode(POP));
        return out;
    }
}