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

import java.util.List;
import java.util.Map;

@Stability(Level.HIGH)
public class AnnotationObfuscatorTransformer extends Mutator {

    private static final Logger logger = Logger.INSTANCE;

    public AnnotationObfuscatorTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
    }

    @Override
    public void run(ObfuscatorContext base) {
        Map<String, String> renamed = base.getClassRenameMap();
        int count = 0;

        for (JClassNode cn : base.getClasses()) {
            if (cn.isExempt())     continue;
            if (cn.isAnnotation()) continue;
            processClass(cn, renamed);
            count++;
        }

        logger.logln(war.jnt.dash.Level.INFO, Origin.METAPHOR,
                String.format("AnnotationObfuscatorTransformer: processed %d classes", count));
    }

    private void processClass(JClassNode cn, Map<String, String> renamed) {

        // Class-level
        cn.invisibleAnnotations     = null;
        cn.invisibleTypeAnnotations = null;
        cn.visibleAnnotations       = stripOrphaned(cn.visibleAnnotations, renamed);
        cn.visibleTypeAnnotations   = stripOrphanedType(cn.visibleTypeAnnotations, renamed);

        // Methods
        for (MethodNode mn : cn.methods) {
            if (cn.isExempt(mn)) continue;

            mn.invisibleAnnotations              = null;
            mn.invisibleTypeAnnotations          = null;
            mn.invisibleParameterAnnotations     = null;
            mn.visibleLocalVariableAnnotations   = null;
            mn.invisibleLocalVariableAnnotations = null;

            mn.visibleAnnotations          = stripOrphaned(mn.visibleAnnotations, renamed);
            mn.visibleTypeAnnotations      = stripOrphanedType(mn.visibleTypeAnnotations, renamed);
            mn.visibleParameterAnnotations = stripOrphanedParams(mn.visibleParameterAnnotations, renamed);
        }

        // Fields
        for (FieldNode fn : cn.fields) {
            if (cn.isExempt(fn)) continue;

            fn.invisibleAnnotations     = null;
            fn.invisibleTypeAnnotations = null;
            fn.visibleAnnotations       = stripOrphaned(fn.visibleAnnotations, renamed);
            fn.visibleTypeAnnotations   = stripOrphanedType(fn.visibleTypeAnnotations, renamed);
        }
    }

    // -------------------------------------------------------------------------

    private static List<AnnotationNode> stripOrphaned(
            List<AnnotationNode> list, Map<String, String> renamed) {
        if (list == null || list.isEmpty() || renamed == null || renamed.isEmpty())
            return list;
        list.removeIf(an -> isOrphaned(an.desc, renamed));
        return list.isEmpty() ? null : list;
    }

    private static List<TypeAnnotationNode> stripOrphanedType(
            List<TypeAnnotationNode> list, Map<String, String> renamed) {
        if (list == null || list.isEmpty() || renamed == null || renamed.isEmpty())
            return list;
        list.removeIf(an -> isOrphaned(an.desc, renamed));
        return list.isEmpty() ? null : list;
    }

    private static List<AnnotationNode>[] stripOrphanedParams(
            List<AnnotationNode>[] params, Map<String, String> renamed) {
        if (params == null || renamed == null || renamed.isEmpty()) return params;
        for (List<AnnotationNode> slot : params) {
            if (slot == null) continue;
            slot.removeIf(an -> isOrphaned(an.desc, renamed));
        }
        return params;
    }

    private static boolean isOrphaned(String desc, Map<String, String> renamed) {
        if (desc == null || desc.length() < 3) return false;
        if (desc.charAt(0) != 'L' || desc.charAt(desc.length() - 1) != ';') return false;
        String internalName = desc.substring(1, desc.length() - 1);
        return renamed.containsKey(internalName);
    }
}
