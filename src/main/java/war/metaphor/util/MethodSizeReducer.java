package war.metaphor.util;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.CodeSizeEvaluator;

import java.lang.reflect.Modifier;
import java.util.*;

import static org.objectweb.asm.Opcodes.*;

/**
 * Splits methods that exceed the JVM 64KB bytecode limit into smaller
 * synthetic static helper methods added directly to the same class.
 *
 * Called as a last-resort tier inside JClassNode.compute() before falling
 * back to original (unobfuscated) bytes.
 */
public class MethodSizeReducer {

    private static final int SIZE_LIMIT  = 64000;
    private static final int TARGET_SIZE = 48000;
    private static final int MAX_PASSES  = 24;

    private static final Set<Integer> UNCONDITIONAL_EXIT = Set.of(
            GOTO, TABLESWITCH, LOOKUPSWITCH,
            IRETURN, LRETURN, FRETURN, DRETURN, ARETURN, RETURN, ATHROW
    );

    /**
     * Scans all methods in the class and splits any that exceed SIZE_LIMIT.
     * Newly created helper methods are appended to classNode.methods.
     * Returns true if anything was split.
     */
    public static boolean reduce(ClassNode classNode) {
        boolean anyDone = false;
        boolean changed = true;
        int pass = 0;
        while (changed && pass++ < MAX_PASSES) {
            changed = false;
            List<MethodNode> toAdd = new ArrayList<>();
            for (MethodNode mn : new ArrayList<>(classNode.methods)) {
                if (methodSize(mn) <= SIZE_LIMIT) continue;
                if (Modifier.isAbstract(mn.access) || Modifier.isNative(mn.access)) continue;
                List<MethodNode> created = splitOne(classNode, mn);
                if (created != null && !created.isEmpty()) {
                    toAdd.addAll(created);
                    changed = true;
                    anyDone = true;
                }
            }
            classNode.methods.addAll(toAdd);
        }
        return anyDone;
    }

    private static List<MethodNode> splitOne(ClassNode owner, MethodNode mn) {
        AbstractInsnNode[] all = mn.instructions.toArray();
        if (all.length == 0) return null;

        Set<LabelNode> handlerLabels = collectHandlerLabels(mn);
        Set<LabelNode> jumpTargets   = collectJumpTargets(mn);

        int cut = findBestCut(all, mn, handlerLabels, jumpTargets);
        if (cut < 0) return null;

        return doSplit(owner, mn, all, cut);
    }

    /**
     * Finds the latest instruction index where we can safely cut so that
     * the head portion stays at or below TARGET_SIZE bytes.
     */
    private static int findBestCut(AbstractInsnNode[] all, MethodNode mn,
                                    Set<LabelNode> handlerLabels,
                                    Set<LabelNode> jumpTargets) {
        int best = -1;
        for (int i = 1; i < all.length - 1; i++) {
            AbstractInsnNode cur = all[i];
            if (cur.getOpcode() < 0) continue;

            AbstractInsnNode prev = prevReal(cur);
            if (prev == null) continue;
            if (!UNCONDITIONAL_EXIT.contains(prev.getOpcode())) continue;
            if (forwardJumpCrossed(all, i, jumpTargets)) continue;
            if (tailStartsAtHandler(all, i, handlerLabels)) continue;

            if (estimateHeadSize(all, i) > TARGET_SIZE) break;
            best = i;
        }
        return best;
    }

    private static List<MethodNode> doSplit(ClassNode owner, MethodNode original,
                                             AbstractInsnNode[] all, int cutIdx) {
        boolean isStatic = Modifier.isStatic(original.access);
        Type    origRet  = Type.getReturnType(original.desc);
        String  tailDesc = buildTailDesc(original, isStatic);
        String  tailName = original.name + "$sz_"
                + Integer.toHexString(System.identityHashCode(original) ^ cutIdx);

        Map<LabelNode, LabelNode> labelMap = new HashMap<>();
        for (int i = cutIdx; i < all.length; i++)
            if (all[i] instanceof LabelNode ln) labelMap.put(ln, new LabelNode());

        InsnList tailInsns = new InsnList();
        for (int i = cutIdx; i < all.length; i++)
            tailInsns.add(all[i].clone(labelMap));

        MethodNode tail = new MethodNode(
                ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
                tailName, tailDesc, null,
                original.exceptions == null ? null
                        : original.exceptions.toArray(new String[0]));
        tail.instructions   = tailInsns;
        tail.tryCatchBlocks = new ArrayList<>();
        tail.maxLocals = original.maxLocals + 4;
        tail.maxStack  = original.maxStack  + 4;

        // Move try/catch entries fully contained in the tail
        if (original.tryCatchBlocks != null) {
            Set<LabelNode> tailOrig = new HashSet<>();
            for (int i = cutIdx; i < all.length; i++)
                if (all[i] instanceof LabelNode ln) tailOrig.add(ln);

            for (TryCatchBlockNode tcb : original.tryCatchBlocks) {
                if (tailOrig.contains(tcb.start) && tailOrig.contains(tcb.end)
                        && tailOrig.contains(tcb.handler)) {
                    tail.tryCatchBlocks.add(new TryCatchBlockNode(
                            labelMap.getOrDefault(tcb.start,   tcb.start),
                            labelMap.getOrDefault(tcb.end,     tcb.end),
                            labelMap.getOrDefault(tcb.handler, tcb.handler),
                            tcb.type));
                }
            }
            original.tryCatchBlocks.removeIf(tcb ->
                    tailOrig.contains(tcb.start) && tailOrig.contains(tcb.end)
                            && tailOrig.contains(tcb.handler));
        }

        // Remove tail instructions from original and append the call stub
        for (int i = cutIdx; i < all.length; i++)
            original.instructions.remove(all[i]);

        original.instructions.add(buildCallStub(isStatic, owner.name, tailName, tailDesc, origRet));
        original.maxStack = Math.max(original.maxStack, tail.maxStack);

        return List.of(tail);
    }

