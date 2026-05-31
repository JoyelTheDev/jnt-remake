package war.metaphor.mutator.misc;

import org.objectweb.asm.tree.*;
import war.configuration.ConfigurationSection;
import war.jnt.annotate.Level;
import war.jnt.annotate.Stability;
import war.jnt.dash.Logger;
import war.jnt.dash.Origin;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.Mutator;
import war.metaphor.tree.JClassNode;

import java.lang.reflect.Modifier;

@Stability(Level.UNKNOWN)
public class FieldInlinerTransformer extends Mutator {

    public FieldInlinerTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
    }

    @Override
    public void run(ObfuscatorContext base) {
        int inlined = 0;
        for (JClassNode classNode : base.getClasses()) {
            if (classNode.isExempt()) continue;
            MethodNode clinit = classNode.getStaticInit();
            for (FieldNode field : classNode.fields) {
                if (classNode.isExempt(field)) continue;
                if (field.value != null && Modifier.isStatic(field.access)) {
                    InsnList list = new InsnList();
                    list.add(new LdcInsnNode(field.value));
                    list.add(new FieldInsnNode(PUTSTATIC, classNode.name, field.name, field.desc));
                    clinit.instructions.insert(list);
                    field.value = null;
                    inlined++;
                }
            }
        }
        Logger.INSTANCE.logln(war.jnt.dash.Level.INFO, Origin.METAPHOR,
                "FieldInlinerTransformer: Inlined " + inlined + " field values");
    }
}