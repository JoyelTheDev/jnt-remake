package war.metaphor.mutator.misc;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;
import war.configuration.ConfigurationSection;
import war.jnt.annotate.Level;
import war.jnt.annotate.Stability;
import war.jnt.dash.Logger;
import war.jnt.dash.Origin;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.Mutator;
import war.metaphor.tree.JClassNode;

import java.lang.reflect.Modifier;

@Stability(Level.VERY_HIGH)
public class StripTransformer extends Mutator {

    private final boolean stripLocalVariables;
    private final boolean stripSignatures;
    private final boolean stripParameters;
    private final boolean stripSourceInfo;
    private final boolean stripInnerClasses;

    public StripTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
        this.stripLocalVariables = config == null || config.getBoolean("local-variables", true);
        this.stripSignatures     = config == null || config.getBoolean("signatures", true);
        this.stripParameters     = config == null || config.getBoolean("parameters", true);
        this.stripSourceInfo     = config == null || config.getBoolean("source-info", true);
        this.stripInnerClasses   = config == null || config.getBoolean("inner-classes", true);
    }

    @Override
    public void run(ObfuscatorContext base) {
        int stripped = 0;
        for (JClassNode classNode : base.getClasses()) {
            if (classNode.isExempt()) continue;
            if (stripSourceInfo) {
                classNode.sourceDebug = null;
                classNode.sourceFile = null;
            }
            if (stripInnerClasses) {
                classNode.innerClasses.clear();
            }
            for (MethodNode method : classNode.methods) {
                if (classNode.isExempt(method)) continue;
                if (Modifier.isAbstract(method.access)) continue;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof LineNumberNode) {
                        method.instructions.remove(instruction);
                        stripped++;
                    }
                }
                if (stripLocalVariables) {
                    method.localVariables = null;
                }
                if (stripSignatures) {
                    if (method.signature != null && !method.signature.startsWith("plot::ark")) {
                        method.signature = null;
                    }
                }
                if (stripParameters) {
                    method.parameters = null;
                }
            }
            for (var field : classNode.fields) {
                if (classNode.isExempt(field)) continue;
                if (stripSignatures) field.signature = null;
            }
        }
        Logger.INSTANCE.logln(war.jnt.dash.Level.INFO, Origin.METAPHOR,
                "StripTransformer: Stripped " + stripped + " line number nodes");
    }
}