    // ── Descriptor & call-stub builders ──────────────────────────────────

    private static String buildTailDesc(MethodNode mn, boolean isStatic) {
        Type[] origArgs = Type.getArgumentTypes(mn.desc);
        Type   ret      = Type.getReturnType(mn.desc);
        if (isStatic) return mn.desc;
        Type[] newArgs  = new Type[origArgs.length + 1];
        newArgs[0] = Type.getType("Ljava/lang/Object;");
        System.arraycopy(origArgs, 0, newArgs, 1, origArgs.length);
        return Type.getMethodDescriptor(ret, newArgs);
    }

    private static InsnList buildCallStub(boolean isStatic, String owner,
                                           String tailName, String tailDesc, Type ret) {
        InsnList list = new InsnList();
        int slot = 0;
        for (Type arg : Type.getArgumentTypes(tailDesc)) {
            int op = switch (arg.getSort()) {
                case Type.LONG   -> LLOAD;
                case Type.FLOAT  -> FLOAD;
                case Type.DOUBLE -> DLOAD;
                case Type.INT, Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT -> ILOAD;
                default -> ALOAD;
            };
            list.add(new VarInsnNode(op, slot));
            slot += arg.getSize();
        }
        list.add(new MethodInsnNode(INVOKESTATIC, owner, tailName, tailDesc, false));
        list.add(switch (ret.getSort()) {
            case Type.VOID                                                   -> new InsnNode(RETURN);
            case Type.INT, Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT   -> new InsnNode(IRETURN);
            case Type.LONG                                                   -> new InsnNode(LRETURN);
            case Type.FLOAT                                                  -> new InsnNode(FRETURN);
            case Type.DOUBLE                                                 -> new InsnNode(DRETURN);
            default                                                          -> new InsnNode(ARETURN);
        });
        return list;
    }

    // ── Size estimation ───────────────────────────────────────────────────

    private static int methodSize(MethodNode mn) {
        try {
            CodeSizeEvaluator cse = new CodeSizeEvaluator(null);
            mn.accept(cse);
            return cse.getMaxSize();
        } catch (Exception e) {
            return 0;
        }
    }

    private static int estimateHeadSize(AbstractInsnNode[] all, int cutIdx) {
        MethodNode tmp = new MethodNode(ACC_PRIVATE | ACC_STATIC, "$est", "()V", null, null);
        tmp.tryCatchBlocks = new ArrayList<>();
        Map<LabelNode, LabelNode> lm = new HashMap<>();
        for (int i = 0; i < cutIdx; i++)
            if (all[i] instanceof LabelNode ln) lm.put(ln, new LabelNode());
        for (int i = 0; i < cutIdx; i++)
            tmp.instructions.add(all[i].clone(lm));
        tmp.instructions.add(new InsnNode(RETURN));
        return methodSize(tmp);
    }

    // ── Guard predicates ─────────────────────────────────────────────────

    private static boolean forwardJumpCrossed(AbstractInsnNode[] all, int cutIdx,
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

    private static boolean tailStartsAtHandler(AbstractInsnNode[] all, int cutIdx,
                                                Set<LabelNode> handlerLabels) {
        for (int i = cutIdx; i < all.length; i++) {
            AbstractInsnNode n = all[i];
            if (n instanceof LabelNode ln && handlerLabels.contains(ln)) return true;
            if (n.getOpcode() >= 0) break;
        }
        return false;
    }

    private static Set<LabelNode> collectHandlerLabels(MethodNode mn) {
        Set<LabelNode> s = new HashSet<>();
        if (mn.tryCatchBlocks != null)
            for (TryCatchBlockNode tcb : mn.tryCatchBlocks) s.add(tcb.handler);
        return s;
    }

    private static Set<LabelNode> collectJumpTargets(MethodNode mn) {
        Set<LabelNode> s = new HashSet<>();
        for (AbstractInsnNode n : mn.instructions) {
            if (n instanceof JumpInsnNode jin)       s.add(jin.label);
            if (n instanceof TableSwitchInsnNode ts) { s.add(ts.dflt); s.addAll(ts.labels); }
            if (n instanceof LookupSwitchInsnNode ls){ s.add(ls.dflt); s.addAll(ls.labels); }
        }
        if (mn.tryCatchBlocks != null)
            for (TryCatchBlockNode tcb : mn.tryCatchBlocks) s.add(tcb.handler);
        return s;
    }

    private static AbstractInsnNode prevReal(AbstractInsnNode n) {
        n = n.getPrevious();
        while (n != null && n.getOpcode() < 0) n = n.getPrevious();
        return n;
    }
}