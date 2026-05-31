package war.metaphor.mutator.data.strings;

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

import java.util.ArrayList;
import java.util.List;

@Stability(Level.HIGH)
public class StringStackTransformer extends Mutator {

    private static final String SB   = "java/lang/StringBuilder";
    private static final String SB_D = "Ljava/lang/StringBuilder;";

    private final int minLength;
    private final int maxLength;
    private final int xorKey;

    public StringStackTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
        this.minLength = config == null ? 2  : config.getInt("min-length", 2);
        this.maxLength = config == null ? 64 : config.getInt("max-length", 64);
        this.xorKey    = config == null ? 0  : config.getInt("xor-key", 0);
    }

    @Override
    public void run(ObfuscatorContext base) {
        int stacked = 0;
        for (JClassNode cn : base.getClasses()) {
            if (cn.isExempt()) continue;
            for (MethodNode mn : cn.methods) {
                if (cn.isExempt(mn)) continue;
                BytecodeUtil.translateConcatenation(mn);
                stacked += processMethod(mn);
            }
        }
        Logger.INSTANCE.logln(war.jnt.dash.Level.INFO, Origin.METAPHOR,
                "StringStackTransformer: Stacked " + stacked + " strings");
    }

    private int processMethod(MethodNode mn) {
        List<AbstractInsnNode> targets = new ArrayList<>();
        for (AbstractInsnNode ain : mn.instructions) {
            if (!BytecodeUtil.isString(ain)) continue;
            String s = BytecodeUtil.getString(ain);
            if (s == null) continue;
            if (s.length() < minLength) continue;
            if (s.length() > maxLength) continue;
            targets.add(ain);
        }
        int count = 0;
        for (AbstractInsnNode ain : targets) {
            if (BytecodeUtil.leeway(mn) < 30_000) break;
            String s = BytecodeUtil.getString(ain);
            if (s == null) continue;
            InsnList replacement = buildStackString(s);
            if (!BytecodeUtil.hasSpace(mn, replacement)) continue;
            mn.instructions.insertBefore(ain, replacement);
            mn.instructions.remove(ain);
            count++;
        }
        return count;
    }

    private InsnList buildStackString(String s) {
        InsnList insns = new InsnList();
        insns.add(new TypeInsnNode(NEW, SB));
        insns.add(new InsnNode(DUP));
        insns.add(new MethodInsnNode(INVOKESPECIAL, SB, "<init>", "()V", false));
        char[] chars = s.toCharArray();
        for (char c : chars) {
            int stored = (xorKey != 0) ? (c ^ xorKey) : c;
            insns.add(pushInt(stored));
            if (xorKey != 0) {
                insns.add(pushInt(xorKey));
                insns.add(new InsnNode(IXOR));
            }
            insns.add(new InsnNode(I2C));
            insns.add(new MethodInsnNode(INVOKEVIRTUAL, SB, "append", "(C)" + SB_D, false));
        }
        insns.add(new MethodInsnNode(INVOKEVIRTUAL, SB, "toString", "()Ljava/lang/String;", false));
        return insns;
    }

    private AbstractInsnNode pushInt(int value) {
        if (value >= -1 && value <= 5) {
            return new InsnNode(ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            return new IntInsnNode(BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            return new IntInsnNode(SIPUSH, value);
        } else {
            return new LdcInsnNode(value);
        }
    }
}