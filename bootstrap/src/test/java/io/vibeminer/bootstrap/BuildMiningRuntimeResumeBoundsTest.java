package io.vibeminer.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.buildretention.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class BuildMiningRuntimeResumeBoundsTest {
  record Page(String cursor, int maximum) {}

  static List<BuildRetention.Intent> intents(int count) {
    var result = new ArrayList<BuildRetention.Intent>();
    for (int i = 0; i < count; i++) {
      String key = String.format("%064x", i + 1);
      var target = new BuildRetention.Target(key, key, key, key, 1, "all-declared", key);
      result.add(
          new BuildRetention.Intent(
              target,
              BuildRetention.Mode.DOWNLOAD_ALL,
              1,
              new BuildRetention.Policy(100, 1000, 1000, 100, 3),
              Instant.EPOCH,
              1,
              0,
              new BuildRetention.Cost(0, 0, 0)));
    }
    return List.copyOf(result);
  }

  static java.util.function.BiFunction<String, Integer, List<BuildRetention.Intent>> repository(
      List<BuildRetention.Intent> saved, List<Page> requests) {
    return (after, maximum) -> {
      requests.add(new Page(after, maximum));
      return saved.stream()
          .filter(i -> i.target().identity().compareTo(after) > 0)
          .sorted(Comparator.comparing(i -> i.target().identity()))
          .limit(maximum)
          .toList();
    };
  }

  static BuildRetentionActivation activation(
      java.util.function.Consumer<BuildRetention.Intent> resumed) {
    return new BuildRetentionActivation() {
      public void resumeDownloadAll(BuildRetention.Intent intent) {
        resumed.accept(intent);
      }

      public void withdraw(BuildRetention.Target target, long generation) {
        fail("Startup cannot withdraw a saved preference");
      }
    };
  }

  @Test
  void seventeenthSavedScopeIsProbedAndReportedWithoutActivatingOrMutatingIt() {
    var saved = intents(17);
    var pages = new ArrayList<Page>();
    var resumed = new ArrayList<BuildRetention.Intent>();
    var notices = new ArrayList<String>();
    BuildMiningRuntime.resumeAuthorized(
        repository(saved, pages), activation(resumed::add), CancellationToken.none(), notices::add);
    var ordered = saved.stream().sorted(Comparator.comparing(i -> i.target().identity())).toList();
    assertEquals(ordered.subList(0, 16), resumed);
    assertEquals(
        List.of(new Page("", 16), new Page(ordered.get(15).target().identity(), 1)), pages);
    assertEquals(1, notices.size());
    assertTrue(notices.getFirst().contains("startup limit 16"));
    assertTrue(notices.getFirst().contains("preferences were kept"));
    assertFalse(resumed.contains(ordered.get(16)));
    assertEquals(17, saved.size());
  }

  @Test
  void exactlySixteenDoesNotProduceAFalseDeferredNotice() {
    var pages = new ArrayList<Page>();
    var resumed = new ArrayList<BuildRetention.Intent>();
    var notices = new ArrayList<String>();
    BuildMiningRuntime.resumeAuthorized(
        repository(intents(16), pages),
        activation(resumed::add),
        CancellationToken.none(),
        notices::add);
    assertEquals(16, resumed.size());
    assertEquals(2, pages.size());
    assertTrue(notices.isEmpty());
  }

  @Test
  void failedActivationDoesNotRewriteAuthorizationOrBlockRemainingFirstPage() {
    var saved = intents(17);
    var pages = new ArrayList<Page>();
    var attempted = new ArrayList<BuildRetention.Intent>();
    var notices = new ArrayList<String>();
    BuildMiningRuntime.resumeAuthorized(
        repository(saved, pages),
        activation(
            intent -> {
              attempted.add(intent);
              if (attempted.size() == 1)
                throw new BuildRetentionException(
                    BuildRetentionException.Code.LIMIT_EXCEEDED, "full");
            }),
        CancellationToken.none(),
        notices::add);
    assertEquals(16, attempted.size());
    assertEquals(2, notices.size());
    assertTrue(notices.getFirst().contains("needs retry"));
    assertEquals(BuildRetention.Mode.DOWNLOAD_ALL, saved.getFirst().mode());
  }

  @Test
  void cancellationStopsBeforeAnotherActivationOrOverflowProbe() {
    var pages = new ArrayList<Page>();
    var resumed = new ArrayList<BuildRetention.Intent>();
    var canceled = new java.util.concurrent.atomic.AtomicBoolean();
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () ->
            BuildMiningRuntime.resumeAuthorized(
                repository(intents(17), pages),
                activation(
                    intent -> {
                      resumed.add(intent);
                      if (resumed.size() == 3) canceled.set(true);
                    }),
                canceled::get,
                notice -> fail("Canceled startup should not report successful deferral")));
    assertEquals(3, resumed.size());
    assertEquals(1, pages.size());
  }
}
