package war.metaphor.mutator.split;

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
import war.metaphor.util.builder.InsnListBuilder;

import java.lang.reflect.Modifier;
import java.util.*;

@Stability(Level.MEDIUM)
public class MethodSplitTransformer extends Mutator {

    private final int minInsn;
    private final int parts;
    private final int chance;

    public MethodSplitTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
        this.minInsn = config == null ? 40 : config.getInt("min-insn", 40);
        this.parts   = Math.max(2, Math.min(4, config == null ? 2 : config.getInt("parts", 2)));
        this.chance  = config == null ? 50 : config.getInt("chance", 50);
    }

    @Override
    public void run(ObfuscatorContext base) {
        int split = 0;
        for (JClassNode classNode : base.getClasses()) {
            if (classNode.isExempt()) continue;
            if (classNode.isInterface()) continue;

            List<MethodNode> toAdd = new ArrayList<>();

            for (MethodNode method : classNode.methods) {
                if (classNode.isExempt(method)) continue;
                if (Modifier.isAbstract(method.access)) continue;
                if (method.name.equals("<init>") || method.name.equals("<clinit>")) continue;
                if (method.instructions == null || method.instructions.size() < minInsn) continue;
                if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) continue;
                if (hasAnyJump(method)) continue;
                if (method.instructions.getFirst() == null) continue;
                if (rand.nextInt(100) >= chance) continue;
                List<MethodNode> splitParts = splitMethod(classNode, method);
                if (splitParts != null) {
                    toAdd.addAll(splitParts);
                    split++;
                }
            }

            classNode.methods.addAll(toAdd);
        }
        Logger.INSTANCE.logln(war.jnt.dash.Level.INFO, Origin.METAPHOR,
                "MethodSplitTransformer: Split " + split + " methods");
    }

    private boolean hasAnyJump(MethodNode method) {
        for (AbstractInsnNode n : method.instructions) {
            int op = n.getOpcode();
            if ((op >= IFEQ && op <= JSR) || op == TABLESWITCH || op == LOOKUPSWITCH) {
                return true;
            }
        }
        return false;
    }

    private List<MethodNode> splitMethod(JClassNode classNode, MethodNode method) {
        AbstractInsnNode[] all = method.instructions.toArray();
        if (all == null || all.length == 0) return null;
        if (method.instructions.getFirst() == null) return null;

        List<AbstractInsnNode> real = new ArrayList<>();
        for (AbstractInsnNode n : all) {
            if (n.getOpcode() >= 0) real.add(n);
        }
        if (real.size() < minInsn) return null;

        int methodBytes = 65535 - BytecodeUtil.leeway(method);
        int requiredParts = Math.max(parts, (int) Math.ceil(methodBytes / 45000.0));
        int effectiveParts = Math.min(requiredParts, 16);

        int segSize = real.size() / effectiveParts;
        if (segSize < 5) return null;

        List<MethodNode> created = new ArrayList<>();

        boolean isStatic = Modifier.isStatic(method.access);
        String baseDesc  = method.desc;
        String retDesc   = Type.getReturnType(baseDesc).getDescriptor();

        List<AbstractInsnNode> segmentEntryPoints = new ArrayList<>();
        int realCount = 0;
        for (AbstractInsnNode n : all) {
            if (n.getOpcode() < 0) continue;
            if (realCount % segSize == 0 && realCount > 0 && segmentEntryPoints.size() < effectiveParts - 1) {
                segmentEntryPoints.add(n);
            }
            realCount++;
        }
        if (segmentEntryPoints.isEmpty()) return null;

        for (AbstractInsnNode ep : segmentEntryPoints) {
            if (ep == null) return null;
        }

        String baseName = method.name;
        List<String> partNames = new ArrayList<>();
        for (int i = 0; i < segmentEntryPoints.size() + 1; i++) {
            partNames.add(baseName + "$part" + i + "_" + Integer.toHexString(rand.nextInt(0xFFFF)));
        }

        boolean isVoid = retDesc.equals("V");

        for (int seg = 0; seg <= segmentEntryPoints.size(); seg++) {
            AbstractInsnNode start = seg == 0
                    ? method.instructions.getFirst()
                    : segmentEntryPoints.get(seg - 1);
            AbstractInsnNode end = seg < segmentEntryPoints.size()
                    ? segmentEntryPoints.get(seg)
                    : null;

            if (start == null) return null;

            MethodNode part = new MethodNode(
                    ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
                    partNames.get(seg),
                    buildPartDesc(method, isStatic),
                    null, null);

            Map<LabelNode, LabelNode> labelMap = new HashMap<>();
            AbstractInsnNode probe = start;
            while (probe != null && probe != end) {
                if (probe instanceof LabelNode) {
                    labelMap.put((LabelNode) probe, new LabelNode());
                }
                probe = probe.getNext();
            }

            InsnList partInsns = new InsnList();
            AbstractInsnNode cur = start;
            while (cur != null && cur != end) {
                AbstractInsnNode cloned = cur.clone(labelMap);
                if (cloned != null) {
                    partInsns.add(cloned);
                }
                cur = cur.getNext();
            }

            if (partInsns.size() == 0) return null;

            if (seg < segmentEntryPoints.size()) {
                AbstractInsnNode last = partInsns.getLast();
                while (last != null && last.getOpcode() < 0) last = last.getPrevious();
                if (last != null && BytecodeUtil.isReturning(last)) {
                    partInsns.remove(last);
                }
                partInsns.add(buildCallToPart(method, isStatic, classNode.name, partNames.get(seg + 1)));
            }

            part.instructions = partInsns;
            part.maxLocals = method.maxLocals + 2;
            part.maxStack  = method.maxStack  + 2;
            part.tryCatchBlocks = new ArrayList<>();
            created.add(part);
        }

        method.instructions.clear();
        method.instructions.add(buildCallToPart(method, isStatic, classNode.name, partNames.get(0)));

        return created;
    }

    private String buildPartDesc(MethodNode method, boolean originalIsStatic) {
        if (originalIsStatic) {
            return method.desc;
        } else {
            Type[] args = Type.getArgumentTypes(method.desc);
            Type   ret  = Type.getReturnType(method.desc);
            Type[] newArgs = new Type[args.length + 1];
            newArgs[0] = Type.getType("Ljava/lang/Object;");
            System.arraycopy(args, 0, newArgs, 1, args.length);
            return Type.getMethodDescriptor(ret, newArgs);
        }
    }

    private InsnList buildCallToPart(MethodNode method, boolean isStatic,
                                     String ownerClass, String partName) {
        String partDesc = buildPartDesc(method, isStatic);
        Type[] args     = Type.getArgumentTypes(partDesc);
        Type   ret      = Type.getReturnType(method.desc);

        InsnListBuilder b = InsnListBuilder.builder();

        int slot = 0;
        for (Type arg : args) {
            b.load(arg, slot);
            slot += arg.getSize();
        }

        b.invokestatic(ownerClass, partName, partDesc);

        if (ret.getSort() == Type.VOID) {
            b._return();
        } else {
            b.list(buildReturn(ret));
        }

        return b.build();
    }

    private InsnList buildReturn(Type ret) {
        return switch (ret.getSort()) {
            case Type.INT, Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT ->
                    InsnListBuilder.builder().ireturn().build();
            case Type.LONG   -> InsnListBuilder.builder().lreturn().build();
            case Type.FLOAT  -> InsnListBuilder.builder().freturn().build();
            case Type.DOUBLE -> InsnListBuilder.builder().dreturn().build();
            default          -> InsnListBuilder.builder().areturn().build();
        };
    }
}