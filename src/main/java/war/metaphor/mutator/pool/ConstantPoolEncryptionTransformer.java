package war.metaphor.mutator.pool;

import org.objectweb.asm.tree.*;
import war.configuration.ConfigurationSection;
import war.jnt.annotate.Level;
import war.jnt.annotate.Stability;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.Mutator;
import war.metaphor.tree.JClassNode;
import war.metaphor.util.Chance;
import war.metaphor.util.asm.BytecodeUtil;

import java.lang.reflect.Modifier;
import java.util.*;


@Stability(Level.HIGH)
public class ConstantPoolEncryptionTransformer extends Mutator {

    // ── Type tags embedded in the encrypted byte stream ─────────────────────
    private static final byte TAG_STRING = 0x01;
    private static final byte TAG_INT    = 0x02;
    private static final byte TAG_LONG   = 0x03;
    private static final byte TAG_FLOAT  = 0x04;
    private static final byte TAG_DOUBLE = 0x05;

    // ── Descriptor constants ─────────────────────────────────────────────────
    private static final String OBJ_DESC       = "Ljava/lang/Object;";
    private static final String POOL_FIELD_DESC = "[Ljava/lang/Object;";
    private static final String DATA_FIELD_DESC = "[B";
    private static final String DECRYPT_DESC    = "(I)Ljava/lang/Object;";

    // ── Config knobs ─────────────────────────────────────────────────────────
    private final double chance;
    private final int    minStrLength;
    private final boolean encryptNumbers;

    public ConstantPoolEncryptionTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
        this.chance         = config == null ? 100.0 : config.getDouble("chance", 100.0);
        this.minStrLength   = config == null ? 1     : config.getInt("min-str-length", 1);
        this.encryptNumbers = config == null || config.getBoolean("encrypt-numbers", true);
    }

    // ── Entry point ──────────────────────────────────────────────────────────

    @Override
    public void run(ObfuscatorContext base) {
        int totalSlots   = 0;
        int totalClasses = 0;

        for (JClassNode cn : base.getClasses()) {
            if (cn.isExempt())    continue;
            if (cn.isInterface()) continue;
            if (Modifier.isInterface(cn.access)) continue;

            int slotsMade = processClass(cn);
            if (slotsMade > 0) {
                totalSlots += slotsMade;
                totalClasses++;
            }
        }

        war.jnt.dash.Logger.INSTANCE.logln(
                war.jnt.dash.Level.INFO,
                war.jnt.dash.Origin.METAPHOR,
                "ConstantPoolEncryption: encrypted {} constants across {} classes",
                totalSlots, totalClasses);
    }

    // ── Per-class processing ─────────────────────────────────────────────────

    /**
     * Collects all eligible LDC nodes, assigns them pool slots, builds the
     * encrypted byte[] payload, injects the synthetic fields + clinit init +
     * decrypt method, and rewrites every use site.
     *
     * @return number of pool slots created (0 = class was untouched)
     */
    private int processClass(JClassNode cn) {
        // ── 1. Collect candidates ────────────────────────────────────────────
        List<Candidate> candidates = new ArrayList<>();

        for (MethodNode mn : cn.methods) {
            if (cn.isExempt(mn)) continue;
            if (Modifier.isAbstract(mn.access)) continue;
            // Avoid transforming clinit — it runs before our pool is ready
            if (mn.name.equals("<clinit>")) continue;

            BytecodeUtil.translateConcatenation(mn);

            for (AbstractInsnNode ain : mn.instructions) {
                if (!isEligible(ain)) continue;
                if (!Chance.chance(chance)) continue;
                candidates.add(new Candidate(mn, ain, extractConstant(ain)));
            }
        }

        if (candidates.isEmpty()) return 0;

        // ── 2. Deduplicate: same value → same slot ───────────────────────────
        LinkedHashMap<Object, Integer> slotMap = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            slotMap.computeIfAbsent(c.value, k -> slotMap.size());
        }

        int poolSize = slotMap.size();
        Object[] slotValues = new Object[poolSize];
        slotMap.forEach((v, i) -> slotValues[i] = v);

        // ── 3. Derive per-class cipher keys ──────────────────────────────────
        int nameHash  = cn.name.hashCode();
        byte keyA     = (byte)  (nameHash        & 0xFF);
        byte keyB     = (byte) ((nameHash >>> 8) & 0xFF);
        // keyB is the complement rotate so decryption can't trivially mirror A
        if (keyA == 0) keyA = (byte) 0x5A;
        if (keyB == 0) keyB = (byte) 0xA5;

        // ── 4. Build encrypted byte[] payload ────────────────────────────────
        byte[] payload = buildPayload(slotValues, keyA, keyB);

        // ── 5. Build per-slot byte offsets for the decrypt method ─────────────
        int[] slotOffsets = buildSlotOffsets(slotValues);

        // ── 6. Inject synthetic fields ───────────────────────────────────────
        String poolField = uniqueName(cn, "ARK$pool");
        String dataField = uniqueName(cn, "ARK$data");
        String decryptMn = uniqueName(cn, "ARK$decrypt");

        cn.fields.add(new FieldNode(
                ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
                poolField, POOL_FIELD_DESC, null, null));
        cn.fields.add(new FieldNode(
                ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC | ACC_FINAL,
                dataField, DATA_FIELD_DESC, null, null));

        // ── 7. Inject clinit initialisation ──────────────────────────────────
        MethodNode clinit = cn.getStaticInit();
        InsnList   initCode = buildInit(cn.name, poolField, dataField, payload, poolSize);
        AbstractInsnNode firstReal = firstRealInsn(clinit);
        if (firstReal == null)
            clinit.instructions.insert(initCode);
        else
            clinit.instructions.insertBefore(firstReal, initCode);

        // ── 8. Inject the decrypt method ─────────────────────────────────────
        MethodNode decryptMethod = buildDecryptMethod(
                cn.name, decryptMn, poolField, dataField,
                slotOffsets, keyA, keyB);
        cn.methods.add(decryptMethod);

        // ── 9. Rewrite use sites ──────────────────────────────────────────────
        for (Candidate c : candidates) {
            if (!BytecodeUtil.hasSpace(c.method, 16)) continue;

            int slot = slotMap.get(c.value);
            InsnList replacement = buildUseSite(cn.name, decryptMn, c.value, slot);
            c.method.instructions.insertBefore(c.ldc, replacement);
            c.method.instructions.remove(c.ldc);
        }

        return poolSize;
    }

    // ── Payload encoding ─────────────────────────────────────────────────────

    /**
     * Serialises every constant as:
     *   [1 byte tag] [4-byte int: byte length of data] [data bytes]
     *
     * Then encrypts the data bytes (NOT the tag/length prefix) using:
     *   pass 1 — each byte ^= keyA
     *   pass 2 — each byte = rotateLeft(byte, index & 7)
     *   pass 3 — each byte ^= keyB
     */
    private byte[] buildPayload(Object[] values, byte keyA, byte keyB) {
        // Collect raw byte representations per slot
        List<byte[]> raws = new ArrayList<>(values.length);
        List<Byte>   tags = new ArrayList<>(values.length);

        for (Object v : values) {
            byte[] raw;
            byte   tag;
            if (v instanceof String s) {
                raw = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                tag = TAG_STRING;
            } else if (v instanceof Integer i) {
                raw = intToBytes(i);
                tag = TAG_INT;
            } else if (v instanceof Long l) {
                raw = longToBytes(l);
                tag = TAG_LONG;
            } else if (v instanceof Float f) {
                raw = intToBytes(Float.floatToRawIntBits(f));
                tag = TAG_FLOAT;
            } else if (v instanceof Double d) {
                raw = longToBytes(Double.doubleToRawLongBits(d));
                tag = TAG_DOUBLE;
            } else {
                throw new IllegalStateException("Unexpected constant type: " + v.getClass());
            }
            // encrypt the data bytes
            byte[] enc = encrypt(raw, keyA, keyB);
            raws.add(enc);
            tags.add(tag);
        }

        // Lay out: for each slot: [tag(1)] [length(4)] [encData]
        int total = 0;
        for (byte[] enc : raws) total += 1 + 4 + enc.length;
        byte[] payload = new byte[total];
        int pos = 0;
        for (int i = 0; i < raws.size(); i++) {
            byte[] enc = raws.get(i);
            payload[pos++] = tags.get(i);
            int len = enc.length;
            payload[pos++] = (byte)(len >>> 24);
            payload[pos++] = (byte)(len >>> 16);
            payload[pos++] = (byte)(len >>>  8);
            payload[pos++] = (byte)(len);
            System.arraycopy(enc, 0, payload, pos, enc.length);
            pos += enc.length;
        }
        return payload;
    }

    /** Returns the byte offset of each slot's [tag] header inside the payload. */
    private int[] buildSlotOffsets(Object[] values) {
        int[] offsets = new int[values.length];
        int pos = 0;
        for (int i = 0; i < values.length; i++) {
            offsets[i] = pos;
            int rawLen = rawByteLength(values[i]);
            pos += 1 + 4 + rawLen; // tag + length + data
        }
        return offsets;
    }

    private int rawByteLength(Object v) {
        if (v instanceof String s)  return s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (v instanceof Integer)   return 4;
        if (v instanceof Long)      return 8;
        if (v instanceof Float)     return 4;
        if (v instanceof Double)    return 8;
        throw new IllegalStateException("Unknown type: " + v);
    }

    private byte[] encrypt(byte[] raw, byte keyA, byte keyB) {
        byte[] out = Arrays.copyOf(raw, raw.length);
        for (int i = 0; i < out.length; i++) {
            int b = (out[i] & 0xFF) ^ (keyA & 0xFF);
            int rot = i & 7;
            b = ((b << rot) | (b >>> (8 - rot))) & 0xFF;
            b ^= (keyB & 0xFF);
            out[i] = (byte) b;
        }
        return out;
    }

    // ── Bytecode builders ─────────────────────────────────────────────────────

    /**
     * Builds clinit code that:
     *   1. Creates and populates the JNT$data byte[] from baked-in literals.
     *   2. Creates an empty JNT$pool Object[N].
     */
    private InsnList buildInit(String owner, String poolField, String dataField,
                                byte[] payload, int poolSize) {
        InsnList il = new InsnList();

        // ── JNT$data = new byte[]{ payload bytes } ───────────────────────────
        il.add(pushInt(payload.length));
        il.add(new IntInsnNode(NEWARRAY, T_BYTE));
        for (int i = 0; i < payload.length; i++) {
            il.add(new InsnNode(DUP));
            il.add(pushInt(i));
            il.add(pushInt(payload[i]));  // sign-extends fine for BASTORE
            il.add(new InsnNode(BASTORE));
        }
        il.add(new FieldInsnNode(PUTSTATIC, owner, dataField, DATA_FIELD_DESC));

        // ── JNT$pool = new Object[poolSize] ──────────────────────────────────
        il.add(pushInt(poolSize));
        il.add(new TypeInsnNode(ANEWARRAY, "java/lang/Object"));
        il.add(new FieldInsnNode(PUTSTATIC, owner, poolField, POOL_FIELD_DESC));

        return il;
    }

    private MethodNode buildDecryptMethod(String owner, String methodName,
                                           String poolField, String dataField,
                                           int[] slotOffsets,
                                           byte keyA, byte keyB) {

        MethodNode mn = new MethodNode(
                ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
                methodName, DECRYPT_DESC, null, null);

        InsnList il = mn.instructions;

        // Local variable layout:
        //  0 = slot (param: int)
        //  1 = offset (int)
        //  2 = data (byte[])
        //  3 = tag (byte)
        //  4 = len (int)
        //  5 = raw (byte[])
        //  6 = i (loop counter, int)
        //  7 = b (int — current decrypted byte)
        //  8 = result (Object)

        LabelNode checkCache   = new LabelNode();
        LabelNode computeOffset = new LabelNode();
        LabelNode readHeader   = new LabelNode();
        LabelNode decryptLoop  = new LabelNode();
        LabelNode decryptCheck = new LabelNode();
        LabelNode reconstruct  = new LabelNode();
        LabelNode cacheAndRet  = new LabelNode();

        // ── Check cache ───────────────────────────────────────────────────────
        il.add(checkCache);
        il.add(new FieldInsnNode(GETSTATIC, owner, poolField, POOL_FIELD_DESC)); // pool
        il.add(new VarInsnNode(ILOAD, 0));                                       // slot
        il.add(new InsnNode(AALOAD));                                            // pool[slot]
        il.add(new InsnNode(DUP));
        LabelNode afterNullCheck = new LabelNode();
        il.add(new JumpInsnNode(IFNULL, afterNullCheck));                        // null → compute
        il.add(new InsnNode(ARETURN));                                           // return cached
        il.add(afterNullCheck);
        il.add(new InsnNode(POP));                                               // discard null

        // ── Resolve byte-stream offset for this slot (TABLESWITCH) ───────────
        il.add(computeOffset);
        {
            int minSlot = 0;
            int maxSlot = slotOffsets.length - 1;
            LabelNode[] switchLabels = new LabelNode[slotOffsets.length];
            for (int i = 0; i < slotOffsets.length; i++) switchLabels[i] = new LabelNode();
            LabelNode switchDefault = new LabelNode();

            il.add(new VarInsnNode(ILOAD, 0));
            il.add(new TableSwitchInsnNode(minSlot, maxSlot, switchDefault, switchLabels));

            // Each case: BIPUSH/SIPUSH/LDC offset; ISTORE 1; GOTO readHeader
            for (int i = 0; i < slotOffsets.length; i++) {
                il.add(switchLabels[i]);
                il.add(pushInt(slotOffsets[i]));
                il.add(new VarInsnNode(ISTORE, 1));
                il.add(new JumpInsnNode(GOTO, readHeader));
            }
            // Default: offset = 0 (should never happen in practice)
            il.add(switchDefault);
            il.add(new InsnNode(ICONST_0));
            il.add(new VarInsnNode(ISTORE, 1));
        }

        // ── Read tag + length from payload ────────────────────────────────────
        il.add(readHeader);
        il.add(new FieldInsnNode(GETSTATIC, owner, dataField, DATA_FIELD_DESC));
        il.add(new VarInsnNode(ASTORE, 2));                   // data = JNT$data

        // tag = data[offset]
        il.add(new VarInsnNode(ALOAD, 2));
        il.add(new VarInsnNode(ILOAD, 1));
        il.add(new InsnNode(BALOAD));
        il.add(new VarInsnNode(ISTORE, 3));                   // tag

        // len = ((data[offset+1]&0xFF)<<24) | ((data[offset+2]&0xFF)<<16)
        //      | ((data[offset+3]&0xFF)<<8) | (data[offset+4]&0xFF)
        il.add(new VarInsnNode(ALOAD, 2));
        il.add(new VarInsnNode(ILOAD, 1));
        il.add(new InsnNode(ICONST_1));
        il.add(new InsnNode(IADD));
        il.add(new InsnNode(BALOAD));
        il.add(pushInt(0xFF)); il.add(new InsnNode(IAND));
        il.add(pushInt(24));   il.add(new InsnNode(ISHL));  // hi byte

        il.add(new VarInsnNode(ALOAD, 2));
        il.add(new VarInsnNode(ILOAD, 1)); il.add(new InsnNode(ICONST_2)); il.add(new InsnNode(IADD));
        il.add(new InsnNode(BALOAD));
        il.add(pushInt(0xFF)); il.add(new InsnNode(IAND));
        il.add(pushInt(16));   il.add(new InsnNode(ISHL));
        il.add(new InsnNode(IOR));

        il.add(new VarInsnNode(ALOAD, 2));
        il.add(new VarInsnNode(ILOAD, 1)); il.add(new InsnNode(ICONST_3)); il.add(new InsnNode(IADD));
        il.add(new InsnNode(BALOAD));
        il.add(pushInt(0xFF)); il.add(new InsnNode(IAND));
        il.add(pushInt(8));    il.add(new InsnNode(ISHL));
        il.add(new InsnNode(IOR));

        il.add(new VarInsnNode(ALOAD, 2));
        il.add(new VarInsnNode(ILOAD, 1)); il.add(new InsnNode(ICONST_4)); il.add(new InsnNode(IADD));
        il.add(new InsnNode(BALOAD));
        il.add(pushInt(0xFF)); il.add(new InsnNode(IAND));
        il.add(new InsnNode(IOR));

        il.add(new VarInsnNode(ISTORE, 4));                   // len

        // raw = new byte[len]
        il.add(new VarInsnNode(ILOAD, 4));
        il.add(new IntInsnNode(NEWARRAY, T_BYTE));
        il.add(new VarInsnNode(ASTORE, 5));                   // raw

        // System.arraycopy(data, offset + 5, raw, 0, len)
        il.add(new VarInsnNode(ALOAD, 2));
        il.add(new VarInsnNode(ILOAD, 1)); il.add(new InsnNode(ICONST_5)); il.add(new InsnNode(IADD));
        il.add(new VarInsnNode(ALOAD, 5));
        il.add(new InsnNode(ICONST_0));
        il.add(new VarInsnNode(ILOAD, 4));
        il.add(new MethodInsnNode(INVOKESTATIC,
                "java/lang/System", "arraycopy",
                "(Ljava/lang/Object;ILjava/lang/Object;II)V", false));

    
        il.add(new InsnNode(ICONST_0));
        il.add(new VarInsnNode(ISTORE, 6));                   // i = 0

        il.add(decryptCheck);
        il.add(new VarInsnNode(ILOAD, 6));
        il.add(new VarInsnNode(ILOAD, 4));
        il.add(new JumpInsnNode(IF_ICMPGE, reconstruct));     // if i >= len → done

        il.add(decryptLoop);
        // b = (raw[i] & 0xFF) ^ keyB
        il.add(new VarInsnNode(ALOAD, 5));
        il.add(new VarInsnNode(ILOAD, 6));
        il.add(new InsnNode(BALOAD));
        il.add(pushInt(0xFF)); il.add(new InsnNode(IAND));
        il.add(pushInt(keyB & 0xFF)); il.add(new InsnNode(IXOR));
        il.add(new VarInsnNode(ISTORE, 7));                   // b

        // rot = i & 7  (stays in i's spot on stack temporarily — use local 8 temporarily)
        // b = ((b >>> rot) | (b << (8 - rot))) & 0xFF
        il.add(new VarInsnNode(ILOAD, 7));
        il.add(new VarInsnNode(ILOAD, 6));
        il.add(pushInt(7));   il.add(new InsnNode(IAND));     // rot
        il.add(new InsnNode(IUSHR));                          // b >>> rot

        il.add(new VarInsnNode(ILOAD, 7));
        il.add(pushInt(8));
        il.add(new VarInsnNode(ILOAD, 6));
        il.add(pushInt(7)); il.add(new InsnNode(IAND));       // rot again
        il.add(new InsnNode(ISUB));                           // 8 - rot
        il.add(new InsnNode(ISHL));                           // b << (8 - rot)
        il.add(new InsnNode(IOR));
        il.add(pushInt(0xFF)); il.add(new InsnNode(IAND));    // & 0xFF

        // b ^= keyA
        il.add(pushInt(keyA & 0xFF)); il.add(new InsnNode(IXOR));
        il.add(new VarInsnNode(ISTORE, 7));                   // b (decrypted)

        // raw[i] = (byte) b
        il.add(new VarInsnNode(ALOAD, 5));
        il.add(new VarInsnNode(ILOAD, 6));
        il.add(new VarInsnNode(ILOAD, 7));
        il.add(new InsnNode(BASTORE));

        // i++
        il.add(new IincInsnNode(6, 1));
        il.add(new JumpInsnNode(GOTO, decryptCheck));

        // ── Reconstruct typed value ───────────────────────────────────────────
        il.add(reconstruct);

        LabelNode isString = new LabelNode();
        LabelNode isInt    = new LabelNode();
        LabelNode isLong   = new LabelNode();
        LabelNode isFloat  = new LabelNode();
        LabelNode isDouble = new LabelNode();
        LabelNode endTag   = new LabelNode();

        // if (tag == TAG_STRING)
        il.add(new VarInsnNode(ILOAD, 3));
        il.add(pushInt(TAG_STRING));
        il.add(new JumpInsnNode(IF_ICMPNE, isInt));
        il.add(isString);
        // result = new String(raw, StandardCharsets.UTF_8)
        il.add(new TypeInsnNode(NEW, "java/lang/String"));
        il.add(new InsnNode(DUP));
        il.add(new VarInsnNode(ALOAD, 5));
        il.add(new FieldInsnNode(GETSTATIC,
                "java/nio/charset/StandardCharsets", "UTF_8",
                "Ljava/nio/charset/Charset;"));
        il.add(new MethodInsnNode(INVOKESPECIAL,
                "java/lang/String", "<init>",
                "([BLjava/nio/charset/Charset;)V", false));
        il.add(new VarInsnNode(ASTORE, 8));
        il.add(new JumpInsnNode(GOTO, cacheAndRet));

        // if (tag == TAG_INT)
        il.add(isInt);
        il.add(new VarInsnNode(ILOAD, 3));
        il.add(pushInt(TAG_INT));
        il.add(new JumpInsnNode(IF_ICMPNE, isLong));
        // Integer.valueOf( (raw[0]<<24)|(raw[1]<<16)|(raw[2]<<8)|raw[3] )
        il.add(new VarInsnNode(ALOAD, 5));
        il.add(new InsnNode(ICONST_0)); il.add(new InsnNode(BALOAD));
        il.add(pushInt(0xFF)); il.add(new InsnNode(IAND));
        il.add(pushInt(24));   il.add(new InsnNode(ISHL));
        il.add(new VarInsnNode(ALOAD, 5));
        il.add(new InsnNode(ICONST_1)); il.add(new InsnNode(BALOAD));
        il.add(pushInt(0xFF)); il.add(new InsnNode(IAND));
        il.add(pushInt(16)); il.add(new InsnNode(ISHL)); il.add(new InsnNode(IOR));
        il.add(new VarInsnNode(ALOAD, 5));
        il.add(new InsnNode(ICONST_2)); il.add(new InsnNode(BALOAD));
        il.add(pushInt(0xFF)); il.add(new InsnNode(IAND));
        il.add(pushInt(8)); il.add(new InsnNode(ISHL)); il.add(new InsnNode(IOR));
        il.add(new VarInsnNode(ALOAD, 5));
        il.add(new InsnNode(ICONST_3)); il.add(new InsnNode(BALOAD));
        il.add(pushInt(0xFF)); il.add(new InsnNode(IAND)); il.add(new InsnNode(IOR));
        il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
        il.add(new VarInsnNode(ASTORE, 8));
        il.add(new JumpInsnNode(GOTO, cacheAndRet));
        il.add(isLong);
        il.add(new VarInsnNode(ILOAD, 3));
        il.add(pushInt(TAG_LONG));
        il.add(new JumpInsnNode(IF_ICMPNE, isFloat));
        il.add(buildLongFromBytes(5));
        il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false));
        il.add(new VarInsnNode(ASTORE, 8));
        il.add(new JumpInsnNode(GOTO, cacheAndRet));
        il.add(isFloat);
        il.add(new VarInsnNode(ILOAD, 3));
        il.add(pushInt(TAG_FLOAT));
        il.add(new JumpInsnNode(IF_ICMPNE, isDouble));
        il.add(new VarInsnNode(ALOAD, 5));
        il.add(new InsnNode(ICONST_0)); il.add(new InsnNode(BALOAD));
        il.add(pushInt(0xFF)); il.add(new InsnNode(IAND));
        il.add(pushInt(24)); il.add(new InsnNode(ISHL));
        il.add(new VarInsnNode(ALOAD, 5));
        il.add(new InsnNode(ICONST_1)); il.add(new InsnNode(BALOAD));
        il.add(pushInt(0xFF)); il.add(new InsnNode(IAND));
        il.add(pushInt(16)); il.add(new InsnNode(ISHL)); il.add(new InsnNode(IOR));
        il.add(new VarInsnNode(ALOAD, 5));
        il.add(new InsnNode(ICONST_2)); il.add(new InsnNode(BALOAD));
        il.add(pushInt(0xFF)); il.add(new InsnNode(IAND));
        il.add(pushInt(8)); il.add(new InsnNode(ISHL)); il.add(new InsnNode(IOR));
        il.add(new VarInsnNode(ALOAD, 5));
        il.add(new InsnNode(ICONST_3)); il.add(new InsnNode(BALOAD));
        il.add(pushInt(0xFF)); il.add(new InsnNode(IAND)); il.add(new InsnNode(IOR));
        il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Float", "intBitsToFloat", "(I)F", false));
        il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false));
        il.add(new VarInsnNode(ASTORE, 8));
        il.add(new JumpInsnNode(GOTO, cacheAndRet));
        il.add(isDouble);
        il.add(buildLongFromBytes(5));
        il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false));
        il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false));
        il.add(new VarInsnNode(ASTORE, 8));
        il.add(cacheAndRet);
        il.add(new FieldInsnNode(GETSTATIC, owner, poolField, POOL_FIELD_DESC));
        il.add(new VarInsnNode(ILOAD, 0));
        il.add(new VarInsnNode(ALOAD, 8));
        il.add(new InsnNode(AASTORE));                        // JNT$pool[slot] = result
        il.add(new VarInsnNode(ALOAD, 8));
        il.add(new InsnNode(ARETURN));

        il.add(endTag);

        mn.maxLocals = 9;
        mn.maxStack  = 8;
        return mn;
    }

    private InsnList buildLongFromBytes(int rawVar) {
        InsnList il = new InsnList();
        // ((long)(raw[0]&0xFF)<<56) | ... | (raw[7]&0xFF)
        int[] shifts = {56, 48, 40, 32, 24, 16, 8, 0};
        for (int i = 0; i < 8; i++) {
            il.add(new VarInsnNode(ALOAD, rawVar));
            il.add(pushInt(i));
            il.add(new InsnNode(BALOAD));
            il.add(pushInt(0xFF)); il.add(new InsnNode(IAND));
            il.add(new InsnNode(I2L));
            if (shifts[i] > 0) {
                il.add(pushInt(shifts[i]));
                il.add(new InsnNode(LSHL));
            }
            if (i > 0) il.add(new InsnNode(LOR));
        }
        return il;
    }
    
    private InsnList buildUseSite(String owner, String decryptMethod,
                                   Object value, int slot) {
        InsnList il = new InsnList();
        il.add(pushInt(slot));
        il.add(new MethodInsnNode(INVOKESTATIC, owner, decryptMethod, DECRYPT_DESC, false));

        if (value instanceof String) {
            il.add(new TypeInsnNode(CHECKCAST, "java/lang/String"));
        } else if (value instanceof Integer) {
            il.add(new TypeInsnNode(CHECKCAST, "java/lang/Integer"));
            il.add(new MethodInsnNode(INVOKEVIRTUAL,
                    "java/lang/Integer", "intValue", "()I", false));
        } else if (value instanceof Long) {
            il.add(new TypeInsnNode(CHECKCAST, "java/lang/Long"));
            il.add(new MethodInsnNode(INVOKEVIRTUAL,
                    "java/lang/Long", "longValue", "()J", false));
        } else if (value instanceof Float) {
            il.add(new TypeInsnNode(CHECKCAST, "java/lang/Float"));
            il.add(new MethodInsnNode(INVOKEVIRTUAL,
                    "java/lang/Float", "floatValue", "()F", false));
        } else if (value instanceof Double) {
            il.add(new TypeInsnNode(CHECKCAST, "java/lang/Double"));
            il.add(new MethodInsnNode(INVOKEVIRTUAL,
                    "java/lang/Double", "doubleValue", "()D", false));
        }
        return il;
    }

    // ── Eligibility check ────────────────────────────────────────────────────

    private boolean isEligible(AbstractInsnNode ain) {
        if (!(ain instanceof LdcInsnNode ldc)) return false;
        Object cst = ldc.cst;
        if (cst instanceof String s)
            return s.length() >= minStrLength;
        if (!encryptNumbers)
            return false;
        return cst instanceof Integer
            || cst instanceof Long
            || cst instanceof Float
            || cst instanceof Double;
    }

    private Object extractConstant(AbstractInsnNode ain) {
        return ((LdcInsnNode) ain).cst;
    }

    // ── Byte/encoding helpers ────────────────────────────────────────────────

    private static byte[] intToBytes(int v) {
        return new byte[]{
            (byte)(v >>> 24), (byte)(v >>> 16),
            (byte)(v >>>  8), (byte)(v)
        };
    }

    private static byte[] longToBytes(long v) {
        return new byte[]{
            (byte)(v >>> 56), (byte)(v >>> 48),
            (byte)(v >>> 40), (byte)(v >>> 32),
            (byte)(v >>> 24), (byte)(v >>> 16),
            (byte)(v >>>  8), (byte)(v)
        };
    }

    // ── ASM instruction helpers ──────────────────────────────────────────────

    private static AbstractInsnNode pushInt(int value) {
        if (value >= -1 && value <= 5)
            return new InsnNode(ICONST_0 + value);
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE)
            return new IntInsnNode(BIPUSH, value);
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE)
            return new IntInsnNode(SIPUSH, value);
        return new LdcInsnNode(value);
    }

    private static AbstractInsnNode firstRealInsn(MethodNode mn) {
        for (AbstractInsnNode ain : mn.instructions)
            if (ain.getOpcode() >= 0) return ain;
        return null;
    }

    /** Returns a field/method name that doesn't already exist on the class. */
    private String uniqueName(JClassNode cn, String preferred) {
        Set<String> used = new HashSet<>();
        cn.fields.forEach(f -> used.add(f.name));
        cn.methods.forEach(m -> used.add(m.name));
        if (!used.contains(preferred)) return preferred;
        // append random suffix until unique
        String name;
        do {
            name = preferred + "$" + Integer.toHexString(rand.nextInt(0xFFFF));
        } while (used.contains(name));
        return name;
    }

    // ── Candidate record ─────────────────────────────────────────────────────

    private record Candidate(MethodNode method, AbstractInsnNode ldc, Object value) {}
}
