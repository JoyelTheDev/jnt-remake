package war.metaphor.mutator.misc;

import org.objectweb.asm.tree.*;
import war.configuration.ConfigurationSection;
import war.jnt.annotate.Level;
import war.jnt.annotate.Stability;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.Mutator;
import war.metaphor.tree.JClassNode;

import java.util.List;
import java.util.Map;

/**
 * AnnotationObfuscatorTransformer
 *
 * Removes annotation metadata that leaks structural information to reverse
 * engineers (decompilers, static analysis tools, JADX, etc.).
 *
 * Two categories are handled:
 *
 * 1. INVISIBLE annotations (@Retention(CLASS))
 *    These live in the bytecode but the JVM reflection API never exposes
 *    them. They are purely compile-time artifacts. Removing them is always
 *    safe and hides evidence of which annotation processors were used
 *    (Lombok, MapStruct, Hibernate validator, etc.).
 *    Covers: class, method, field, parameter, and type annotation lists.
 *
 * 2. ORPHANED visible annotations (@Retention(RUNTIME))
 *    After the renamer runs, any visible annotation whose descriptor
 *    references a class that was renamed no longer resolves — the original
 *    type name is gone. Keeping these produces dangling descriptors that
 *    are useless at runtime and only add noise. They are stripped here.
 *    All other visible annotations (Spring, JUnit, Jakarta EE, etc.) are
 *    left untouched so runtime framework behaviour is preserved.
 *
 * Additionally:
 *   - Local-variable annotations are always removed (debug info only).
 *   - @interface classes are skipped entirely.
 *
 * Registration in Metaphor.java:
 *   .mutator("annotation-obf", AnnotationObfuscatorTransformer.class)
 *
 * config.yml:
 *   annotation-obf:
 *     enabled: true
 *
 * Recommended order: run AFTER the renamer so orphan detection is accurate,
 * BEFORE strip so the two passes do not overlap.
 *
 *   order:
 *     - renamer.class
 *     - renamer.method
 *     - renamer.field
 *     - annotation-obf   ← here
 *     - strip
 */
@Stability(Level.HIGH)
public class AnnotationObfuscatorTransformer extends Mutator {

    public AnnotationObfuscatorTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
    }

    @Override
    public void run(ObfuscatorContext base) {
        Map<String, String> renamed = base.getClassRenameMap();
        for (JClassNode cn : base.getClasses()) {
            if (cn.isExempt())    continue;
            if (cn.isAnnotation()) continue;  // never touch @interface declarations

            processClass(cn, renamed);
        }
    }
    private void processClass(JClassNode cn, Map<String, String> renamed) {
        cn.invisibleAnnotations     = null;
        cn.invisibleTypeAnnotations = null;
        cn.visibleAnnotations       = stripOrphaned(cn.visibleAnnotations, renamed);
        cn.visibleTypeAnnotations   = stripOrphanedType(cn.visibleTypeAnnotations, renamed);
        for (MethodNode mn : cn.methods) {
            if (cn.isExempt(mn)) continue;
            mn.invisibleAnnotations              = null;
            mn.invisibleTypeAnnotations          = null;
            mn.invisibleParameterAnnotations     = null;
            mn.visibleLocalVariableAnnotations   = null;  // debug only
            mn.invisibleLocalVariableAnnotations = null;
            mn.visibleAnnotations     = stripOrphaned(mn.visibleAnnotations, renamed);
            mn.visibleTypeAnnotations = stripOrphanedType(mn.visibleTypeAnnotations, renamed);
            mn.visibleParameterAnnotations =
                    stripOrphanedParams(mn.visibleParameterAnnotations, renamed);
        }

        for (FieldNode fn : cn.fields) {
            if (cn.isExempt(fn)) continue;
            fn.invisibleAnnotations     = null;
            fn.invisibleTypeAnnotations = null;
            fn.visibleAnnotations       = stripOrphaned(fn.visibleAnnotations, renamed);
            fn.visibleTypeAnnotations   = stripOrphanedType(fn.visibleTypeAnnotations, renamed);
        }
    }

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
        // desc looks like "Lsome/Class;" — extract inner name
        if (desc.charAt(0) != 'L' || desc.charAt(desc.length() - 1) != ';') return false;
        String internalName = desc.substring(1, desc.length() - 1);
        return renamed.containsKey(internalName);
    }
}
