package war.metaphor.mutator.data.strings;

import org.objectweb.asm.tree.*;
import war.configuration.ConfigurationSection;
import war.jnt.annotate.Level;
import war.jnt.annotate.Stability;
import war.jnt.dash.Logger;
import war.jnt.dash.Origin;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.engine.Engine;
import war.metaphor.engine.types.PolymorphicEngine;
import war.metaphor.mutator.Mutator;
import war.metaphor.tree.JClassNode;
import war.metaphor.util.asm.BytecodeUtil;

@Stability(Level.MEDIUM)
public class LightStringTransformer extends Mutator {

    public LightStringTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
    }

    @Override
    public void run(ObfuscatorContext base) {
        int encrypted = 0;
        for (JClassNode classNode : base.getClasses()) {
            if (classNode.isExempt()) continue;
            for (MethodNode method : classNode.methods) {
                if (classNode.isExempt(method)) continue;
                BytecodeUtil.translateConcatenation(method);
                int size = BytecodeUtil.leeway(method);
                for (AbstractInsnNode instruction : method.instructions) {
                    if (size < 30000) break;
                    if (BytecodeUtil.isString(instruction)) {
                        String str = BytecodeUtil.getString(instruction);
                        if (str.length() < 2 || str.length() > 65535) continue;
                        Engine engine;
                        int byteLength = 0;
                        char[] chars;
                        engine = new PolymorphicEngine(5);
                        chars = str.toCharArray();
                        for (int i = 0; i < chars.length; i++)
                            chars[i] = (char) engine.run(chars[i]);
                        int byteLengthOffset;
                        int currentLength;
                        for (byteLengthOffset = 0; byteLengthOffset < chars.length; ++byteLengthOffset) {
                            currentLength = chars[byteLengthOffset];
                            if (currentLength >= 1 && currentLength <= 127) {
                                ++byteLength;
                            } else if (currentLength <= 2047) {
                                byteLength += 2;
                            } else {
                                byteLength += 3;
                            }
                        }
                        if (byteLength > 65535) return;
                        String enc = new String(chars);
                        InsnList code = new InsnList();
                        code.add(BytecodeUtil.generateInteger(0));
                        code.add(new TypeInsnNode(NEW, "java/lang/StringBuilder"));
                        code.add(new InsnNode(DUP));
                        code.add(new LdcInsnNode(enc));
                        code.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false));
                        LabelNode start = new LabelNode();
                        LabelNode end = new LabelNode();
                        code.add(start);
                        code.add(new InsnNode(SWAP));
                        code.add(BytecodeUtil.generateInteger(enc.length()));
                        code.add(new InsnNode(DUP2));
                        code.add(new JumpInsnNode(IF_ICMPGE, end));
                        code.add(new InsnNode(POP));
                        code.add(new InsnNode(DUP2));
                        code.add(new InsnNode(DUP2));
                        code.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/StringBuilder", "charAt", "(I)C", false));
                        code.add(engine.getInstructions());
                        code.add(new InsnNode(I2C));
                        code.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/StringBuilder", "setCharAt", "(IC)V", false));
                        code.add(BytecodeUtil.generateInteger(1));
                        code.add(new InsnNode(IADD));
                        code.add(new InsnNode(SWAP));
                        code.add(new JumpInsnNode(GOTO, start));
                        code.add(end);
                        code.add(new InsnNode(POP2));
                        code.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
                        if (!BytecodeUtil.hasSpace(method, code)) return;
                        method.instructions.insertBefore(instruction, code);
                        method.instructions.remove(instruction);
                        encrypted++;
                    }
                    size = BytecodeUtil.leeway(method);
                }
            }
        }
        Logger.INSTANCE.logln(war.jnt.dash.Level.INFO, Origin.METAPHOR,
                "LightStringTransformer: Encrypted " + encrypted + " strings");
    }
}