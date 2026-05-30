package war.metaphor.mutator.virtualization;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

public final class VmEncoder implements Opcodes {

    public static final String POOL_DESC = "[[Ljava/lang/Object;";
    public static final String BYTECODE_DESC = "[I";

    private final List<Integer> bytecode = new ArrayList<>();
    private final List<Object>  constPool = new ArrayList<>();
    private final Map<LabelNode, Integer> labelOffsets = new HashMap<>();
    private final List<int[]> patchSites = new ArrayList<>();

    private int poolIdx(Object o) {
        int idx = constPool.indexOf(o);
        if (idx == -1) {
            idx = constPool.size();
            constPool.add(o);
        }
        return idx;
    }

    private void emit(int... words) {
        for (int w : words) bytecode.add(w);
    }

    private void emitJump(int vmOp, LabelNode target) {
        emit(vmOp);
        int site = bytecode.size();
        emit(0);
        patchSites.add(new int[]{site, System.identityHashCode(target)});
        labelOffsets.putIfAbsent(target, -1);
    }

    public Result encode(MethodNode mn) {
        AbstractInsnNode[] insns = mn.instructions.toArray();

        for (AbstractInsnNode insn : insns) {
            if (insn instanceof LabelNode ln) {
                labelOffsets.put(ln, bytecode.size());
            }
        }

        Map<LabelNode, Integer> labelIdentity = new IdentityHashMap<>();
        for (AbstractInsnNode insn : insns) {
            if (insn instanceof LabelNode ln) {
                labelIdentity.put(ln, bytecode.size());
            }
        }

        bytecode.clear();
        constPool.clear();
        patchSites.clear();
        labelOffsets.clear();

        Map<LabelNode, List<int[]>> forwardPatches = new IdentityHashMap<>();

        for (AbstractInsnNode insn : insns) {
            int op = insn.getOpcode();
            if (insn instanceof LabelNode ln) {
                labelOffsets.put(ln, bytecode.size());
                List<int[]> patches = forwardPatches.remove(ln);
                if (patches != null) {
                    for (int[] p : patches) bytecode.set(p[0], bytecode.size());
                }
                continue;
            }
            if (insn instanceof LineNumberNode || insn instanceof FrameNode) continue;

            switch (op) {
                case NOP -> emit(VmOpcodes.NOP);
                case ACONST_NULL -> emit(VmOpcodes.ACONST_NULL);
                case ICONST_M1 -> emit(VmOpcodes.IPUSH, -1);
                case ICONST_0  -> emit(VmOpcodes.IPUSH, 0);
                case ICONST_1  -> emit(VmOpcodes.IPUSH, 1);
                case ICONST_2  -> emit(VmOpcodes.IPUSH, 2);
                case ICONST_3  -> emit(VmOpcodes.IPUSH, 3);
                case ICONST_4  -> emit(VmOpcodes.IPUSH, 4);
                case ICONST_5  -> emit(VmOpcodes.IPUSH, 5);
                case LCONST_0  -> emit(VmOpcodes.LPUSH, poolIdx(0L));
                case LCONST_1  -> emit(VmOpcodes.LPUSH, poolIdx(1L));
                case FCONST_0  -> emit(VmOpcodes.FPUSH, poolIdx(0.0f));
                case FCONST_1  -> emit(VmOpcodes.FPUSH, poolIdx(1.0f));
                case FCONST_2  -> emit(VmOpcodes.FPUSH, poolIdx(2.0f));
                case DCONST_0  -> emit(VmOpcodes.DPUSH, poolIdx(0.0));
                case DCONST_1  -> emit(VmOpcodes.DPUSH, poolIdx(1.0));
                case BIPUSH, SIPUSH -> emit(VmOpcodes.IPUSH, ((IntInsnNode) insn).operand);
                case LDC -> {
                    Object cst = ((LdcInsnNode) insn).cst;
                    if (cst instanceof Integer iv) {
                        emit(VmOpcodes.IPUSH, iv);
                    } else if (cst instanceof Long lv) {
                        emit(VmOpcodes.LPUSH, poolIdx(lv));
                    } else if (cst instanceof Float fv) {
                        emit(VmOpcodes.FPUSH, poolIdx(fv));
                    } else if (cst instanceof Double dv) {
                        emit(VmOpcodes.DPUSH, poolIdx(dv));
                    } else {
                        emit(VmOpcodes.APUSH, poolIdx(cst));
                    }
                }
                case ILOAD -> emit(VmOpcodes.ILOAD, ((VarInsnNode) insn).var);
                case LLOAD -> emit(VmOpcodes.LLOAD, ((VarInsnNode) insn).var);
                case FLOAD -> emit(VmOpcodes.FLOAD, ((VarInsnNode) insn).var);
                case DLOAD -> emit(VmOpcodes.DLOAD, ((VarInsnNode) insn).var);
                case ALOAD -> emit(VmOpcodes.ALOAD, ((VarInsnNode) insn).var);
                case ISTORE -> emit(VmOpcodes.ISTORE, ((VarInsnNode) insn).var);
                case LSTORE -> emit(VmOpcodes.LSTORE, ((VarInsnNode) insn).var);
                case FSTORE -> emit(VmOpcodes.FSTORE, ((VarInsnNode) insn).var);
                case DSTORE -> emit(VmOpcodes.DSTORE, ((VarInsnNode) insn).var);
                case ASTORE -> emit(VmOpcodes.ASTORE, ((VarInsnNode) insn).var);
                case IALOAD -> emit(VmOpcodes.IALOAD);
                case LALOAD -> emit(VmOpcodes.LALOAD);
                case FALOAD -> emit(VmOpcodes.FALOAD);
                case DALOAD -> emit(VmOpcodes.DALOAD);
                case AALOAD -> emit(VmOpcodes.AALOAD);
                case BALOAD -> emit(VmOpcodes.BALOAD);
                case CALOAD -> emit(VmOpcodes.CALOAD);
                case SALOAD -> emit(VmOpcodes.SALOAD);
                case IASTORE -> emit(VmOpcodes.IASTORE);
                case LASTORE -> emit(VmOpcodes.LASTORE);
                case FASTORE -> emit(VmOpcodes.FASTORE);
                case DASTORE -> emit(VmOpcodes.DASTORE);
                case AASTORE -> emit(VmOpcodes.AASTORE);
                case BASTORE -> emit(VmOpcodes.BASTORE);
                case CASTORE -> emit(VmOpcodes.CASTORE);
                case SASTORE -> emit(VmOpcodes.SASTORE);
                case POP  -> emit(VmOpcodes.POP);
                case POP2 -> emit(VmOpcodes.POP2);
                case DUP      -> emit(VmOpcodes.DUP);
                case DUP_X1   -> emit(VmOpcodes.DUP_X1);
                case DUP_X2   -> emit(VmOpcodes.DUP_X2);
                case DUP2     -> emit(VmOpcodes.DUP2);
                case DUP2_X1  -> emit(VmOpcodes.DUP2_X1);
                case DUP2_X2  -> emit(VmOpcodes.DUP2_X2);
                case SWAP  -> emit(VmOpcodes.SWAP);
                case IADD  -> emit(VmOpcodes.IADD);
                case LADD  -> emit(VmOpcodes.LADD);
                case FADD  -> emit(VmOpcodes.FADD);
                case DADD  -> emit(VmOpcodes.DADD);
                case ISUB  -> emit(VmOpcodes.ISUB);
                case LSUB  -> emit(VmOpcodes.LSUB);
                case FSUB  -> emit(VmOpcodes.FSUB);
                case DSUB  -> emit(VmOpcodes.DSUB);
                case IMUL  -> emit(VmOpcodes.IMUL);
                case LMUL  -> emit(VmOpcodes.LMUL);
                case FMUL  -> emit(VmOpcodes.FMUL);
                case DMUL  -> emit(VmOpcodes.DMUL);
                case IDIV  -> emit(VmOpcodes.IDIV);
                case LDIV  -> emit(VmOpcodes.LDIV);
                case FDIV  -> emit(VmOpcodes.FDIV);
                case DDIV  -> emit(VmOpcodes.DDIV);
                case IREM  -> emit(VmOpcodes.IREM);
                case LREM  -> emit(VmOpcodes.LREM);
                case FREM  -> emit(VmOpcodes.FREM);
                case DREM  -> emit(VmOpcodes.DREM);
                case INEG  -> emit(VmOpcodes.INEG);
                case LNEG  -> emit(VmOpcodes.LNEG);
                case FNEG  -> emit(VmOpcodes.FNEG);
                case DNEG  -> emit(VmOpcodes.DNEG);
                case ISHL  -> emit(VmOpcodes.ISHL);
                case LSHL  -> emit(VmOpcodes.LSHL);
                case ISHR  -> emit(VmOpcodes.ISHR);
                case LSHR  -> emit(VmOpcodes.LSHR);
                case IUSHR -> emit(VmOpcodes.IUSHR);
                case LUSHR -> emit(VmOpcodes.LUSHR);
                case IAND  -> emit(VmOpcodes.IAND);
                case LAND  -> emit(VmOpcodes.LAND);
                case IOR   -> emit(VmOpcodes.IOR);
                case LOR   -> emit(VmOpcodes.LOR);
                case IXOR  -> emit(VmOpcodes.IXOR);
                case LXOR  -> emit(VmOpcodes.LXOR);
                case I2L   -> emit(VmOpcodes.I2L);
                case I2F   -> emit(VmOpcodes.I2F);
                case I2D   -> emit(VmOpcodes.I2D);
                case L2I   -> emit(VmOpcodes.L2I);
                case L2F   -> emit(VmOpcodes.L2F);
                case L2D   -> emit(VmOpcodes.L2D);
                case F2I   -> emit(VmOpcodes.F2I);
                case F2L   -> emit(VmOpcodes.F2L);
                case F2D   -> emit(VmOpcodes.F2D);
                case D2I   -> emit(VmOpcodes.D2I);
                case D2L   -> emit(VmOpcodes.D2L);
                case D2F   -> emit(VmOpcodes.D2F);
                case I2B   -> emit(VmOpcodes.I2B);
                case I2C   -> emit(VmOpcodes.I2C);
                case I2S   -> emit(VmOpcodes.I2S);
                case LCMP  -> emit(VmOpcodes.LCMP);
                case FCMPL -> emit(VmOpcodes.FCMPL);
                case FCMPG -> emit(VmOpcodes.FCMPG);
                case DCMPL -> emit(VmOpcodes.DCMPL);
                case DCMPG -> emit(VmOpcodes.DCMPG);
                case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE,
                     IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE,
                     IF_ACMPEQ, IF_ACMPNE, IFNULL, IFNONNULL -> {
                    JumpInsnNode ji = (JumpInsnNode) insn;
                    int vmOp = jvmJumpToVm(op);
                    emit(vmOp);
                    int site = bytecode.size();
                    emit(0);
                    forwardPatches.computeIfAbsent(ji.label, k -> new ArrayList<>()).add(new int[]{site});
                }
                case GOTO -> {
                    JumpInsnNode ji = (JumpInsnNode) insn;
                    emit(VmOpcodes.GOTO);
                    int site = bytecode.size();
                    emit(0);
                    forwardPatches.computeIfAbsent(ji.label, k -> new ArrayList<>()).add(new int[]{site});
                }
                case IRETURN -> emit(VmOpcodes.IRETURN);
                case LRETURN -> emit(VmOpcodes.LRETURN);
                case FRETURN -> emit(VmOpcodes.FRETURN);
                case DRETURN -> emit(VmOpcodes.DRETURN);
                case ARETURN -> emit(VmOpcodes.ARETURN);
                case RETURN  -> emit(VmOpcodes.RETURN);
                case GETSTATIC -> {
                    FieldInsnNode fi = (FieldInsnNode) insn;
                    emit(VmOpcodes.GETSTATIC, poolIdx(new String[]{fi.owner, fi.name, fi.desc}));
                }
                case PUTSTATIC -> {
                    FieldInsnNode fi = (FieldInsnNode) insn;
                    emit(VmOpcodes.PUTSTATIC, poolIdx(new String[]{fi.owner, fi.name, fi.desc}));
                }
                case GETFIELD -> {
                    FieldInsnNode fi = (FieldInsnNode) insn;
                    emit(VmOpcodes.GETFIELD, poolIdx(new String[]{fi.owner, fi.name, fi.desc}));
                }
                case PUTFIELD -> {
                    FieldInsnNode fi = (FieldInsnNode) insn;
                    emit(VmOpcodes.PUTFIELD, poolIdx(new String[]{fi.owner, fi.name, fi.desc}));
                }
                case INVOKEVIRTUAL -> {
                    MethodInsnNode mi = (MethodInsnNode) insn;
                    emit(VmOpcodes.INVOKEVIRTUAL, poolIdx(new String[]{mi.owner, mi.name, mi.desc}));
                }
                case INVOKESPECIAL -> {
                    MethodInsnNode mi = (MethodInsnNode) insn;
                    emit(VmOpcodes.INVOKESPECIAL, poolIdx(new String[]{mi.owner, mi.name, mi.desc}));
                }
                case INVOKESTATIC -> {
                    MethodInsnNode mi = (MethodInsnNode) insn;
                    emit(VmOpcodes.INVOKESTATIC, poolIdx(new String[]{mi.owner, mi.name, mi.desc}));
                }
                case INVOKEINTERFACE -> {
                    MethodInsnNode mi = (MethodInsnNode) insn;
                    emit(VmOpcodes.INVOKEINTERFACE, poolIdx(new String[]{mi.owner, mi.name, mi.desc}));
                }
                case NEW -> emit(VmOpcodes.NEW, poolIdx(((TypeInsnNode) insn).desc));
                case NEWARRAY -> emit(VmOpcodes.NEWARRAY, ((IntInsnNode) insn).operand);
                case ANEWARRAY -> emit(VmOpcodes.ANEWARRAY, poolIdx(((TypeInsnNode) insn).desc));
                case ARRAYLENGTH -> emit(VmOpcodes.ARRAYLENGTH);
                case ATHROW -> emit(VmOpcodes.ATHROW);
                case CHECKCAST -> emit(VmOpcodes.CHECKCAST, poolIdx(((TypeInsnNode) insn).desc));
                case INSTANCEOF -> emit(VmOpcodes.INSTANCEOF, poolIdx(((TypeInsnNode) insn).desc));
                case MONITORENTER -> emit(VmOpcodes.MONITORENTER);
                case MONITOREXIT  -> emit(VmOpcodes.MONITOREXIT);
                case IINC -> {
                    IincInsnNode ii = (IincInsnNode) insn;
                    emit(VmOpcodes.IINC, ii.var, ii.incr);
                }
                case TABLESWITCH -> {
                    TableSwitchInsnNode ts = (TableSwitchInsnNode) insn;
                    emit(VmOpcodes.TABLESWITCH, ts.min, ts.max);
                    int dfltSite = bytecode.size(); emit(0);
                    forwardPatches.computeIfAbsent(ts.dflt, k -> new ArrayList<>()).add(new int[]{dfltSite});
                    for (LabelNode lbl : ts.labels) {
                        int s = bytecode.size(); emit(0);
                        forwardPatches.computeIfAbsent(lbl, k -> new ArrayList<>()).add(new int[]{s});
                    }
                }
                case LOOKUPSWITCH -> {
                    LookupSwitchInsnNode ls = (LookupSwitchInsnNode) insn;
                    emit(VmOpcodes.LOOKUPSWITCH, ls.keys.size());
                    int dfltSite = bytecode.size(); emit(0);
                    forwardPatches.computeIfAbsent(ls.dflt, k -> new ArrayList<>()).add(new int[]{dfltSite});
                    for (int k = 0; k < ls.keys.size(); k++) {
                        emit(ls.keys.get(k));
                        int s = bytecode.size(); emit(0);
                        forwardPatches.computeIfAbsent(ls.labels.get(k), k2 -> new ArrayList<>()).add(new int[]{s});
                    }
                }
                case MULTIANEWARRAY -> {
                    MultiANewArrayInsnNode mn2 = (MultiANewArrayInsnNode) insn;
                    emit(VmOpcodes.MULTIANEWARRAY, poolIdx(mn2.desc), mn2.dims);
                }
            }
        }

        for (Map.Entry<LabelNode, List<int[]>> e : forwardPatches.entrySet()) {
            Integer offset = labelOffsets.get(e.getKey());
            if (offset != null) {
                for (int[] s : e.getValue()) bytecode.set(s[0], offset);
            }
        }

        int[] bc = bytecode.stream().mapToInt(Integer::intValue).toArray();

        Object[][] pool = new Object[constPool.size()][];
        for (int i = 0; i < constPool.size(); i++) {
            Object entry = constPool.get(i);
            if (entry instanceof String[] sa) {
                pool[i] = sa;
            } else {
                pool[i] = new Object[]{entry};
            }
        }

        return new Result(bc, pool);
    }

