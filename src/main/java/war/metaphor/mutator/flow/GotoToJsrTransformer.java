package war.metaphor.mutator.flow;

import org.objectweb.asm.tree.*;
import war.configuration.ConfigurationSection;
import war.jnt.annotate.Level;
import war.jnt.annotate.Stability;
import war.jnt.dash.Logger;
import war.jnt.dash.Origin;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.Mutator;
import war.metaphor.util.asm.BytecodeUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

@Stability(Level.UNKNOWN)
public class GotoToJsrTransformer extends Mutator {

    public GotoToJsrTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
    }

    @Override
    public void run(ObfuscatorContext base) {
        AtomicInteger converted = new AtomicInteger(0);
        base.getClasses().parallelStream().forEach(jClassNode -> {
            for (MethodNode method : jClassNode.methods) {
                Map<LabelNode, ArrayList<JumpInsnNode>> jumps = new HashMap<>();
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof JumpInsnNode jin) {
                        jumps.computeIfAbsent(jin.label, _ -> new ArrayList<>());
                        jumps.get(jin.label).add(jin);
                    }
                }

                for (Map.Entry<LabelNode, ArrayList<JumpInsnNode>> entry : jumps.entrySet()) {
                    if (!isJustGotos(entry.getValue())) continue;
                    method.instructions.insertBefore(entry.getKey(), BytecodeUtil.makeInteger(ThreadLocalRandom.current().nextInt()));
                    method.instructions.insert(entry.getKey(), new InsnNode(POP));
                    for (JumpInsnNode jumpInsnNode : entry.getValue()) {
                        jumpInsnNode.opcode = JSR;
                        converted.incrementAndGet();
                    }
                }
            }
        });
        Logger.INSTANCE.logln(war.jnt.dash.Level.INFO, Origin.METAPHOR,
                "GotoToJsrTransformer: Converted " + converted.get() + " GOTO instructions to JSR");
    }

    private boolean isJustGotos(ArrayList<JumpInsnNode> value) {
        for (JumpInsnNode jumpInsnNode : value) {
            if (jumpInsnNode.getOpcode() != GOTO) return false;
        }
        return true;
    }
}