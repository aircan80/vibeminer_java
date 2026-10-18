package io.vibeminer.application.selectedfile;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SelectedFileContractsTest {
  @Test
  void emptyPartialSearchCannotClaimCompletedAbsence() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ArchiveSearchResult(Optional.empty(), 1, 2, true));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FileSelectionSearch(Optional.empty(), 1, 2, true));
    assertFalse(new ArchiveSearchResult(Optional.empty(), 1, 2, false).complete());
    assertTrue(new FileSelectionSearch(Optional.empty(), 2, 2, true).complete());
  }

  @Test
  void independentBudgetDimensionsRejectInvalidAndUnboundedDuration() {
    var value = SelectedFileBudgets.defaults();
    assertThrows(
        IllegalArgumentException.class,
        () -> new SelectedFileBudgets(value.lookup(), 1, 0, 1, 1, Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SelectedFileBudgets(value.lookup(), 1, 1, 1, 1, Duration.ofHours(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ArchiveSearchBudgets(1, 1, 1, Duration.ofSeconds(1), 0));
    assertTrue(ArchiveSearchBudgets.defaults().maximumArchiveBytes() > Integer.MAX_VALUE);
    assertThrows(IllegalArgumentException.class, () -> new AcquisitionCost(-1, 0));
  }

  @Test
  void resultChoicesCannotBeEmptyOrAmbiguous() {
    assertThrows(
        IllegalArgumentException.class, () -> new LooseProbe(Optional.empty(), Optional.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FileSelectionProbe(Optional.empty(), Optional.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FileLocator(
                FileLocator.Kind.LOOSE,
                "https://us.cdn.blizzard.com/x",
                "a".repeat(32),
                10,
                1,
                9,
                Optional.empty()));
  }
}
