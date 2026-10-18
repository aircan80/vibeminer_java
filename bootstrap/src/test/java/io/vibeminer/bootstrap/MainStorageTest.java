package io.vibeminer.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainStorageTest {
  @TempDir Path normal;

  @Test
  void smokeProfilesAreFreshAndCannotResumeNormalAuthorizedWork() throws Exception {
    var previous = System.getProperty("vibeminer.dataDir");
    var sentinel = normal.resolve("catalog.sqlite");
    Files.writeString(sentinel, "normal profile must never be opened");
    System.setProperty("vibeminer.dataDir", normal.toString());
    Path first = null;
    Path second = null;
    try {
      first = Main.dataDirectory(new String[] {"--smoke-test"});
      second = Main.dataDirectory(new String[] {"--fixtures", "--smoke-test"});
      assertNotEquals(first, second);
      for (var profile : new Path[] {first, second}) {
        assertNotEquals(normal, profile);
        try (var files = Files.list(profile)) {
          assertEquals(0, files.count());
        }
        var client = new MetadataRuntimeTest.Client();
        try (var runtime = MetadataRuntime.open(profile, () -> client, g -> g);
            var owner =
                new LazyBootstrapService(
                    () -> {
                      throw new AssertionError("Smoke must contain no authorized download work");
                    })) {
          assertFalse(
              runtime
                  .resumeAuthorizedDownloads(owner)
                  .toCompletableFuture()
                  .get(5, TimeUnit.SECONDS));
          assertEquals(0, client.requests.get());
        }
      }
      assertEquals("normal profile must never be opened", Files.readString(sentinel));
      try (var files = Files.list(normal)) {
        assertEquals(1, files.count());
      }
      assertEquals(normal, Main.dataDirectory(new String[0]));
    } finally {
      if (previous == null) System.clearProperty("vibeminer.dataDir");
      else System.setProperty("vibeminer.dataDir", previous);
      delete(first);
      delete(second);
    }
  }

  @Test
  void smokeIgnoresEvenInvalidNormalProfileOverride() throws Exception {
    var previous = System.getProperty("vibeminer.dataDir");
    Path profile = null;
    System.setProperty("vibeminer.dataDir", "relative-invalid-profile");
    try {
      profile = Main.dataDirectory(new String[] {"--smoke-test"});
      assertTrue(Files.isDirectory(profile));
      assertThrows(IllegalArgumentException.class, () -> Main.dataDirectory(new String[0]));
    } finally {
      if (previous == null) System.clearProperty("vibeminer.dataDir");
      else System.setProperty("vibeminer.dataDir", previous);
      delete(profile);
    }
  }

  private static void delete(Path profile) throws Exception {
    if (profile == null || !profile.getFileName().toString().startsWith("vibeminer-smoke-")) return;
    try (var paths = Files.walk(profile)) {
      for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
    }
  }
}
