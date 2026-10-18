package io.vibeminer.bootstrap;

import io.vibeminer.application.DefaultWorkspaceService;
import io.vibeminer.application.DiscoveryService;
import io.vibeminer.application.FixtureDiscovery;
import io.vibeminer.desktop.AtlasApplication;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.infrastructure.discovery.OfficialDiscoveryService;
import io.vibeminer.infrastructure.discovery.OfficialProductDiscoveryService;
import io.vibeminer.infrastructure.workspace.AppDataDirectory;
import io.vibeminer.infrastructure.workspace.SqliteWorkspaceRepository;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Composition root; launching never discovers game installations or starts network requests. */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    if (List.of(args).contains("--self-test")) {
      PackageSelfTest.run();
      return;
    }
    var official = new OfficialDiscoveryService();
    DiscoveryService discovery =
        new DiscoveryService() {
          @Override
          public List<BuildSnapshot> fixtureBuilds() {
            return FixtureDiscovery.builds();
          }

          @Override
          public CompletionStage<List<BuildSnapshot>> refresh(String productId, String region) {
            return official.refresh(productId, region);
          }
        };
    var workspaceExecutor =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64),
            runnable -> {
              var thread = new Thread(runnable, "vibeminer-workspaces");
              thread.setDaemon(true);
              return thread;
            });
    var dataDirectory = AppDataDirectory.resolve();
    var content = new LazyBootstrapService(() -> ContentRuntime.open(dataDirectory));
    var shutdown = new Thread(() -> shutdown(content, workspaceExecutor), "vibeminer-shutdown");
    Runtime.getRuntime().addShutdownHook(shutdown);
    var workspaceService =
        new DefaultWorkspaceService(
            new SqliteWorkspaceRepository(dataDirectory.resolve("catalog.sqlite")),
            workspaceExecutor,
            Clock.systemUTC());
    try {
      AtlasApplication.configure(
          discovery,
          workspaceService,
          new OfficialProductDiscoveryService(),
          content,
          new LazyFileLookupService(content),
          new LazySelectedFileService(content),
          List.of(args).contains("--fixtures"));
      javafx.application.Application.launch(AtlasApplication.class, args);
    } finally {
      shutdown(content, workspaceExecutor);
      try {
        Runtime.getRuntime().removeShutdownHook(shutdown);
      } catch (IllegalStateException alreadyShuttingDown) {
        /* The registered hook owns the same idempotent shutdown. */
      }
    }
  }

  private static void shutdown(
      LazyBootstrapService content, java.util.concurrent.ExecutorService workspaces) {
    try {
      content.close();
    } catch (RuntimeException failure) {
      System.err.println("VibeMiner content shutdown: " + failure.getMessage());
    }
    workspaces.shutdown();
    boolean interrupted = Thread.interrupted();
    try {
      if (!workspaces.awaitTermination(5, TimeUnit.SECONDS)) workspaces.shutdownNow();
    } catch (InterruptedException failure) {
      interrupted = true;
      workspaces.shutdownNow();
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }
}
