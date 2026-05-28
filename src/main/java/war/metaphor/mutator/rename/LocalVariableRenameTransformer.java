package war.metaphor.mutator.rename;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import war.configuration.ConfigurationSection;
import war.jnt.annotate.Level;
import war.jnt.annotate.Stability;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.Mutator;
import war.metaphor.tree.JClassNode;
import war.metaphor.util.Dictionary;
import war.metaphor.util.Purpose;

import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;


/* * renamer:
 *   localvariable:
 *     enabled: true
 *     dictionary: illusion   # illusion / unicode / cjk / random / alpha / keyword / counter
 *     length: 5              # base name length (dictionary-dependent)
 *     prefix: ""             # optional fixed prefix for every generated name
 *     skip-this: true        # true = leave the implicit 'this' slot name alone
 *     skip-params: false     # true = leave explicit parameter names alone
 * }</pre>
 */
@Stability(Level.HIGH)
public class LocalVariableRenameTransformer extends Mutator {

    private final Dictionary.Mode mode;
    private final String          prefix;
    private final int             length;
    private final boolean         skipThis;
    private final boolean         skipParams;

    public LocalVariableRenameTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
        this.mode       = Dictionary.Mode.of(config == null ? null : config.getString("dictionary", "random"));
        this.prefix     = config == null ? ""    : config.getString("prefix", "");
        this.length     = config == null ? 5     : config.getInt("length", 5);
        this.skipThis   = config == null || config.getBoolean("skip-this", true);
        this.skipParams = config == null || config.getBoolean("skip-params", false);
    }

    @Override
    public void run(ObfuscatorContext base) {
        for (JClassNode classNode : base.getClasses()) {
            if (classNode.isExempt()) continue;
            for (MethodNode method : classNode.methods) {
                if (classNode.isExempt(method))             continue;
                if (Modifier.isAbstract(method.access))     continue;
                if (Modifier.isNative(method.access))       continue;
                if (method.localVariables == null
                        || method.localVariables.isEmpty()) continue;
                renameLocals(method);
            }
        }
    }

    private void renameLocals(MethodNode method) {
        boolean isStatic  = Modifier.isStatic(method.access);
        int firstParamSlot = isStatic ? 0 : 1; // slot 0 = 'this' in instance methods
        int firstLocalSlot = firstParamSlot + paramSlotCount(method);
        Map<Integer, String> slotToName = new HashMap<>();
        for (LocalVariableNode lv : method.localVariables) {
            int idx = lv.index;
            if (!isStatic && idx == 0) {
                if (skipThis) continue; // leave "this" alone by default
            }

            if (idx >= firstParamSlot && idx < firstLocalSlot) {
                if (skipParams) continue;
            }

            String newName = slotToName.computeIfAbsent(idx,
                    __ -> Dictionary.gen(length, Purpose.GENERIC, mode, prefix));
            lv.name = newName;
        }
    }

    private static int paramSlotCount(MethodNode method) {
        int slots = 0;
        for (Type argType : Type.getArgumentTypes(method.desc)) {
            slots += argType.getSize(); // 2 for long/double, 1 for everything else
        }
        return slots;
    }
}
