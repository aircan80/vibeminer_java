package io.vibeminer.application.buildlibrary;

import static io.vibeminer.application.buildlibrary.BuildLibraryPolicyTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.discoveryhistory.DiscoveryHistoryException;
import java.util.*;
import org.junit.jupiter.api.Test;

class BuildLibraryProductObservationTest {
  @Test
  void emptyCapturePreservesPairedReceiptsWithoutInventingRegionalSuccess() {
    var event = event("us", "a".repeat(32), "raw", NOW);
    var observation =
        new BuildLibraryProductObservation(
            "wow",
            NOW,
            event.parserRevision(),
            event.versions(),
            event.cdns(),
            List.of(),
            List.of(),
            Optional.empty());
    assertSame(event.versions().source(), observation.versions().source());
    assertTrue(observation.events().isEmpty());
    assertTrue(observation.failures().isEmpty());
  }

  @Test
  void actualRegionalOutcomesShareOneCaptureAndCannotConflict() {
    var event = event("us", "a".repeat(32), "raw", NOW);
    var failure =
        new BuildLibraryProductObservation.RegionalFailure(
            new BuildLibraryScope("wow", "eu"),
            DiscoveryHistoryException.Code.UNSUPPORTED_INTERPRETATION,
            "Ambiguous CDN row");
    var observation =
        new BuildLibraryProductObservation(
            "wow",
            NOW,
            event.parserRevision(),
            event.versions(),
            event.cdns(),
            List.of(event),
            List.of(failure),
            Optional.empty());
    assertSame(
        observation.versions().source(),
        observation
            .events()
            .getFirst()
            .builds()
            .getFirst()
            .build()
            .evidence()
            .documents()
            .getFirst());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuildLibraryProductObservation(
                "wow",
                NOW.plusSeconds(1),
                event.parserRevision(),
                event.versions(),
                event.cdns(),
                List.of(event),
                List.of(),
                Optional.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuildLibraryProductObservation(
                "wow",
                NOW,
                event.parserRevision(),
                event.versions(),
                event.cdns(),
                List.of(event),
                List.of(
                    new BuildLibraryProductObservation.RegionalFailure(
                        new BuildLibraryScope("wow", "us"), failure.code(), failure.message())),
                Optional.empty()));
  }

  @Test
  void partialResultRetainsPreciseFailureWithoutDiscardingCapturedEvents() {
    var event = event("us", "a".repeat(32), "raw", NOW);
    var observation =
        new BuildLibraryProductObservation(
            "wow",
            NOW,
            event.parserRevision(),
            event.versions(),
            event.cdns(),
            List.of(event),
            List.of(),
            Optional.empty());
    var ticket = new BuildLibraryProductCheckTicket(UUID.randomUUID(), "wow", 8, NOW);
    var cause = new BuildLibraryException(BuildLibraryException.Code.STORAGE_UNAVAILABLE, "disk");
    var failure =
        new BuildLibraryRefreshResult.ScopeFailure(
            new BuildLibraryScope("wow", "us"),
            BuildLibraryRefreshResult.Phase.LIBRARY_REGISTRATION,
            cause);
    var result =
        new BuildLibraryRefreshResult(ticket, observation, List.of(), List.of(failure), true);
    assertSame(cause, result.failures().getFirst().cause());
    assertEquals(event, result.observation().events().getFirst());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuildLibraryRefreshResult(
                new BuildLibraryProductCheckTicket(UUID.randomUUID(), "wow_beta", 8, NOW),
                observation,
                List.of(),
                List.of(),
                false));
  }
}
