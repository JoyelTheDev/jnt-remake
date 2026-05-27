package war.metaphor;

import war.configuration.ConfigurationSection;
import war.jar.JarReader;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.integer.*;
import war.metaphor.mutator.data.strings.*;
import war.metaphor.mutator.data.strings.poly2.*;
import war.metaphor.mutator.flow.*;
import war.metaphor.mutator.rename.*;
import war.metaphor.mutator.integrity.CallGraphIntegrityMutator;
import war.metaphor.mutator.anti.*;
import war.metaphor.mutator.integrity.mainCallCheck.MainCallCheckMutator;
import war.metaphor.mutator.integrity.method.MethodIntegrityMutator;
import war.metaphor.mutator.loader.*;
import war.metaphor.mutator.misc.*;
import war.metaphor.mutator.split.*;
import war.metaphor.mutator.optimization.*;
import war.metaphor.mutator.parameter.*;
import war.metaphor.mutator.ref.ReferenceTransformer;
import war.metaphor.mutator.runtime.*;
import war.metaphor.mutator.splash.SplashScreenTransformer;
import war.metaphor.mutator.virtualization.*;

import java.nio.file.Path;

public class Metaphor {

    public ObfuscatorContext buildObfuscatePass(JarReader intake, ConfigurationSection cfg, String dir) {
        return ObfuscatorContext.builder()
                .input(intake.getInput().toPath())
                .output(Path.of(String.format("%s/metaphor-temp.jar", dir)))
                .mappings(cfg.getStringList("mappings"))
                .section("mutators.metaphor")
                .config(cfg)
                .classes(intake.getClasses())
                .libraries(intake.getLibraries())
                .resources(intake.getResources())
                .manifest(intake.getManifest())

                .mutator("method-call-fix", MethodCallFixer.class)
                .mutator("bootstrap-entry", BootstrapEntryTransformer.class)

                .mutator("unused-method-remover", UnusedMethodTransformer.class)
                .mutator("unused-class-remover", UnusedClassTransformer.class)

                .mutator("optimizer", OptimizationTransformer.class)
                .mutator("inlining", MethodInliningTransformer.class)
                .mutator("field-initialize", FieldInlinerTransformer.class)
                .mutator("access-unify", AccessUnifyTransformer.class)

                .mutator("internal-class-integrator", InternalClassIntegrateTransformer.class)

                .mutator("renamer.class", ClassRenameTransformer.class)
                .mutator("renamer.method", MethodRenameTransformer.class)
                .mutator("renamer.field", FieldRenameTransformer.class)
                .mutator("renamer.localvariable", LocalVariableRenameTransformer.class)
                .mutator("renamer.desc", DescriptorTransformer.class)

                .mutator("main-call-check", MainCallCheckMutator.class)
                .mutator("call-graph", CallGraphIntegrityMutator.class)
                .mutator("method-integrity", MethodIntegrityMutator.class)
            
                .mutator("anti-debug",  AntiDebugTransformer.class) 
                .mutator("anti-tamper", AntiTamperTransformer.class)
                .mutator("anti-dump", AntiDumpTransformer.class)

                .mutator("string.poly", StringTransformer.class)
                .mutator("string.poly2", NewStringTransformer.class)
                .mutator("string.light", LightStringTransformer.class)
                .mutator("string.split", StringSplitTransformer.class)
                .mutator("string.stack", StringStackTransformer.class)
                .mutator("ahegao", AhegaoTransformer.class)
            
                .mutator("flow.break", BlockBreakTransformer.class)
                .mutator("flow.flattening", ControlFlowFlatteningTransformer.class)
                .mutator("method-split",    MethodSplitTransformer.class)
                .mutator("flow.shuffle", InstructionShuffleTransformer.class)
                .mutator("flow.switch", SwitchTransformer.class)
                .mutator("flow.traps", TrapEdgeTransformer.class)
                .mutator("flow.opaque", OpaquePredicatesTransformer.class)
            
                .mutator("number.salt", SaltingIntegerTransformer.class)
                .mutator("number.table", IntegerTableTransformer.class)
                .mutator("mba", MBATransformer.class)
                .mutator("numberobf", NumberTransformer.class)
                .mutator("member-shuffle", MemberShuffleTransformer.class)
                .mutator("dead-code",  DeadCodeInjectorTransformer.class)
                .mutator("virtualize",  VirtualizingTransformer.class)  
                

                .mutator("ref", ReferenceTransformer.class)
                .mutator("var-duplicate", VarDuplicateTransformer.class)

                .mutator("lift-constructors", LiftInitializersTransformer.class)

                .mutator("watermark", WatermarkTransformer.class)

                .mutator("strip", StripTransformer.class)

                .mutator("dot-graph", DotExportTransformer.class)

                .mutator("indy-rewriter", IndyTransformer.class)

                .mutator("splash-screen", SplashScreenTransformer.class)

                //.mutator("goto-to-jsr", GotoToJsrMutator.class)
                .mutator("array-rewriter", MultiNewArrayTransformer.class)

                .mutator("runtime-patch", RuntimePatchTransformer.class)
                .mutator("exchange", ExchangeTransformer.class)
                .build();
    }

    public ObfuscatorContext buildPackagePass(JarReader intake, ConfigurationSection cfg, String dir) {
        return ObfuscatorContext.builder()
                .input(intake.getInput().toPath())
                .output(Path.of(String.format("%s/output-final.jar", dir)))
                .section("mutators.jnt")
                .config(cfg)
                .classes(intake.getClasses())
                .libraries(intake.getLibraries())
                .resources(intake.getResources())
                .manifest(intake.getManifest())
                .mutator("cleanup", CleanupMutator.class)
                .mutator("integrate", IntegrateLoaderMutator.class)
                .build();
    }
}
