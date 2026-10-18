package io.vibeminer.bootstrap;

import io.vibeminer.desktop.AtlasApplication;
import io.vibeminer.infrastructure.discovery.OfficialProductDiscoveryService;
import io.vibeminer.infrastructure.workspace.AppDataDirectory;
import java.util.List;

/** Composition root; only previously authorized downloads may resume during launch. */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    if (List.of(args).contains("--self-test")) {
      PackageSelfTest.run();
      return;
    }
    var dataDirectory = dataDirectory(args);
    var metadata = MetadataRuntime.open(dataDirectory);
    var content = new LazyBootstrapService(() -> ContentRuntime.open(dataDirectory));
    var mining = new LazyBuildServices(content);
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
      AtlasApplication.configureBuildReadiness(
          metadata.readiness(),
          io.vibeminer.infrastructure.buildopening.BuildOpeningPolicy.SCOPE_REVISION,
          io.vibeminer.infrastructure.inventory.SqliteBuildInventory.REVISION);
      AtlasApplication.configureBuildOpening(new LazyBuildOpeningService(content));
      AtlasApplication.configureBuildMining(
          mining, mining, mining, BuildMiningRuntime.overviewLimits());
      metadata
          .resumeAuthorizedDownloads(content)
          .whenComplete(
              (resumed, failure) -> {
                if (failure != null)
                  System.err.println("VibeMiner: saved downloads could not be resumed at startup");
              });
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
      try {
        mining.close();
      } finally {
        shutdown(content, metadata);
        try {
          Runtime.getRuntime().removeShutdownHook(shutdown);
        } catch (IllegalStateException alreadyShuttingDown) {
          /* The registered hook owns the same idempotent shutdown. */
        }
      }
    }
  }

  /**
   * Smoke verification must never open the normal profile or its authorized work. The tiny smoke
   * profile is intentionally left in the OS temporary directory: shutdown can retain actual
   * workers, so deleting it here would risk deleting storage still in use.
   */
  static java.nio.file.Path dataDirectory(String[] args) {
    if (!List.of(args).contains("--smoke-test")) return AppDataDirectory.resolve();
    try {
      return java.nio.file.Files.createTempDirectory("vibeminer-smoke-");
    } catch (java.io.IOException failure) {
      throw new java.io.UncheckedIOException(
          "Could not create isolated GUI smoke storage", failure);
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
