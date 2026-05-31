package war.metaphor.mutator.anti;

import org.objectweb.asm.tree.*;
import war.configuration.ConfigurationSection;
import war.jnt.annotate.Level;
import war.jnt.annotate.Stability;
import war.jnt.dash.Logger;
import war.jnt.dash.Origin;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.Mutator;
import war.metaphor.tree.JClassNode;
import war.metaphor.util.builder.ClassBuilder;
import war.metaphor.util.builder.InsnListBuilder;
import war.metaphor.util.builder.MethodBuilder;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

@Stability(Level.HIGH)
public class AntiDebugTransformer extends Mutator {

    private static final String GUARD_CLASS = "dev/ark/guard/AntiDebug";
    private static final int    VERSION     = V11;

    private final String  reaction;
    private final boolean checkJdwp;
    private final boolean checkAgents;
    private final boolean checkTools;
    private final boolean checkThreads;
    private final int     injectionChance;

    public AntiDebugTransformer(ObfuscatorContext base, ConfigurationSection config) {
        super(base, config);
        this.reaction        = config == null ? "exit"  : config.getString("reaction", "exit");
        this.checkJdwp       = config == null || config.getBoolean("check-jdwp", true);
        this.checkAgents     = config == null || config.getBoolean("check-agents", true);
        this.checkTools      = config == null || config.getBoolean("check-tools", true);
        this.checkThreads    = config == null || config.getBoolean("check-threads", true);
        this.injectionChance = config == null ? 30 : config.getInt("injection-chance", 30);
    }

    @Override
    public void run(ObfuscatorContext base) {
        base.getClasses().add(buildGuardClass());

        int injected = 0;
        for (JClassNode classNode : base.getClasses()) {
            if (classNode.isExempt()) continue;
            if (classNode.name.equals(GUARD_CLASS)) continue;

            for (MethodNode method : classNode.methods) {
                if (classNode.isExempt(method)) continue;
                if (Modifier.isAbstract(method.access)) continue;
                if (method.name.equals("<clinit>")) continue;
                if (method.instructions.size() == 0) continue;
                if (rand.nextInt(100) >= injectionChance) continue;

                method.instructions.insert(
                    InsnListBuilder.builder()
                        .invokestatic(GUARD_CLASS, "check", "()V")
                        .build()
                );
                injected++;
            }
        }
        Logger.INSTANCE.logln(war.jnt.dash.Level.INFO, Origin.METAPHOR,
                "AntiDebugTransformer: Injected guard into " + injected + " methods");
    }

    private JClassNode buildGuardClass() {
        JClassNode cls = ClassBuilder.create()
                .withName(GUARD_CLASS)
                .withSuperName("java/lang/Object")
                .withAccess(ACC_PUBLIC | ACC_FINAL | ACC_SYNTHETIC)
                .build();
        cls.version = VERSION;

        FieldNode checkedField = new FieldNode(
                ACC_PRIVATE | ACC_STATIC | ACC_VOLATILE | ACC_SYNTHETIC,
                "checked", "Z", null, null);
        cls.fields.add(checkedField);

        MethodNode clinit = MethodBuilder.create()
                .withName("<clinit>")
                .withDesc("()V")
                .withAccess(ACC_STATIC)
                .withInstructions(InsnListBuilder.builder()
                        .iconst_0()
                        .putstatic(GUARD_CLASS, "checked", "Z")
                        ._return()
                        .build())
                .build();
        clinit.maxLocals = 0;
        clinit.maxStack  = 1;
        cls.methods.add(clinit);

        cls.methods.add(buildCheckMethod());
        cls.methods.add(buildReactMethod());

        return cls;
    }

    private MethodNode buildCheckMethod() {
        InsnListBuilder b = InsnListBuilder.builder();
        LabelNode end = new LabelNode();

        b.getstatic(GUARD_CLASS, "checked", "Z")
         .ifne(end)
         .iconst_1()
         .putstatic(GUARD_CLASS, "checked", "Z");

        if (checkJdwp)    b.list(buildJdwpCheck());
        if (checkAgents)  b.list(buildAgentCheck());
        if (checkTools)   b.list(buildToolCheck());
        if (checkThreads) b.list(buildThreadCheck());

        b.label(end)
         ._return();

        MethodNode check = MethodBuilder.create()
                .withName("check")
                .withDesc("()V")
                .withAccess(ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC)
                .withInstructions(b.build())
                .build();
        check.maxLocals = 4;
        check.maxStack  = 3;
        return check;
    }

