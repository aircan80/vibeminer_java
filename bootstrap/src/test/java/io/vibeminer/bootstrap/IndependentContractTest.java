package io.vibeminer.bootstrap;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Independent checks for filesystem escape hatches and the pure metadata parser. */
class IndependentContractTest {
    @Test void coreAndDesktopCannotUseLegacyFilesystemAdapters() {
        var classes = new ClassFileImporter().importPackages("io.vibeminer");
        noClasses().that().resideInAnyPackage("io.vibeminer.domain..",
                        "io.vibeminer.application..", "io.vibeminer.desktop..")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.io.File")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.FileInputStream")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.FileOutputStream")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.FileReader")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.FileWriter")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.RandomAccessFile")
                .check(classes);
    }

    @Test void metadataParserCannotFetchPersistOrPaint() {
        var classes = new ClassFileImporter().importPackages("io.vibeminer");
        noClasses().that().haveFullyQualifiedName(
                        "io.vibeminer.infrastructure.discovery.BlizzardMetadataParser")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "java.net..", "java.sql..", "java.nio.file..", "java.nio.channels..",
                        "javafx..", "io.vibeminer.desktop..", "io.vibeminer.bootstrap..")
                .check(classes);
    }
}
