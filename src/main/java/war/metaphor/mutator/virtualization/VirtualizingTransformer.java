package war.metaphor.mutator.virtualization;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import war.configuration.ConfigurationSection;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.Mutator;
import war.metaphor.tree.JClassNode;
import war.metaphor.util.Chance;
import war.jnt.dash.Logger;
import war.jnt.dash.Level;
import war.jnt.dash.Origin;
import war.metaphor.util.asm.BytecodeUtil;
import war.metaphor.util.builder.ClassBuilder;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class VirtualizingTransformer extends Mutator {

    private static final String REFLECT_OWNER = "war/metaphor/mutator/virtualization/VmReflect";

    private final int chance;
    private String interpreterOwner;
    private MethodNode interpreterMethod;

    public VirtualizingTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
        this.chance = config != null ? config.getInt("chance", 100) : 100;
    }

    @Override
    public void run(ObfuscatorContext base) {
        interpreterOwner = "vm/" + UUID.randomUUID().toString().replace("-", "");
        interpreterMethod = VmInterpreter.generate(interpreterOwner, "run", null, 0, true);
        List<JClassNode> toAdd = new ArrayList<>();
        int virtualizedCount = 0;
        for (JClassNode jcn : base.getClasses()) {
            if (jcn.isExempt()) continue;
            if (jcn.isInterface()) continue;

            for (MethodNode mn : new ArrayList<>(jcn.methods)) {
                if (Modifier.isAbstract(mn.access)) continue;
                if (Modifier.isNative(mn.access)) continue;
                if (mn.name.equals("<init>") || mn.name.equals("<clinit>")) continue;
                if (!Chance.chance(chance)) continue;
                if (BytecodeUtil.leeway(mn) < 500) continue;
                if (hasUnsupportedInsn(mn)) continue;

                virtualize(jcn, mn);
                virtualizedCount++;
            }
        }

        Logger.INSTANCE.logln(Level.INFO, Origin.METAPHOR,
                "VirtualizingTransformer: Virtualized " + virtualizedCount + " methods");

        JClassNode vmClass = buildVmClass();
        base.addClass(vmClass);

        JClassNode reflectClass = buildReflectClass(base);
        if (reflectClass != null) base.addClass(reflectClass);
    }

    private void virtualize(JClassNode owner, MethodNode mn) {
        VmEncoder encoder = new VmEncoder();
        VmEncoder.Result result = encoder.encode(mn);
        boolean isStatic = Modifier.isStatic(mn.access);
        Type[] argTypes  = Type.getArgumentTypes(mn.desc);
        Type   retType   = Type.getReturnType(mn.desc);
        InsnList stub = buildStub(mn, result, isStatic, argTypes, retType, owner.name);
        mn.instructions = stub;
        mn.tryCatchBlocks.clear();
        mn.localVariables = null;
        mn.maxStack  = 16;
        mn.maxLocals = (isStatic ? 0 : 1) + argTypes.length + 6;
    }

    private InsnList buildStub(MethodNode mn, VmEncoder.Result result,
                               boolean isStatic, Type[] argTypes, Type retType,
                               String ownerName) {
        InsnList il = new InsnList();

        int localBC      = 0;
        int localPool    = localBC + 1;
        int localBytecode= localPool + 1;
        int localThis    = localBytecode + 1;
        int localArgs    = localThis + 1;
        int localResult  = localArgs + 1;

        il.add(new IntInsnNode(SIPUSH, result.bytecode().length));
        il.add(new IntInsnNode(NEWARRAY, T_INT));
        for (int i = 0; i < result.bytecode().length; i++) {
            il.add(new InsnNode(DUP));
            il.add(BytecodeUtil.makeInteger(i));
            il.add(new LdcInsnNode(result.bytecode()[i]));
            il.add(new InsnNode(IASTORE));
        }
        il.add(new VarInsnNode(ASTORE, localBytecode));
        Object[][] pool = result.constPool();
        il.add(BytecodeUtil.makeInteger(pool.length));
        il.add(new TypeInsnNode(ANEWARRAY, "[Ljava/lang/Object;"));
        for (int i = 0; i < pool.length; i++) {
            il.add(new InsnNode(DUP));
            il.add(BytecodeUtil.makeInteger(i));
            il.add(BytecodeUtil.makeInteger(pool[i].length));
            il.add(new TypeInsnNode(ANEWARRAY, "java/lang/Object"));
            for (int j = 0; j < pool[i].length; j++) {
                il.add(new InsnNode(DUP));
                il.add(BytecodeUtil.makeInteger(j));
                Object entry = pool[i][j];
                if (entry instanceof String s) {
                    il.add(new LdcInsnNode(s));
                } else if (entry instanceof Integer iv) {
                    il.add(new LdcInsnNode(iv));
                    il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
                } else if (entry instanceof Long lv) {
                    il.add(new LdcInsnNode(lv));
                    il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false));
                } else if (entry instanceof Float fv) {
                    il.add(new LdcInsnNode(fv));
                    il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false));
                } else if (entry instanceof Double dv) {
                    il.add(new LdcInsnNode(dv));
                    il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false));
                } else {
                    il.add(new InsnNode(ACONST_NULL));
                }
                il.add(new InsnNode(AASTORE));
            }
            il.add(new InsnNode(AASTORE));
        }
        il.add(new VarInsnNode(ASTORE, localPool));
        int maxLocalsInMethod = mn.maxLocals == 0 ? 16 : mn.maxLocals + 8;
        il.add(BytecodeUtil.makeInteger(maxLocalsInMethod));
        il.add(new TypeInsnNode(ANEWARRAY, "java/lang/Object"));
        il.add(new VarInsnNode(ASTORE, localArgs));
        int slot = 0;
        if (!isStatic) {
            il.add(new VarInsnNode(ALOAD, localArgs));
            il.add(new InsnNode(ICONST_0));
            il.add(new VarInsnNode(ALOAD, 0));
            il.add(new InsnNode(AASTORE));
            slot = 1;
        }
        for (int i = 0; i < argTypes.length; i++) {
            Type t = argTypes[i];
            int jvmSlot = slot + (isStatic ? 0 : 0);
            il.add(new VarInsnNode(ALOAD, localArgs));
            il.add(BytecodeUtil.makeInteger(slot));
            loadAndBox(il, t, isStatic ? i : i + 1);
            il.add(new InsnNode(AASTORE));
            slot += t.getSize();
        }

        int pcSlot = !isStatic ? 0 : -1;
        il.add(BytecodeUtil.makeInteger(0));
        il.add(new VarInsnNode(ALOAD, localPool));
        il.add(new VarInsnNode(ALOAD, localBytecode));
        if (!isStatic) {
            il.add(new VarInsnNode(ALOAD, 0));
        }
        il.add(new VarInsnNode(ALOAD, localArgs));
        String interpDesc = isStatic
                ? "(I[[Ljava/lang/Object;[I[Ljava/lang/Object;)Ljava/lang/Object;"
                : "(I[[Ljava/lang/Object;[ILjava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;";
        il.add(new MethodInsnNode(INVOKESTATIC, interpreterOwner, "run", interpDesc, false));
        il.add(new VarInsnNode(ASTORE, localResult));
        addReturn(il, retType, localResult);
        return il;
    }

    private static void loadAndBox(InsnList il, Type t, int varIdx) {
        switch (t.getSort()) {
            case Type.INT, Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT -> {
                il.add(new VarInsnNode(ILOAD, varIdx));
                il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
            }
            case Type.LONG -> {
                il.add(new VarInsnNode(LLOAD, varIdx));
                il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false));
            }
            case Type.FLOAT -> {
                il.add(new VarInsnNode(FLOAD, varIdx));
                il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false));
            }
            case Type.DOUBLE -> {
                il.add(new VarInsnNode(DLOAD, varIdx));
                il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false));
            }
            default -> il.add(new VarInsnNode(ALOAD, varIdx));
        }
    }

    private static void addReturn(InsnList il, Type retType, int localResult) {
        switch (retType.getSort()) {
            case Type.VOID -> il.add(new InsnNode(RETURN));
            case Type.INT, Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT -> {
                il.add(new VarInsnNode(ALOAD, localResult));
                il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
                il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false));
                il.add(new InsnNode(IRETURN));
            }
            case Type.LONG -> {
                il.add(new VarInsnNode(ALOAD, localResult));
                il.add(new TypeInsnNode(CHECKCAST, "java/lang/Long"));
                il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false));
                il.add(new InsnNode(LRETURN));
            }
            case Type.FLOAT -> {
                il.add(new VarInsnNode(ALOAD, localResult));
                il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
                il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false));
                il.add(new InsnNode(FRETURN));
            }
            case Type.DOUBLE -> {
                il.add(new VarInsnNode(ALOAD, localResult));
                il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
                il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false));
                il.add(new InsnNode(DRETURN));
            }
            default -> {
                il.add(new VarInsnNode(ALOAD, localResult));
                il.add(new InsnNode(ARETURN));
            }
        }
    }

    private static boolean hasUnsupportedInsn(MethodNode mn) {
        for (AbstractInsnNode ain : mn.instructions) {
            int op = ain.getOpcode();
            if (op == INVOKEDYNAMIC) return true;
            if (op == JSR || op == RET) return true;
        }
        return false;
    }

    private JClassNode buildVmClass() {
        JClassNode cn = ClassBuilder.create()
                .withName(interpreterOwner)
                .withVersion(V11)
                .withAccess(ACC_PUBLIC | ACC_SYNTHETIC)
                .withSuperName("java/lang/Object")
                .withMethod(interpreterMethod)
                .build();
        cn.sourceFile = null;
        return cn;
    }

    private JClassNode buildReflectClass(ObfuscatorContext base) {
        for (JClassNode jcn : base.getClasses()) {
            if (jcn.name.equals(REFLECT_OWNER)) return null;
        }
        try {
            JClassNode reflectNode = new JClassNode();
            org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(
                    VmReflect.class.getResourceAsStream("/" + REFLECT_OWNER + ".class"));
            cr.accept(reflectNode, org.objectweb.asm.ClassReader.SKIP_FRAMES);
            reflectNode.setRealName(REFLECT_OWNER);
            return reflectNode;
        } catch (Exception e) {
            return null;
        }
    }
}