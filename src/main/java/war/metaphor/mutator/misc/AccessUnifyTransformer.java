package war.metaphor.mutator.misc;

import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import war.configuration.ConfigurationSection;
import war.jnt.annotate.Level;
import war.jnt.annotate.Stability;
import war.jnt.dash.Logger;
import war.jnt.dash.Origin;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.Mutator;
import war.metaphor.tree.JClassNode;

@Stability(Level.MEDIUM)
public class AccessUnifyTransformer extends Mutator {

    public AccessUnifyTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
    }

    @Override
    public void run(ObfuscatorContext base) {
        final boolean exploit = config.getBoolean("exploit", false);
        int members = 0;
        for (JClassNode classNode : base.getClasses()) {
            if (classNode.isExempt()) continue;
            classNode.access = handle(classNode.access);
            if (exploit) classNode.access = exploitClass(classNode.access);
            if (classNode.isInterface()) continue;
            for (MethodNode method : classNode.methods) {
                if (classNode.isExempt(method)) continue;
                method.access = handle(method.access);
                if (exploit && !method.name.contains("<")) {
                    method.access = exploitMethod(method.access);
                }
                members++;
            }
            for (FieldNode field : classNode.fields) {
                if (classNode.isExempt(field)) continue;
                field.access = handle(field.access);
                if (exploit) {
                    field.access = exploitField(field.access);
                }
                members++;
            }
        }
        Logger.INSTANCE.logln(war.jnt.dash.Level.INFO, Origin.METAPHOR,
                "AccessUnifyTransformer: Unified access on " + members + " members");
    }

    private int exploitMethod(int access) {
        access |= ACC_SYNTHETIC;
        access |= ACC_DEPRECATED;
        access |= ACC_STRICT;
        access |= ACC_VARARGS;
        access |= ACC_BRIDGE;
        return access;
    }

    private int exploitField(int access) {
        access |= ACC_SYNTHETIC;
        access |= ACC_DEPRECATED;
        access |= ACC_VOLATILE;
        access |= ACC_ENUM;
        return access;
    }

    private int exploitClass(int access) {
        access |= ACC_SYNTHETIC;
        access |= ACC_DEPRECATED;
        access |= ACC_SUPER;
        access |= ACC_OPEN;
        access |= ACC_ENUM;
        return access;
    }

    private int handle(int access) {
        access |= ACC_PUBLIC;
        access &= ~ACC_PRIVATE;
        access &= ~ACC_PROTECTED;
        access &= ~ACC_FINAL;
        return access;
    }
}