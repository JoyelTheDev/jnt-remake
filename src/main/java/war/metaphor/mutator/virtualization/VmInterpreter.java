package war.metaphor.mutator.virtualization;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

public final class VmInterpreter implements Opcodes {

    private VmInterpreter() {}

    public static MethodNode generate(String interpreterOwner, String methodName, String methodDesc,
                                      int maxLocals, boolean isStatic) {
        MethodNode mn = new MethodNode(
                ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC,
                methodName,
                "(I[[Ljava/lang/Object;[I" + (isStatic ? "" : "Ljava/lang/Object;") + "[Ljava/lang/Object;)Ljava/lang/Object;",
                null, new String[]{"java/lang/Exception"});

        InsnList il = new InsnList();

        int argBC    = 0;
        int argPool  = 1;
        int argBytecode = 2;
        int argThis  = isStatic ? -1 : 3;
        int argLocals = isStatic ? 3 : 4;

        int localPC    = argLocals + 1;
        int localStack = argLocals + 2;
        int localSP    = argLocals + 3;
        int localOp    = argLocals + 4;
        int localTmp1  = argLocals + 5;
        int localTmp2  = argLocals + 6;
        int localTmp3  = argLocals + 7;

        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd   = new LabelNode();

        il.add(new IntInsnNode(SIPUSH, 256));
        il.add(new TypeInsnNode(ANEWARRAY, "java/lang/Object"));
        il.add(new VarInsnNode(ASTORE, localStack));
        il.add(new InsnNode(ICONST_M1));
        il.add(new VarInsnNode(ISTORE, localSP));
        il.add(new InsnNode(ICONST_0));
        il.add(new VarInsnNode(ISTORE, localPC));

        il.add(loopStart);

        il.add(new VarInsnNode(ILOAD, localPC));
        il.add(new VarInsnNode(ALOAD, argBytecode));
        il.add(new InsnNode(ARRAYLENGTH));
        LabelNode guardOk = new LabelNode();
        il.add(new JumpInsnNode(IF_ICMPLT, guardOk));
        il.add(new InsnNode(ACONST_NULL));
        il.add(new InsnNode(ARETURN));
        il.add(guardOk);

        il.add(new VarInsnNode(ALOAD, argBytecode));
        il.add(new VarInsnNode(ILOAD, localPC));
        il.add(new InsnNode(IALOAD));
        il.add(new VarInsnNode(ISTORE, localOp));
        il.add(new IincInsnNode(localPC, 1));

        int numOpcodes = 140;
        int[] keys = buildOpcodeKeys();
        LabelNode[] handlers = new LabelNode[keys.length];
        for (int i = 0; i < handlers.length; i++) handlers[i] = new LabelNode();
        LabelNode defaultLabel = new LabelNode();

        il.add(new VarInsnNode(ILOAD, localOp));
        il.add(new LookupSwitchInsnNode(defaultLabel, keys, handlers));

        int hi = 0;

        il.add(handlers[hi++]);
        il.add(new JumpInsnNode(GOTO, loopStart));

        il.add(handlers[hi++]);
        il.add(new InsnNode(ACONST_NULL));
        il.add(new IincInsnNode(localSP, 1));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loopStart));

        addIPUSH(il, handlers, hi++, argBytecode, localPC, localStack, localSP, loopStart);
        addLPUSH(il, handlers, hi++, argPool, argBytecode, localPC, localStack, localSP, loopStart);
        addFPUSH(il, handlers, hi++, argPool, argBytecode, localPC, localStack, localSP, loopStart);
        addDPUSH(il, handlers, hi++, argPool, argBytecode, localPC, localStack, localSP, loopStart);
        addAPUSH(il, handlers, hi++, argPool, argBytecode, localPC, localStack, localSP, loopStart);

        addVarLoad(il, handlers, hi++, argLocals, localStack, localSP, argBytecode, localPC, loopStart, "I");
        addVarLoad(il, handlers, hi++, argLocals, localStack, localSP, argBytecode, localPC, loopStart, "J");
        addVarLoad(il, handlers, hi++, argLocals, localStack, localSP, argBytecode, localPC, loopStart, "F");
        addVarLoad(il, handlers, hi++, argLocals, localStack, localSP, argBytecode, localPC, loopStart, "D");
        addVarLoad(il, handlers, hi++, argLocals, localStack, localSP, argBytecode, localPC, loopStart, "A");
        addVarStore(il, handlers, hi++, argLocals, localStack, localSP, argBytecode, localPC, loopStart, "I");
        addVarStore(il, handlers, hi++, argLocals, localStack, localSP, argBytecode, localPC, loopStart, "J");
        addVarStore(il, handlers, hi++, argLocals, localStack, localSP, argBytecode, localPC, loopStart, "F");
        addVarStore(il, handlers, hi++, argLocals, localStack, localSP, argBytecode, localPC, loopStart, "D");
        addVarStore(il, handlers, hi++, argLocals, localStack, localSP, argBytecode, localPC, loopStart, "A");

        addPOP(il, handlers, hi++, localStack, localSP, loopStart);
        addPOP2(il, handlers, hi++, localStack, localSP, loopStart);
        addDUP(il, handlers, hi++, localStack, localSP, loopStart);
        addDUP_X1(il, handlers, hi++, localStack, localSP, loopStart);
        addDUP_X2(il, handlers, hi++, localStack, localSP, loopStart);
        addDUP2(il, handlers, hi++, localStack, localSP, loopStart);
        addDUP2_X1(il, handlers, hi++, localStack, localSP, loopStart);
        addDUP2_X2(il, handlers, hi++, localStack, localSP, loopStart);
        addSWAP(il, handlers, hi++, localStack, localSP, loopStart);

        addIntArith(il, handlers, hi++, localStack, localSP, loopStart, IADD, "I");
        addLongArith(il, handlers, hi++, localStack, localSP, loopStart, LADD);
        addFloatArith(il, handlers, hi++, localStack, localSP, loopStart, FADD);
        addDoubleArith(il, handlers, hi++, localStack, localSP, loopStart, DADD);
        addIntArith(il, handlers, hi++, localStack, localSP, loopStart, ISUB, "I");
        addLongArith(il, handlers, hi++, localStack, localSP, loopStart, LSUB);
        addFloatArith(il, handlers, hi++, localStack, localSP, loopStart, FSUB);
        addDoubleArith(il, handlers, hi++, localStack, localSP, loopStart, DSUB);
        addIntArith(il, handlers, hi++, localStack, localSP, loopStart, IMUL, "I");
        addLongArith(il, handlers, hi++, localStack, localSP, loopStart, LMUL);
        addFloatArith(il, handlers, hi++, localStack, localSP, loopStart, FMUL);
        addDoubleArith(il, handlers, hi++, localStack, localSP, loopStart, DMUL);
        addIntArith(il, handlers, hi++, localStack, localSP, loopStart, IDIV, "I");
        addLongArith(il, handlers, hi++, localStack, localSP, loopStart, LDIV);
        addFloatArith(il, handlers, hi++, localStack, localSP, loopStart, FDIV);
        addDoubleArith(il, handlers, hi++, localStack, localSP, loopStart, DDIV);
        addIntArith(il, handlers, hi++, localStack, localSP, loopStart, IREM, "I");
        addLongArith(il, handlers, hi++, localStack, localSP, loopStart, LREM);
        addFloatArith(il, handlers, hi++, localStack, localSP, loopStart, FREM);
        addDoubleArith(il, handlers, hi++, localStack, localSP, loopStart, DREM);
        addINEG(il, handlers, hi++, localStack, localSP, loopStart);
        addLNEG(il, handlers, hi++, localStack, localSP, loopStart);
        addFNEG(il, handlers, hi++, localStack, localSP, loopStart);
        addDNEG(il, handlers, hi++, localStack, localSP, loopStart);
        addShift(il, handlers, hi++, localStack, localSP, loopStart, ISHL);
        addLShift(il, handlers, hi++, localStack, localSP, loopStart, LSHL);
        addShift(il, handlers, hi++, localStack, localSP, loopStart, ISHR);
        addLShift(il, handlers, hi++, localStack, localSP, loopStart, LSHR);
        addShift(il, handlers, hi++, localStack, localSP, loopStart, IUSHR);
        addLShift(il, handlers, hi++, localStack, localSP, loopStart, LUSHR);
        addIntArith(il, handlers, hi++, localStack, localSP, loopStart, IAND, "I");
        addLongArith(il, handlers, hi++, localStack, localSP, loopStart, LAND);
        addIntArith(il, handlers, hi++, localStack, localSP, loopStart, IOR, "I");
        addLongArith(il, handlers, hi++, localStack, localSP, loopStart, LOR);
        addIntArith(il, handlers, hi++, localStack, localSP, loopStart, IXOR, "I");
        addLongArith(il, handlers, hi++, localStack, localSP, loopStart, LXOR);

        addConvert(il, handlers, hi++, localStack, localSP, loopStart, "I", "J", I2L);
        addConvert(il, handlers, hi++, localStack, localSP, loopStart, "I", "F", I2F);
        addConvert(il, handlers, hi++, localStack, localSP, loopStart, "I", "D", I2D);
        addConvert(il, handlers, hi++, localStack, localSP, loopStart, "J", "I", L2I);
        addConvert(il, handlers, hi++, localStack, localSP, loopStart, "J", "F", L2F);
        addConvert(il, handlers, hi++, localStack, localSP, loopStart, "J", "D", L2D);
        addConvert(il, handlers, hi++, localStack, localSP, loopStart, "F", "I", F2I);
        addConvert(il, handlers, hi++, localStack, localSP, loopStart, "F", "J", F2L);
        addConvert(il, handlers, hi++, localStack, localSP, loopStart, "F", "D", F2D);
        addConvert(il, handlers, hi++, localStack, localSP, loopStart, "D", "I", D2I);
        addConvert(il, handlers, hi++, localStack, localSP, loopStart, "D", "J", D2L);
        addConvert(il, handlers, hi++, localStack, localSP, loopStart, "D", "F", D2F);
        addConvert(il, handlers, hi++, localStack, localSP, loopStart, "I", "B", I2B);
        addConvert(il, handlers, hi++, localStack, localSP, loopStart, "I", "C", I2C);
        addConvert(il, handlers, hi++, localStack, localSP, loopStart, "I", "S", I2S);

        addLCMP(il, handlers, hi++, localStack, localSP, loopStart);
        addFCMP(il, handlers, hi++, localStack, localSP, loopStart, false);
        addFCMP(il, handlers, hi++, localStack, localSP, loopStart, true);
        addDCMP(il, handlers, hi++, localStack, localSP, loopStart, false);
        addDCMP(il, handlers, hi++, localStack, localSP, loopStart, true);

        addIfZ(il, handlers, hi++, argBytecode, localPC, localStack, localSP, loopStart, IFEQ);
        addIfZ(il, handlers, hi++, argBytecode, localPC, localStack, localSP, loopStart, IFNE);
        addIfZ(il, handlers, hi++, argBytecode, localPC, localStack, localSP, loopStart, IFLT);
        addIfZ(il, handlers, hi++, argBytecode, localPC, localStack, localSP, loopStart, IFGE);
        addIfZ(il, handlers, hi++, argBytecode, localPC, localStack, localSP, loopStart, IFGT);
        addIfZ(il, handlers, hi++, argBytecode, localPC, localStack, localSP, loopStart, IFLE);
        addIfICMP(il, handlers, hi++, argBytecode, localPC, localStack, localSP, loopStart, IF_ICMPEQ);
        addIfICMP(il, handlers, hi++, argBytecode, localPC, localStack, localSP, loopStart, IF_ICMPNE);
        addIfICMP(il, handlers, hi++, argBytecode, localPC, localStack, localSP, loopStart, IF_ICMPLT);
        addIfICMP(il, handlers, hi++, argBytecode, localPC, localStack, localSP, loopStart, IF_ICMPGE);
        addIfICMP(il, handlers, hi++, argBytecode, localPC, localStack, localSP, loopStart, IF_ICMPGT);
        addIfICMP(il, handlers, hi++, argBytecode, localPC, localStack, localSP, loopStart, IF_ICMPLE);
        addIfACMP(il, handlers, hi++, argBytecode, localPC, localStack, localSP, loopStart, IF_ACMPEQ);
        addIfACMP(il, handlers, hi++, argBytecode, localPC, localStack, localSP, loopStart, IF_ACMPNE);
        addGOTO(il, handlers, hi++, argBytecode, localPC, loopStart);

        addIRETURN(il, handlers, hi++, localStack, localSP);
        addLRETURN(il, handlers, hi++, localStack, localSP);
        addFRETURN(il, handlers, hi++, localStack, localSP);
        addDRETURN(il, handlers, hi++, localStack, localSP);
        addARETURN(il, handlers, hi++, localStack, localSP);
        addVOIDRETURN(il, handlers, hi++);

        addGETSTATIC(il, handlers, hi++, argPool, argBytecode, localPC, localStack, localSP, loopStart);
        addPUTSTATIC(il, handlers, hi++, argPool, argBytecode, localPC, localStack, localSP, loopStart);
        addGETFIELD(il, handlers, hi++, argPool, argBytecode, localPC, localStack, localSP, loopStart);
        addPUTFIELD(il, handlers, hi++, argPool, argBytecode, localPC, localStack, localSP, loopStart);

        addINVOKE(il, handlers, hi++, argPool, argBytecode, localPC, localStack, localSP, loopStart, INVOKEVIRTUAL);
        addINVOKE(il, handlers, hi++, argPool, argBytecode, localPC, localStack, localSP, loopStart, INVOKESPECIAL);
        addINVOKE(il, handlers, hi++, argPool, argBytecode, localPC, localStack, localSP, loopStart, INVOKESTATIC);
        addINVOKE(il, handlers, hi++, argPool, argBytecode, localPC, localStack, localSP, loopStart, INVOKEINTERFACE);

        addNEW(il, handlers, hi++, argPool, argBytecode, localPC, localStack, localSP, loopStart);
        addNEWARRAY(il, handlers, hi++, argBytecode, localPC, localStack, localSP, loopStart);
        addANEWARRAY(il, handlers, hi++, argPool, argBytecode, localPC, localStack, localSP, loopStart);
        addARRAYLENGTH(il, handlers, hi++, localStack, localSP, loopStart);
        addATHROW(il, handlers, hi++, localStack, localSP);
        addCHECKCAST(il, handlers, hi++, argPool, argBytecode, localPC, localStack, localSP, loopStart);
        addINSTANCEOF(il, handlers, hi++, argPool, argBytecode, localPC, localStack, localSP, loopStart);
        addIFNULL(il, handlers, hi++, argBytecode, localPC, localStack, localSP, loopStart, true);
        addIFNULL(il, handlers, hi++, argBytecode, localPC, localStack, localSP, loopStart, false);
        addTABLESWITCH(il, handlers, hi++, argBytecode, localPC, localStack, localSP, loopStart);
        addLOOKUPSWITCH(il, handlers, hi++, argBytecode, localPC, localStack, localSP, loopStart);
        addMULTIANEWARRAY(il, handlers, hi++, argPool, argBytecode, localPC, localStack, localSP, loopStart);

        addArrayLoad(il, handlers, hi++, localStack, localSP, loopStart, "I");
        addArrayLoad(il, handlers, hi++, localStack, localSP, loopStart, "J");
        addArrayLoad(il, handlers, hi++, localStack, localSP, loopStart, "F");
        addArrayLoad(il, handlers, hi++, localStack, localSP, loopStart, "D");
        addArrayLoad(il, handlers, hi++, localStack, localSP, loopStart, "A");
        addArrayLoad(il, handlers, hi++, localStack, localSP, loopStart, "B");
        addArrayLoad(il, handlers, hi++, localStack, localSP, loopStart, "C");
        addArrayLoad(il, handlers, hi++, localStack, localSP, loopStart, "S");

        addArrayStore(il, handlers, hi++, localStack, localSP, loopStart, "I");
        addArrayStore(il, handlers, hi++, localStack, localSP, loopStart, "J");
        addArrayStore(il, handlers, hi++, localStack, localSP, loopStart, "F");
        addArrayStore(il, handlers, hi++, localStack, localSP, loopStart, "D");
        addArrayStore(il, handlers, hi++, localStack, localSP, loopStart, "A");
        addArrayStore(il, handlers, hi++, localStack, localSP, loopStart, "B");
        addArrayStore(il, handlers, hi++, localStack, localSP, loopStart, "C");
        addArrayStore(il, handlers, hi++, localStack, localSP, loopStart, "S");

        addIINC(il, handlers, hi++, argBytecode, localPC, argLocals, loopStart);
        addMONITOR(il, handlers, hi++, localStack, localSP, loopStart, true);
        addMONITOR(il, handlers, hi++, localStack, localSP, loopStart, false);

        il.add(defaultLabel);
        il.add(new TypeInsnNode(NEW, "java/lang/IllegalStateException"));
        il.add(new InsnNode(DUP));
        il.add(new TypeInsnNode(NEW, "java/lang/StringBuilder"));
        il.add(new InsnNode(DUP));
        il.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
        il.add(new LdcInsnNode("Unknown VM opcode: "));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        il.add(new VarInsnNode(ILOAD, localOp));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
        il.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false));
        il.add(new InsnNode(ATHROW));

        il.add(loopEnd);
        il.add(new InsnNode(ACONST_NULL));
        il.add(new InsnNode(ARETURN));

        mn.instructions = il;
        mn.maxStack  = 16;
        mn.maxLocals = argLocals + 8;
        return mn;
    }

    private static void pushStack(InsnList il, int localStack, int localSP, int unboxedLocalOrNeg, String type, boolean fromLocal) {
        il.add(new IincInsnNode(localSP, 1));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        if (fromLocal) {
            il.add(new VarInsnNode(ILOAD, unboxedLocalOrNeg));
        }
        box(il, type);
        il.add(new InsnNode(AASTORE));
    }

    private static void peekStack(InsnList il, int localStack, int localSP) {
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(AALOAD));
    }

    private static void popStack(InsnList il, int localStack, int localSP, String type, int targetLocal) {
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(AALOAD));
        unbox(il, type);
        if (type.equals("J") || type.equals("D")) {
            if (type.equals("J")) il.add(new VarInsnNode(LSTORE, targetLocal));
            else il.add(new VarInsnNode(DSTORE, targetLocal));
        } else if (type.equals("F")) {
            il.add(new VarInsnNode(FSTORE, targetLocal));
        } else if (type.equals("A")) {
            il.add(new VarInsnNode(ASTORE, targetLocal));
        } else {
            il.add(new VarInsnNode(ISTORE, targetLocal));
        }
        il.add(new IincInsnNode(localSP, -1));
    }

    private static void box(InsnList il, String type) {
        switch (type) {
            case "I", "B", "C", "S" ->
                il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
            case "J" ->
                il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false));
            case "F" ->
                il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false));
            case "D" ->
                il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false));
            case "Z" ->
                il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
        }
    }

    private static void unbox(InsnList il, String type) {
        switch (type) {
            case "I" -> {
                il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
                il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false));
            }
            case "B" -> {
                il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
                il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false));
                il.add(new InsnNode(I2B));
            }
            case "C" -> {
                il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
                il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false));
                il.add(new InsnNode(I2C));
            }
            case "S" -> {
                il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
                il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false));
                il.add(new InsnNode(I2S));
            }
            case "J" -> {
                il.add(new TypeInsnNode(CHECKCAST, "java/lang/Long"));
                il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false));
            }
            case "F" -> {
                il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
                il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false));
            }
            case "D" -> {
                il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
                il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false));
            }
            case "A" -> il.add(new TypeInsnNode(CHECKCAST, "java/lang/Object"));
        }
    }

    private static void readBC(InsnList il, int argBytecode, int localPC) {
        il.add(new VarInsnNode(ALOAD, argBytecode));
        il.add(new VarInsnNode(ILOAD, localPC));
        il.add(new InsnNode(IALOAD));
        il.add(new IincInsnNode(localPC, 1));
    }

    private static void addIPUSH(InsnList il, LabelNode[] h, int hi, int argBytecode, int localPC,
                                   int localStack, int localSP, LabelNode loop) {
        il.add(h[hi]);
        readBC(il, argBytecode, localPC);
        il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
        il.add(new IincInsnNode(localSP, 1));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addLPUSH(InsnList il, LabelNode[] h, int hi, int argPool, int argBytecode, int localPC,
                                   int localStack, int localSP, LabelNode loop) {
        il.add(h[hi]);
        readBC(il, argBytecode, localPC);
        il.add(new VarInsnNode(ALOAD, argPool));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AALOAD));
        il.add(new InsnNode(ICONST_0));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Long"));
        il.add(new IincInsnNode(localSP, 1));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addFPUSH(InsnList il, LabelNode[] h, int hi, int argPool, int argBytecode, int localPC,
                                   int localStack, int localSP, LabelNode loop) {
        il.add(h[hi]);
        readBC(il, argBytecode, localPC);
        il.add(new VarInsnNode(ALOAD, argPool));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AALOAD));
        il.add(new InsnNode(ICONST_0));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Float"));
        il.add(new IincInsnNode(localSP, 1));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addDPUSH(InsnList il, LabelNode[] h, int hi, int argPool, int argBytecode, int localPC,
                                   int localStack, int localSP, LabelNode loop) {
        il.add(h[hi]);
        readBC(il, argBytecode, localPC);
        il.add(new VarInsnNode(ALOAD, argPool));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AALOAD));
        il.add(new InsnNode(ICONST_0));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Double"));
        il.add(new IincInsnNode(localSP, 1));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addAPUSH(InsnList il, LabelNode[] h, int hi, int argPool, int argBytecode, int localPC,
                                   int localStack, int localSP, LabelNode loop) {
        il.add(h[hi]);
        readBC(il, argBytecode, localPC);
        il.add(new VarInsnNode(ALOAD, argPool));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AALOAD));
        il.add(new InsnNode(ICONST_0));
        il.add(new InsnNode(AALOAD));
        il.add(new IincInsnNode(localSP, 1));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addVarLoad(InsnList il, LabelNode[] h, int hi, int argLocals, int localStack, int localSP,
                                    int argBytecode, int localPC, LabelNode loop, String type) {
        il.add(h[hi]);
        readBC(il, argBytecode, localPC);
        il.add(new VarInsnNode(ALOAD, argLocals));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AALOAD));
        if (!type.equals("A")) {
            unbox(il, type);
            box(il, type);
        }
        il.add(new IincInsnNode(localSP, 1));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addVarStore(InsnList il, LabelNode[] h, int hi, int argLocals, int localStack, int localSP,
                                     int argBytecode, int localPC, LabelNode loop, String type) {
        il.add(h[hi]);
        readBC(il, argBytecode, localPC);
        il.add(new VarInsnNode(ASTORE, localSP + 1));
        il.add(new VarInsnNode(ALOAD, argLocals));
        il.add(new VarInsnNode(ALOAD, localSP + 1));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(AALOAD));
        il.add(new InsnNode(AASTORE));
        il.add(new IincInsnNode(localSP, -1));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addPOP(InsnList il, LabelNode[] h, int hi, int localStack, int localSP, LabelNode loop) {
        il.add(h[hi]);
        il.add(new IincInsnNode(localSP, -1));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addPOP2(InsnList il, LabelNode[] h, int hi, int localStack, int localSP, LabelNode loop) {
        il.add(h[hi]);
        il.add(new IincInsnNode(localSP, -2));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addDUP(InsnList il, LabelNode[] h, int hi, int localStack, int localSP, LabelNode loop) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(AALOAD));
        il.add(new IincInsnNode(localSP, 1));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addDUP_X1(InsnList il, LabelNode[] h, int hi, int localStack, int localSP, LabelNode loop) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(AALOAD));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(ISUB));
        il.add(new InsnNode(AALOAD));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(ISUB));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(AALOAD));
        il.add(new InsnNode(AASTORE));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new IincInsnNode(localSP, 1));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addDUP_X2(InsnList il, LabelNode[] h, int hi, int localStack, int localSP, LabelNode loop) {
        addDUP_X1(il, h, hi, localStack, localSP, loop);
    }

    private static void addDUP2(InsnList il, LabelNode[] h, int hi, int localStack, int localSP, LabelNode loop) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(ISUB));
        il.add(new InsnNode(AALOAD));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(AALOAD));
        il.add(new IincInsnNode(localSP, 2));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(ISUB));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addDUP2_X1(InsnList il, LabelNode[] h, int hi, int ls, int sp, LabelNode loop) {
        addDUP2(il, h, hi, ls, sp, loop);
    }

    private static void addDUP2_X2(InsnList il, LabelNode[] h, int hi, int ls, int sp, LabelNode loop) {
        addDUP2(il, h, hi, ls, sp, loop);
    }

    private static void addSWAP(InsnList il, LabelNode[] h, int hi, int localStack, int localSP, LabelNode loop) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(AALOAD));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(ISUB));
        il.add(new InsnNode(AALOAD));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(ISUB));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addIntArith(InsnList il, LabelNode[] h, int hi, int localStack, int localSP,
                                     LabelNode loop, int jvmOp, String type) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(ISUB));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(jvmOp));
        il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
        il.add(new IincInsnNode(localSP, -1));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addLongArith(InsnList il, LabelNode[] h, int hi, int localStack, int localSP,
                                      LabelNode loop, int jvmOp) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Long"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(ISUB));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Long"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false));
        il.add(new InsnNode(DUP2_X2));
        il.add(new InsnNode(POP2));
        il.add(new InsnNode(jvmOp));
        il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false));
        il.add(new IincInsnNode(localSP, -1));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addFloatArith(InsnList il, LabelNode[] h, int hi, int localStack, int localSP,
                                       LabelNode loop, int jvmOp) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(ISUB));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false));
        il.add(new InsnNode(jvmOp));
        il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false));
        il.add(new IincInsnNode(localSP, -1));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addDoubleArith(InsnList il, LabelNode[] h, int hi, int localStack, int localSP,
                                        LabelNode loop, int jvmOp) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(ISUB));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false));
        il.add(new InsnNode(jvmOp));
        il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false));
        il.add(new IincInsnNode(localSP, -1));
        il.add(new VarInsnNode(ALOAD, localStack));
        il.add(new VarInsnNode(ILOAD, localSP));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addINEG(InsnList il, LabelNode[] h, int hi, int ls, int sp, LabelNode loop) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false));
        il.add(new InsnNode(INEG));
        il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addLNEG(InsnList il, LabelNode[] h, int hi, int ls, int sp, LabelNode loop) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Long"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false));
        il.add(new InsnNode(LNEG));
        il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addFNEG(InsnList il, LabelNode[] h, int hi, int ls, int sp, LabelNode loop) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false));
        il.add(new InsnNode(FNEG));
        il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addDNEG(InsnList il, LabelNode[] h, int hi, int ls, int sp, LabelNode loop) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false));
        il.add(new InsnNode(DNEG));
        il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addShift(InsnList il, LabelNode[] h, int hi, int ls, int sp, LabelNode loop, int jvmOp) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(ISUB));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(jvmOp));
        il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
        il.add(new IincInsnNode(sp, -1));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addLShift(InsnList il, LabelNode[] h, int hi, int ls, int sp, LabelNode loop, int jvmOp) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(ISUB));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Long"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false));
        il.add(new InsnNode(DUP2_X1));
        il.add(new InsnNode(POP2));
        il.add(new InsnNode(jvmOp));
        il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false));
        il.add(new IincInsnNode(sp, -1));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addConvert(InsnList il, LabelNode[] h, int hi, int ls, int sp, LabelNode loop,
                                    String from, String to, int jvmOp) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        unbox(il, from);
        il.add(new InsnNode(jvmOp));
        box(il, to);
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addLCMP(InsnList il, LabelNode[] h, int hi, int ls, int sp, LabelNode loop) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Long"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(ISUB));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Long"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false));
        il.add(new InsnNode(DUP2_X2));
        il.add(new InsnNode(POP2));
        il.add(new InsnNode(LCMP));
        il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
        il.add(new IincInsnNode(sp, -1));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addFCMP(InsnList il, LabelNode[] h, int hi, int ls, int sp, LabelNode loop, boolean g) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(ISUB));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(g ? FCMPG : FCMPL));
        il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
        il.add(new IincInsnNode(sp, -1));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addDCMP(InsnList il, LabelNode[] h, int hi, int ls, int sp, LabelNode loop, boolean g) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(ISUB));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false));
        il.add(new InsnNode(DUP2_X2));
        il.add(new InsnNode(POP2));
        il.add(new InsnNode(g ? DCMPG : DCMPL));
        il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
        il.add(new IincInsnNode(sp, -1));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addIfZ(InsnList il, LabelNode[] h, int hi, int argBytecode, int localPC,
                                 int ls, int sp, LabelNode loop, int jvmOp) {
        LabelNode taken = new LabelNode(), notTaken = new LabelNode();
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false));
        il.add(new IincInsnNode(sp, -1));
        il.add(new JumpInsnNode(jvmOp, taken));
        il.add(notTaken);
        il.add(new IincInsnNode(localPC, 1));
        il.add(new JumpInsnNode(GOTO, loop));
        il.add(taken);
        readBC(il, argBytecode, localPC);
        il.add(new VarInsnNode(ISTORE, localPC));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addIfICMP(InsnList il, LabelNode[] h, int hi, int argBytecode, int localPC,
                                   int ls, int sp, LabelNode loop, int jvmOp) {
        LabelNode taken = new LabelNode(), notTaken = new LabelNode();
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(ISUB));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false));
        il.add(new InsnNode(SWAP));
        il.add(new IincInsnNode(sp, -2));
        il.add(new JumpInsnNode(jvmOp, taken));
        il.add(notTaken);
        il.add(new IincInsnNode(localPC, 1));
        il.add(new JumpInsnNode(GOTO, loop));
        il.add(taken);
        readBC(il, argBytecode, localPC);
        il.add(new VarInsnNode(ISTORE, localPC));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addIfACMP(InsnList il, LabelNode[] h, int hi, int argBytecode, int localPC,
                                   int ls, int sp, LabelNode loop, int jvmOp) {
        LabelNode taken = new LabelNode(), notTaken = new LabelNode();
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(ISUB));
        il.add(new InsnNode(AALOAD));
        il.add(new IincInsnNode(sp, -2));
        il.add(new JumpInsnNode(jvmOp, taken));
        il.add(notTaken);
        il.add(new IincInsnNode(localPC, 1));
        il.add(new JumpInsnNode(GOTO, loop));
        il.add(taken);
        readBC(il, argBytecode, localPC);
        il.add(new VarInsnNode(ISTORE, localPC));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addGOTO(InsnList il, LabelNode[] h, int hi, int argBytecode, int localPC, LabelNode loop) {
        il.add(h[hi]);
        readBC(il, argBytecode, localPC);
        il.add(new VarInsnNode(ISTORE, localPC));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addIRETURN(InsnList il, LabelNode[] h, int hi, int ls, int sp) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new InsnNode(ARETURN));
    }

    private static void addLRETURN(InsnList il, LabelNode[] h, int hi, int ls, int sp) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new InsnNode(ARETURN));
    }

    private static void addFRETURN(InsnList il, LabelNode[] h, int hi, int ls, int sp) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new InsnNode(ARETURN));
    }

    private static void addDRETURN(InsnList il, LabelNode[] h, int hi, int ls, int sp) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new InsnNode(ARETURN));
    }

    private static void addARETURN(InsnList il, LabelNode[] h, int hi, int ls, int sp) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new InsnNode(ARETURN));
    }

    private static void addVOIDRETURN(InsnList il, LabelNode[] h, int hi) {
        il.add(h[hi]);
        il.add(new InsnNode(ACONST_NULL));
        il.add(new InsnNode(ARETURN));
    }

    private static void addGETSTATIC(InsnList il, LabelNode[] h, int hi, int argPool, int argBytecode, int localPC,
                                      int ls, int sp, LabelNode loop) {
        il.add(h[hi]);
        readBC(il, argBytecode, localPC);
        il.add(new VarInsnNode(ALOAD, argPool));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AALOAD));
        il.add(new InsnNode(DUP));
        il.add(new InsnNode(ICONST_0));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/String"));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(DUP));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/String"));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(ICONST_2));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/String"));
        il.add(new MethodInsnNode(INVOKESTATIC, "war/metaphor/mutator/virtualization/VmReflect",
                "getStatic", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false));
        il.add(new IincInsnNode(sp, 1));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addPUTSTATIC(InsnList il, LabelNode[] h, int hi, int argPool, int argBytecode, int localPC,
                                      int ls, int sp, LabelNode loop) {
        il.add(h[hi]);
        readBC(il, argBytecode, localPC);
        il.add(new VarInsnNode(ALOAD, argPool));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AALOAD));
        il.add(new InsnNode(DUP));
        il.add(new InsnNode(ICONST_0));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/String"));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(DUP));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/String"));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(ICONST_2));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/String"));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new IincInsnNode(sp, -1));
        il.add(new MethodInsnNode(INVOKESTATIC, "war/metaphor/mutator/virtualization/VmReflect",
                "putStatic", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V", false));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addGETFIELD(InsnList il, LabelNode[] h, int hi, int argPool, int argBytecode, int localPC,
                                     int ls, int sp, LabelNode loop) {
        il.add(h[hi]);
        readBC(il, argBytecode, localPC);
        il.add(new VarInsnNode(ALOAD, argPool));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AALOAD));
        il.add(new InsnNode(DUP));
        il.add(new InsnNode(ICONST_0));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/String"));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(DUP));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/String"));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(ICONST_2));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/String"));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new IincInsnNode(sp, -1));
        il.add(new MethodInsnNode(INVOKESTATIC, "war/metaphor/mutator/virtualization/VmReflect",
                "getField", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false));
        il.add(new IincInsnNode(sp, 1));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addPUTFIELD(InsnList il, LabelNode[] h, int hi, int argPool, int argBytecode, int localPC,
                                     int ls, int sp, LabelNode loop) {
        il.add(h[hi]);
        readBC(il, argBytecode, localPC);
        il.add(new VarInsnNode(ALOAD, argPool));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AALOAD));
        il.add(new InsnNode(DUP));
        il.add(new InsnNode(ICONST_0));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/String"));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(DUP));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/String"));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(ICONST_2));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/String"));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(ISUB));
        il.add(new InsnNode(AALOAD));
        il.add(new IincInsnNode(sp, -2));
        il.add(new MethodInsnNode(INVOKESTATIC, "war/metaphor/mutator/virtualization/VmReflect",
                "putField", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V", false));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addINVOKE(InsnList il, LabelNode[] h, int hi, int argPool, int argBytecode, int localPC,
                                   int ls, int sp, LabelNode loop, int kind) {
        il.add(h[hi]);
        readBC(il, argBytecode, localPC);
        il.add(new VarInsnNode(ALOAD, argPool));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AALOAD));
        il.add(new InsnNode(DUP));
        il.add(new InsnNode(ICONST_0));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/String"));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(DUP));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/String"));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(ICONST_2));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/String"));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new MethodInsnNode(INVOKESTATIC, "war/metaphor/mutator/virtualization/VmReflect",
                "invoke",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;I)Ljava/lang/Object;",
                false));
        il.add(new VarInsnNode(ISTORE, sp));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addNEW(InsnList il, LabelNode[] h, int hi, int argPool, int argBytecode, int localPC,
                                int ls, int sp, LabelNode loop) {
        il.add(h[hi]);
        readBC(il, argBytecode, localPC);
        il.add(new VarInsnNode(ALOAD, argPool));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AALOAD));
        il.add(new InsnNode(ICONST_0));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/String"));
        il.add(new MethodInsnNode(INVOKESTATIC, "war/metaphor/mutator/virtualization/VmReflect",
                "allocate", "(Ljava/lang/String;)Ljava/lang/Object;", false));
        il.add(new IincInsnNode(sp, 1));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addNEWARRAY(InsnList il, LabelNode[] h, int hi, int argBytecode, int localPC,
                                     int ls, int sp, LabelNode loop) {
        il.add(h[hi]);
        readBC(il, argBytecode, localPC);
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false));
        il.add(new MethodInsnNode(INVOKESTATIC, "war/metaphor/mutator/virtualization/VmReflect",
                "newarray", "(II)Ljava/lang/Object;", false));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addANEWARRAY(InsnList il, LabelNode[] h, int hi, int argPool, int argBytecode, int localPC,
                                      int ls, int sp, LabelNode loop) {
        il.add(h[hi]);
        readBC(il, argBytecode, localPC);
        il.add(new VarInsnNode(ALOAD, argPool));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AALOAD));
        il.add(new InsnNode(ICONST_0));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/String"));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false));
        il.add(new MethodInsnNode(INVOKESTATIC, "war/metaphor/mutator/virtualization/VmReflect",
                "anewarray", "(Ljava/lang/String;I)Ljava/lang/Object;", false));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addARRAYLENGTH(InsnList il, LabelNode[] h, int hi, int ls, int sp, LabelNode loop) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/reflect/Array", "getLength", "(Ljava/lang/Object;)I", false));
        il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addATHROW(InsnList il, LabelNode[] h, int hi, int ls, int sp) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Throwable"));
        il.add(new InsnNode(ATHROW));
    }

    private static void addCHECKCAST(InsnList il, LabelNode[] h, int hi, int argPool, int argBytecode, int localPC,
                                      int ls, int sp, LabelNode loop) {
        il.add(h[hi]);
        readBC(il, argBytecode, localPC);
        il.add(new VarInsnNode(ALOAD, argPool));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AALOAD));
        il.add(new InsnNode(ICONST_0));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/String"));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new MethodInsnNode(INVOKESTATIC, "war/metaphor/mutator/virtualization/VmReflect",
                "checkcast", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addINSTANCEOF(InsnList il, LabelNode[] h, int hi, int argPool, int argBytecode, int localPC,
                                       int ls, int sp, LabelNode loop) {
        il.add(h[hi]);
        readBC(il, argBytecode, localPC);
        il.add(new VarInsnNode(ALOAD, argPool));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AALOAD));
        il.add(new InsnNode(ICONST_0));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/String"));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new IincInsnNode(sp, -1));
        il.add(new MethodInsnNode(INVOKESTATIC, "war/metaphor/mutator/virtualization/VmReflect",
                "instanceof_", "(Ljava/lang/String;Ljava/lang/Object;)I", false));
        il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
        il.add(new IincInsnNode(sp, 1));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addIFNULL(InsnList il, LabelNode[] h, int hi, int argBytecode, int localPC,
                                   int ls, int sp, LabelNode loop, boolean nullBranch) {
        LabelNode taken = new LabelNode(), notTaken = new LabelNode();
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new IincInsnNode(sp, -1));
        il.add(new JumpInsnNode(nullBranch ? IFNULL : IFNONNULL, taken));
        il.add(notTaken);
        il.add(new IincInsnNode(localPC, 1));
        il.add(new JumpInsnNode(GOTO, loop));
        il.add(taken);
        readBC(il, argBytecode, localPC);
        il.add(new VarInsnNode(ISTORE, localPC));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addTABLESWITCH(InsnList il, LabelNode[] h, int hi, int argBytecode, int localPC,
                                         int ls, int sp, LabelNode loop) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, argBytecode));
        il.add(new VarInsnNode(ILOAD, localPC));
        il.add(new InsnNode(IALOAD));
        il.add(new VarInsnNode(ISTORE, localPC + 1));
        il.add(new IincInsnNode(localPC, 1));
        il.add(new VarInsnNode(ALOAD, argBytecode));
        il.add(new VarInsnNode(ILOAD, localPC));
        il.add(new InsnNode(IALOAD));
        il.add(new VarInsnNode(ISTORE, localPC + 2));
        il.add(new IincInsnNode(localPC, 1));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false));
        il.add(new IincInsnNode(sp, -1));
        il.add(new VarInsnNode(ILOAD, localPC + 1));
        il.add(new InsnNode(ISUB));
        il.add(new VarInsnNode(ISTORE, localPC + 3));
        LabelNode useDefault = new LabelNode(), inRange = new LabelNode();
        il.add(new VarInsnNode(ILOAD, localPC + 3));
        il.add(new JumpInsnNode(IFLT, useDefault));
        il.add(new VarInsnNode(ILOAD, localPC + 3));
        il.add(new VarInsnNode(ILOAD, localPC + 2));
        il.add(new VarInsnNode(ILOAD, localPC + 1));
        il.add(new InsnNode(ISUB));
        il.add(new JumpInsnNode(IF_ICMPGT, useDefault));
        il.add(inRange);
        il.add(new VarInsnNode(ALOAD, argBytecode));
        il.add(new VarInsnNode(ILOAD, localPC));
        il.add(new VarInsnNode(ILOAD, localPC + 3));
        il.add(new InsnNode(IADD));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(IADD));
        il.add(new InsnNode(IALOAD));
        il.add(new VarInsnNode(ISTORE, localPC));
        il.add(new JumpInsnNode(GOTO, loop));
        il.add(useDefault);
        il.add(new VarInsnNode(ALOAD, argBytecode));
        il.add(new VarInsnNode(ILOAD, localPC));
        il.add(new InsnNode(IALOAD));
        il.add(new VarInsnNode(ISTORE, localPC));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addLOOKUPSWITCH(InsnList il, LabelNode[] h, int hi, int argBytecode, int localPC,
                                          int ls, int sp, LabelNode loop) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, argBytecode));
        il.add(new VarInsnNode(ILOAD, localPC));
        il.add(new InsnNode(IALOAD));
        il.add(new VarInsnNode(ISTORE, localPC + 1));
        il.add(new IincInsnNode(localPC, 1));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false));
        il.add(new IincInsnNode(sp, -1));
        il.add(new VarInsnNode(ISTORE, localPC + 2));
        il.add(new InsnNode(ICONST_0));
        il.add(new VarInsnNode(ISTORE, localPC + 3));
        LabelNode scanLoop = new LabelNode(), scanLoopEnd = new LabelNode(), foundCase = new LabelNode();
        il.add(scanLoop);
        il.add(new VarInsnNode(ILOAD, localPC + 3));
        il.add(new VarInsnNode(ILOAD, localPC + 1));
        il.add(new JumpInsnNode(IF_ICMPGE, scanLoopEnd));
        il.add(new VarInsnNode(ALOAD, argBytecode));
        il.add(new VarInsnNode(ILOAD, localPC));
        il.add(new VarInsnNode(ILOAD, localPC + 3));
        il.add(new InsnNode(ICONST_2));
        il.add(new InsnNode(IMUL));
        il.add(new InsnNode(IADD));
        il.add(new InsnNode(IALOAD));
        il.add(new VarInsnNode(ILOAD, localPC + 2));
        il.add(new JumpInsnNode(IF_ICMPNE, foundCase));
        il.add(new IincInsnNode(localPC + 3, 1));
        il.add(new JumpInsnNode(GOTO, scanLoop));
        il.add(foundCase);
        il.add(new VarInsnNode(ALOAD, argBytecode));
        il.add(new VarInsnNode(ILOAD, localPC));
        il.add(new VarInsnNode(ILOAD, localPC + 3));
        il.add(new InsnNode(ICONST_2));
        il.add(new InsnNode(IMUL));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(IADD));
        il.add(new InsnNode(IADD));
        il.add(new InsnNode(IALOAD));
        il.add(new VarInsnNode(ISTORE, localPC));
        il.add(new JumpInsnNode(GOTO, loop));
        il.add(scanLoopEnd);
        il.add(new VarInsnNode(ALOAD, argBytecode));
        il.add(new VarInsnNode(ILOAD, localPC));
        il.add(new VarInsnNode(ILOAD, localPC + 1));
        il.add(new InsnNode(ICONST_2));
        il.add(new InsnNode(IMUL));
        il.add(new InsnNode(IADD));
        il.add(new InsnNode(IALOAD));
        il.add(new VarInsnNode(ISTORE, localPC));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addMULTIANEWARRAY(InsnList il, LabelNode[] h, int hi, int argPool, int argBytecode, int localPC,
                                            int ls, int sp, LabelNode loop) {
        il.add(h[hi]);
        readBC(il, argBytecode, localPC);
        il.add(new VarInsnNode(ALOAD, argPool));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AALOAD));
        il.add(new InsnNode(ICONST_0));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/String"));
        readBC(il, argBytecode, localPC);
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new MethodInsnNode(INVOKESTATIC, "war/metaphor/mutator/virtualization/VmReflect",
                "multianewarray", "(Ljava/lang/String;I[Ljava/lang/Object;I)Ljava/lang/Object;", false));
        il.add(new VarInsnNode(ISTORE, sp));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addArrayLoad(InsnList il, LabelNode[] h, int hi, int ls, int sp, LabelNode loop, String type) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(ISUB));
        il.add(new InsnNode(AALOAD));
        il.add(new InsnNode(SWAP));
        il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/reflect/Array", "get", "(Ljava/lang/Object;I)Ljava/lang/Object;", false));
        il.add(new IincInsnNode(sp, -1));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addArrayStore(InsnList il, LabelNode[] h, int hi, int ls, int sp, LabelNode loop, String type) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(ISUB));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false));
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(ICONST_2));
        il.add(new InsnNode(ISUB));
        il.add(new InsnNode(AALOAD));
        il.add(new IincInsnNode(sp, -3));
        il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/reflect/Array", "set", "(Ljava/lang/Object;ILjava/lang/Object;)V", false));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addIINC(InsnList il, LabelNode[] h, int hi, int argBytecode, int localPC,
                                  int argLocals, LabelNode loop) {
        il.add(h[hi]);
        readBC(il, argBytecode, localPC);
        il.add(new VarInsnNode(ISTORE, localPC + 1));
        readBC(il, argBytecode, localPC);
        il.add(new VarInsnNode(ALOAD, argLocals));
        il.add(new VarInsnNode(ILOAD, localPC + 1));
        il.add(new InsnNode(AALOAD));
        il.add(new TypeInsnNode(CHECKCAST, "java/lang/Number"));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(IADD));
        il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
        il.add(new VarInsnNode(ALOAD, argLocals));
        il.add(new VarInsnNode(ILOAD, localPC + 1));
        il.add(new InsnNode(SWAP));
        il.add(new InsnNode(AASTORE));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static void addMONITOR(InsnList il, LabelNode[] h, int hi, int ls, int sp, LabelNode loop, boolean enter) {
        il.add(h[hi]);
        il.add(new VarInsnNode(ALOAD, ls));
        il.add(new VarInsnNode(ILOAD, sp));
        il.add(new InsnNode(AALOAD));
        il.add(new IincInsnNode(sp, -1));
        il.add(new InsnNode(enter ? MONITORENTER : MONITOREXIT));
        il.add(new JumpInsnNode(GOTO, loop));
    }

    private static int[] buildOpcodeKeys() {
        return new int[]{
            VmOpcodes.NOP, VmOpcodes.ACONST_NULL,
            VmOpcodes.IPUSH, VmOpcodes.LPUSH, VmOpcodes.FPUSH, VmOpcodes.DPUSH, VmOpcodes.APUSH,
            VmOpcodes.ILOAD, VmOpcodes.LLOAD, VmOpcodes.FLOAD, VmOpcodes.DLOAD, VmOpcodes.ALOAD,
            VmOpcodes.ISTORE, VmOpcodes.LSTORE, VmOpcodes.FSTORE, VmOpcodes.DSTORE, VmOpcodes.ASTORE,
            VmOpcodes.POP, VmOpcodes.POP2,
            VmOpcodes.DUP, VmOpcodes.DUP_X1, VmOpcodes.DUP_X2,
            VmOpcodes.DUP2, VmOpcodes.DUP2_X1, VmOpcodes.DUP2_X2, VmOpcodes.SWAP,
            VmOpcodes.IADD, VmOpcodes.LADD, VmOpcodes.FADD, VmOpcodes.DADD,
            VmOpcodes.ISUB, VmOpcodes.LSUB, VmOpcodes.FSUB, VmOpcodes.DSUB,
            VmOpcodes.IMUL, VmOpcodes.LMUL, VmOpcodes.FMUL, VmOpcodes.DMUL,
            VmOpcodes.IDIV, VmOpcodes.LDIV, VmOpcodes.FDIV, VmOpcodes.DDIV,
            VmOpcodes.IREM, VmOpcodes.LREM, VmOpcodes.FREM, VmOpcodes.DREM,
            VmOpcodes.INEG, VmOpcodes.LNEG, VmOpcodes.FNEG, VmOpcodes.DNEG,
            VmOpcodes.ISHL, VmOpcodes.LSHL, VmOpcodes.ISHR, VmOpcodes.LSHR,
            VmOpcodes.IUSHR, VmOpcodes.LUSHR,
            VmOpcodes.IAND, VmOpcodes.LAND, VmOpcodes.IOR, VmOpcodes.LOR,
            VmOpcodes.IXOR, VmOpcodes.LXOR,
            VmOpcodes.I2L, VmOpcodes.I2F, VmOpcodes.I2D,
            VmOpcodes.L2I, VmOpcodes.L2F, VmOpcodes.L2D,
            VmOpcodes.F2I, VmOpcodes.F2L, VmOpcodes.F2D,
            VmOpcodes.D2I, VmOpcodes.D2L, VmOpcodes.D2F,
            VmOpcodes.I2B, VmOpcodes.I2C, VmOpcodes.I2S,
            VmOpcodes.LCMP, VmOpcodes.FCMPL, VmOpcodes.FCMPG, VmOpcodes.DCMPL, VmOpcodes.DCMPG,
            VmOpcodes.IFEQ, VmOpcodes.IFNE, VmOpcodes.IFLT, VmOpcodes.IFGE, VmOpcodes.IFGT, VmOpcodes.IFLE,
            VmOpcodes.IF_ICMPEQ, VmOpcodes.IF_ICMPNE, VmOpcodes.IF_ICMPLT,
            VmOpcodes.IF_ICMPGE, VmOpcodes.IF_ICMPGT, VmOpcodes.IF_ICMPLE,
            VmOpcodes.IF_ACMPEQ, VmOpcodes.IF_ACMPNE,
            VmOpcodes.GOTO,
            VmOpcodes.IRETURN, VmOpcodes.LRETURN, VmOpcodes.FRETURN,
            VmOpcodes.DRETURN, VmOpcodes.ARETURN, VmOpcodes.RETURN,
            VmOpcodes.GETSTATIC, VmOpcodes.PUTSTATIC, VmOpcodes.GETFIELD, VmOpcodes.PUTFIELD,
            VmOpcodes.INVOKEVIRTUAL, VmOpcodes.INVOKESPECIAL, VmOpcodes.INVOKESTATIC, VmOpcodes.INVOKEINTERFACE,
            VmOpcodes.NEW, VmOpcodes.NEWARRAY, VmOpcodes.ANEWARRAY,
            VmOpcodes.ARRAYLENGTH, VmOpcodes.ATHROW, VmOpcodes.CHECKCAST, VmOpcodes.INSTANCEOF,
            VmOpcodes.IFNULL, VmOpcodes.IFNONNULL,
            VmOpcodes.TABLESWITCH, VmOpcodes.LOOKUPSWITCH, VmOpcodes.MULTIANEWARRAY,
            VmOpcodes.IALOAD, VmOpcodes.LALOAD, VmOpcodes.FALOAD, VmOpcodes.DALOAD,
            VmOpcodes.AALOAD, VmOpcodes.BALOAD, VmOpcodes.CALOAD, VmOpcodes.SALOAD,
            VmOpcodes.IASTORE, VmOpcodes.LASTORE, VmOpcodes.FASTORE, VmOpcodes.DASTORE,
            VmOpcodes.AASTORE, VmOpcodes.BASTORE, VmOpcodes.CASTORE, VmOpcodes.SASTORE,
            VmOpcodes.IINC, VmOpcodes.MONITORENTER, VmOpcodes.MONITOREXIT
        };
    }
}