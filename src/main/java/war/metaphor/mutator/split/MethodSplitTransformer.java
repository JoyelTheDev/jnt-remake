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

    private static final Set<Integer> UNCONDITIONAL_EXIT = Set.of(
            GOTO, TABLESWITCH, LOOKUPSWITCH,
            IRETURN, LRETURN, FRETURN, DRETURN, ARETURN, RETURN, ATHROW
    );

    public MethodSplitTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
        this.minInsn = config == null ? 40  : config.getInt("min-insn", 40);
        this.parts   = Math.max(2, Math.min(8, config == null ? 2 : config.getInt("parts", 2)));
        this.chance  = config == null ? 100 : config.getInt("chance", 100);
    }

    @Override
    public void run(ObfuscatorContext base) {
        int split = 0;
        for (JClassNode classNode : base.getClasses()) {
            if (classNode.isExempt()) continue;
            if (classNode.isInterface()) continue;

            List<MethodNode> toAdd = new ArrayList<>();
            for (MethodNode method : new ArrayList<>(classNode.methods)) {
                if (classNode.isExempt(method)) continue;
                if (Modifier.isAbstract(method.access)) continue;
                if (method.name.equals("<init>") || method.name.equals("<clinit>")) continue;
                if (method.instructions == null || countReal(method) < minInsn) continue;
                if (rand.nextInt(100) >= chance) continue;
                List<MethodNode> created = splitMethod(classNode, method);
                if (created != null) {
                    toAdd.addAll(created);
                    split++;
                }
            }
            classNode.methods.addAll(toAdd);
        }
        Logger.INSTANCE.logln(war.jnt.dash.Level.INFO, Origin.METAPHOR,
                "MethodSplitTransformer: Split " + split + " methods");
    }

    private int countReal(MethodNode mn) {
        int c = 0;
        for (AbstractInsnNode n : mn.instructions) if (n.getOpcode() >= 0) c++;
        return c;
    }

    private List<MethodNode> splitMethod(JClassNode classNode, MethodNode method) {
        AbstractInsnNode[] all = method.instructions.toArray();
        if (all.length == 0) return null;

        Set<LabelNode> jumpTargets = collectJumpTargets(method);

        Map<AbstractInsnNode, Integer> pos = new HashMap<>();
        for (int i = 0; i < all.length; i++) pos.put(all[i], i);

        int total = all.length;
        int mid = total / 2;
        int bestCut = -1;
        int bestDist = Integer.MAX_VALUE;

        for (int i = 1; i < total; i++) {
            AbstractInsnNode cur = all[i];
            if (cur.getOpcode() < 0) continue;

            AbstractInsnNode prev = prevReal(cur);
            if (prev == null) continue;
            if (!UNCONDITIONAL_EXIT.contains(prev.getOpcode())) continue;
            if (forwardJumpCrossed(all, i, jumpTargets)) continue;
            if (tailStartsAtHandler(all, i, method)) continue;

            int dist = Math.abs(i - mid);
            if (dist < bestDist) {
                bestDist = dist;
                bestCut = i;
            }
        }

        if (bestCut < 0) return null;
        return doSplit(classNode, method, all, bestCut);
    }

    private boolean forwardJumpCrossed(AbstractInsnNode[] all, int cutIdx,
                                        Set<LabelNode> jumpTargets) {
        Set<LabelNode> tailLabels = new HashSet<>();
        for (int i = cutIdx; i < all.length; i++)
            if (all[i] instanceof LabelNode ln) tailLabels.add(ln);

        for (int i = 0; i < cutIdx; i++) {
            AbstractInsnNode n = all[i];
            if (n instanceof JumpInsnNode jin && tailLabels.contains(jin.label)) return true;
            if (n instanceof TableSwitchInsnNode ts) {
                if (tailLabels.contains(ts.dflt)) return true;
                for (LabelNode lb : ts.labels) if (tailLabels.contains(lb)) return true;
            }
            if (n instanceof LookupSwitchInsnNode ls) {
                if (tailLabels.contains(ls.dflt)) return true;
                for (LabelNode lb : ls.labels) if (tailLabels.contains(lb)) return true;
            }
        }
        return false;
    }

    private boolean tailStartsAtHandler(AbstractInsnNode[] all, int cutIdx,
                                         MethodNode method) {
        if (method.tryCatchBlocks == null) return false;
        Set<LabelNode> handlerLabels = new HashSet<>();
        for (TryCatchBlockNode tcb : method.tryCatchBlocks)
            handlerLabels.add(tcb.handler);
        for (int i = cutIdx; i < all.length; i++) {
            AbstractInsnNode n = all[i];
            if (n instanceof LabelNode ln && handlerLabels.contains(ln)) return true;
            if (n.getOpcode() >= 0) break;
        }
        return false;
    }

    private Set<LabelNode> collectJumpTargets(MethodNode mn) {
        Set<LabelNode> targets = new HashSet<>();
        for (AbstractInsnNode n : mn.instructions) {
            if (n instanceof JumpInsnNode jin) targets.add(jin.label);
            if (n instanceof TableSwitchInsnNode ts) { targets.add(ts.dflt); targets.addAll(ts.labels); }
            if (n instanceof LookupSwitchInsnNode ls) { targets.add(ls.dflt); targets.addAll(ls.labels); }
        }
        if (mn.tryCatchBlocks != null)
            for (TryCatchBlockNode tcb : mn.tryCatchBlocks) targets.add(tcb.handler);
        return targets;
    }

    private AbstractInsnNode prevReal(AbstractInsnNode n) {
        n = n.getPrevious();
        while (n != null && n.getOpcode() < 0) n = n.getPrevious();
        return n;
    }

    private List<MethodNode> doSplit(JClassNode classNode, MethodNode original,
                                      AbstractInsnNode[] all, int cutIdx) {
        boolean isStatic = Modifier.isStatic(original.access);
        Type origRet = Type.getReturnType(original.desc);
        String tailDesc = buildTailDesc(original, isStatic);
        String tailName = original.name + "$split_" + Integer.toHexString(rand.nextInt(0xFFFF));

        Map<LabelNode, LabelNode> labelMap = new HashMap<>();
        for (int i = cutIdx; i < all.length; i++)
            if (all[i] instanceof LabelNode ln) labelMap.put(ln, new LabelNode());

        InsnList tailInsns = new InsnList();
        for (int i = cutIdx; i < all.length; i++)
            tailInsns.add(all[i].clone(labelMap));

        MethodNode tail = new MethodNode(
                ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
                tailName, tailDesc, null,
                original.exceptions == null ? null : original.exceptions.toArray(new String[0]));
        tail.instructions = tailInsns;
        tail.tryCatchBlocks = new ArrayList<>();
        tail.maxLocals = original.maxLocals + 4;
        tail.maxStack = original.maxStack + 4;

        if (original.tryCatchBlocks != null) {
            Set<LabelNode> tailOrigLabels = new HashSet<>();
            for (int i = cutIdx; i < all.length; i++)
                if (all[i] instanceof LabelNode ln) tailOrigLabels.add(ln);
            for (TryCatchBlockNode tcb : original.tryCatchBlocks) {
                if (tailOrigLabels.contains(tcb.start) && tailOrigLabels.contains(tcb.end)
                        && tailOrigLabels.contains(tcb.handler)) {
                    tail.tryCatchBlocks.add(new TryCatchBlockNode(
                            labelMap.getOrDefault(tcb.start, tcb.start),
                            labelMap.getOrDefault(tcb.end, tcb.end),
                            labelMap.getOrDefault(tcb.handler, tcb.handler),
                            tcb.type));
                }
            }
        }

        for (int i = cutIdx; i < all.length; i++)
            original.instructions.remove(all[i]);

        if (original.tryCatchBlocks != null) {
            Set<LabelNode> tailOrigLabels = new HashSet<>();
            for (int i = cutIdx; i < all.length; i++)
                if (all[i] instanceof LabelNode ln) tailOrigLabels.add(ln);
            original.tryCatchBlocks.removeIf(tcb ->
                    tailOrigLabels.contains(tcb.start) && tailOrigLabels.contains(tcb.end)
                            && tailOrigLabels.contains(tcb.handler));
        }

        original.instructions.add(buildTailCall(isStatic, classNode.name, tailName, tailDesc, origRet));
        original.maxStack = Math.max(original.maxStack, tail.maxStack);

        return List.of(tail);
    }

    private String buildTailDesc(MethodNode method, boolean isStatic) {
        Type[] origArgs = Type.getArgumentTypes(method.desc);
        Type ret = Type.getReturnType(method.desc);
        if (isStatic) return method.desc;
        Type[] newArgs = new Type[origArgs.length + 1];
        newArgs[0] = Type.getType("Ljava/lang/Object;");
        System.arraycopy(origArgs, 0, newArgs, 1, origArgs.length);
        return Type.getMethodDescriptor(ret, newArgs);
    }

    private InsnList buildTailCall(boolean isStatic, String owner,
                                    String tailName, String tailDesc, Type ret) {
        InsnListBuilder b = InsnListBuilder.builder();
        int slot = 0;
        for (Type arg : Type.getArgumentTypes(tailDesc)) {
            b.load(arg, slot);
            slot += arg.getSize();
        }
        b.invokestatic(owner, tailName, tailDesc);
        switch (ret.getSort()) {
            case Type.VOID    -> b._return();
            case Type.INT, Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT -> b.ireturn();
            case Type.LONG    -> b.lreturn();
            case Type.FLOAT   -> b.freturn();
            case Type.DOUBLE  -> b.dreturn();
            default           -> b.areturn();
        }
        return b.build();
    }
}