    private InsnList buildJdwpCheck() {
        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd   = new LabelNode();
        return InsnListBuilder.builder()
                .invokestatic("java/lang/management/ManagementFactory", "getRuntimeMXBean",
                              "()Ljava/lang/management/RuntimeMXBean;")
                .invokeinterface("java/lang/management/RuntimeMXBean", "getInputArguments",
                                 "()Ljava/util/List;")
                .invokeinterface("java/util/List", "iterator", "()Ljava/util/Iterator;")
                .astore(0)
                .label(loopStart)
                .aload(0)
                .invokeinterface("java/util/Iterator", "hasNext", "()Z")
                .ifeq(loopEnd)
                .aload(0)
                .invokeinterface("java/util/Iterator", "next", "()Ljava/lang/Object;")
                .checkcast("java/lang/String")
                .astore(1)
                .aload(1)
                .constant("jdwp")
                .invokevirtual("java/lang/String", "contains", "(Ljava/lang/CharSequence;)Z")
                .ifeq("jdwp_no")
                .invokestatic(GUARD_CLASS, "react", "()V")
                .label("jdwp_no")
                ._goto(loopStart)
                .label(loopEnd)
                .build();
    }

    private InsnList buildAgentCheck() {
        return InsnListBuilder.builder()
                .constant("sun.java.command")
                .constant("")
                .invokestatic("java/lang/System", "getProperty",
                              "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")
                .astore(0)
                .aload(0)
                .constant("-javaagent")
                .invokevirtual("java/lang/String", "contains", "(Ljava/lang/CharSequence;)Z")
                .ifne("agent_hit")
                .aload(0)
                .constant("Attach")
                .invokevirtual("java/lang/String", "contains", "(Ljava/lang/CharSequence;)Z")
                .ifeq("agent_ok")
                .label("agent_hit")
                .invokestatic(GUARD_CLASS, "react", "()V")
                .label("agent_ok")
                .build();
    }

    private InsnList buildToolCheck() {
        List<String> toolProps = List.of(
                "recaf.version",
                "bcv.version",
                "jadx.version",
                "jd.version"
        );
        InsnListBuilder b = InsnListBuilder.builder();
        for (String prop : toolProps) {
            LabelNode notFound = new LabelNode();
            b.constant(prop)
             .invokestatic("java/lang/System", "getProperty",
                           "(Ljava/lang/String;)Ljava/lang/String;")
             .ifnull(notFound)
             .invokestatic(GUARD_CLASS, "react", "()V")
             .label(notFound);
        }
        return b.build();
    }

    private InsnList buildThreadCheck() {
        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd   = new LabelNode();
        return InsnListBuilder.builder()
                .invokestatic("java/lang/Thread", "getAllStackTraces", "()Ljava/util/Map;")
                .invokeinterface("java/util/Map", "keySet", "()Ljava/util/Set;")
                .invokeinterface("java/util/Set", "iterator", "()Ljava/util/Iterator;")
                .astore(0)
                .label(loopStart)
                .aload(0)
                .invokeinterface("java/util/Iterator", "hasNext", "()Z")
                .ifeq(loopEnd)
                .aload(0)
                .invokeinterface("java/util/Iterator", "next", "()Ljava/lang/Object;")
                .checkcast("java/lang/Thread")
                .invokevirtual("java/lang/Thread", "getName", "()Ljava/lang/String;")
                .astore(1)
                .aload(1)
                .constant("JDWP")
                .invokevirtual("java/lang/String", "contains", "(Ljava/lang/CharSequence;)Z")
                .ifne("thread_hit")
                .aload(1)
                .constant("JDI ")
                .invokevirtual("java/lang/String", "contains", "(Ljava/lang/CharSequence;)Z")
                .ifeq("thread_ok")
                .label("thread_hit")
                .invokestatic(GUARD_CLASS, "react", "()V")
                .label("thread_ok")
                ._goto(loopStart)
                .label(loopEnd)
                .build();
    }

    private MethodNode buildReactMethod() {
        InsnListBuilder b = InsnListBuilder.builder();

        if ("throw".equals(reaction)) {
            b._new("java/lang/RuntimeException")
             .dup()
             .constant("")
             .invokespecial("java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V")
             .athrow();
        } else {
            b.constant(-1)
             .invokestatic("java/lang/System", "exit", "(I)V");
        }
        b._return();

        MethodNode react = MethodBuilder.create()
                .withName("react")
                .withDesc("()V")
                .withAccess(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC)
                .withInstructions(b.build())
                .build();
        react.maxLocals = 0;
        react.maxStack  = 3;
        return react;
    }
}