    private static int jvmJumpToVm(int jvmOp) {
        return switch (jvmOp) {
            case IFEQ      -> VmOpcodes.IFEQ;
            case IFNE      -> VmOpcodes.IFNE;
            case IFLT      -> VmOpcodes.IFLT;
            case IFGE      -> VmOpcodes.IFGE;
            case IFGT      -> VmOpcodes.IFGT;
            case IFLE      -> VmOpcodes.IFLE;
            case IF_ICMPEQ -> VmOpcodes.IF_ICMPEQ;
            case IF_ICMPNE -> VmOpcodes.IF_ICMPNE;
            case IF_ICMPLT -> VmOpcodes.IF_ICMPLT;
            case IF_ICMPGE -> VmOpcodes.IF_ICMPGE;
            case IF_ICMPGT -> VmOpcodes.IF_ICMPGT;
            case IF_ICMPLE -> VmOpcodes.IF_ICMPLE;
            case IF_ACMPEQ -> VmOpcodes.IF_ACMPEQ;
            case IF_ACMPNE -> VmOpcodes.IF_ACMPNE;
            case IFNULL    -> VmOpcodes.IFNULL;
            case IFNONNULL -> VmOpcodes.IFNONNULL;
            default -> throw new IllegalArgumentException("not a jump: " + jvmOp);
        };
    }

    public record Result(int[] bytecode, Object[][] constPool) {}
}
