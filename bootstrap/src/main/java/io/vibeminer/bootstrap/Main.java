package io.vibeminer.bootstrap;

import io.vibeminer.desktop.AtlasApplication;
import io.vibeminer.infrastructure.discovery.OfficialProductDiscoveryService;
import io.vibeminer.infrastructure.workspace.AppDataDirectory;
import java.util.List;

/** Composition root; launching never discovers game installations or starts network requests. */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    if (List.of(args).contains("--self-test")) {
      PackageSelfTest.run();
      return;
    }
    var dataDirectory = AppDataDirectory.resolve();
    var metadata = MetadataRuntime.open(dataDirectory);
    var content = new LazyBootstrapService(() -> ContentRuntime.open(dataDirectory));
    var shutdown = new Thread(() -> shutdown(content, metadata), "vibeminer-shutdown");
    Runtime.getRuntime().addShutdownHook(shutdown);
    try {
      AtlasApplication.configure(
          metadata.discovery(),
          metadata.workspaces(),
          new OfficialProductDiscoveryService(),
          content,
          new LazyFileLookupService(content),
          new LazySelectedFileService(content),
          new LazyItemProjectionService(content),
          new LazyMetadataReuseService(content),
          new LazyMetadataRepairService(content),
          new LazyItemNamesService(content),
          new LazyItemEvidenceService(content),
          List.of(args).contains("--fixtures"));
      AtlasApplication.configureBuildLibrary(metadata.library());
      AtlasApplication.configureItemEvidenceDestinationChooser(
          (owner, suggestedFileName) -> {
            var chooser = new javafx.stage.FileChooser();
            chooser.setTitle("Choose Item evidence destination");
            chooser.setInitialFileName(suggestedFileName);
            chooser
                .getExtensionFilters()
                .add(
                    new javafx.stage.FileChooser.ExtensionFilter(
                        "JSON evidence (*.json)", "*.json"));
            return java.util.Optional.ofNullable(chooser.showSaveDialog(owner))
                .map(java.io.File::getPath);
          });
      javafx.application.Application.launch(AtlasApplication.class, args);
    } finally {
      shutdown(content, metadata);
      try {
        Runtime.getRuntime().removeShutdownHook(shutdown);
      } catch (IllegalStateException alreadyShuttingDown) {
        /* The registered hook owns the same idempotent shutdown. */
      }
    }
  }

  private static void shutdown(LazyBootstrapService content, MetadataRuntime metadata) {
    try {
      metadata.close();
    } catch (RuntimeException failure) {
      System.err.println("VibeMiner metadata shutdown: " + failure.getMessage());
    }
    try {
      content.close();
    } catch (RuntimeException failure) {
      System.err.println("VibeMiner content shutdown: " + failure.getMessage());
    }
  }
}
