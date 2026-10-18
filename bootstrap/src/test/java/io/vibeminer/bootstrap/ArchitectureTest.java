package io.vibeminer.bootstrap;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import java.net.URL;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.*;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    @Test void coreHasNoFrameworkOrAdapterDependencies() {
        var classes = new ClassFileImporter().importPackages("io.vibeminer");
        noClasses().that().resideInAnyPackage("io.vibeminer.domain..", "io.vibeminer.application..")
            .should().dependOnClassesThat().resideInAnyPackage("javafx..", "java.sql..", "java.net..", "java.nio.file..", "java.nio.channels..",
                "io.vibeminer.infrastructure..", "io.vibeminer.desktop..", "io.vibeminer.bootstrap..")
            .check(classes);
        noClasses().that().resideInAPackage("io.vibeminer.desktop..")
            .should().dependOnClassesThat(resideInAnyPackage("io.vibeminer.infrastructure..", "io.vibeminer.bootstrap..", "java.sql..", "java.net..", "java.nio.file..", "java.nio.channels..").and(not(equivalentTo(URL.class))))
            .check(classes);
        noClasses().that().resideInAPackage("io.vibeminer.desktop..")
            .should().callMethod(URL.class, "openStream").check(classes);
        noClasses().that().resideInAPackage("io.vibeminer.desktop..")
            .should().callMethod(URL.class, "openConnection").check(classes);
        noClasses().that().resideInAPackage("io.vibeminer.desktop..")
            .should().callMethod(URL.class, "openConnection", java.net.Proxy.class).check(classes);
        noClasses().that().resideInAPackage("io.vibeminer.infrastructure..")
            .should().dependOnClassesThat().resideInAnyPackage("io.vibeminer.desktop..", "javafx..").check(classes);
        noClasses().that().resideOutsideOfPackage("io.vibeminer.bootstrap..")
            .should().dependOnClassesThat().resideInAPackage("io.vibeminer.bootstrap..").check(classes);
        noClasses().that().resideInAPackage("io.vibeminer.domain..")
            .should().dependOnClassesThat().resideInAPackage("io.vibeminer.application..").check(classes);
    }
}
