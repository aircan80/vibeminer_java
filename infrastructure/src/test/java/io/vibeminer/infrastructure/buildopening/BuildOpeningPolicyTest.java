package io.vibeminer.infrastructure.buildopening;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.buildopening.BuildOpeningException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BuildOpeningPolicyTest {
  @Test
  void aggregateEqualFitsAndOneMoreOfEachIndependentUnitRefuses() {
    var p = BuildOpeningPolicy.initialCohort();
    var ledger = new OfficialBuildOpeningGateway.Ledger(p, CancellationToken.none(), () -> 0);
    ledger.reserve(
        p.maximumWireBytes(),
        p.maximumAdditionalPhysicalBytes(),
        p.maximumRequests(),
        p.maximumDecodedWorkBytes(),
        p.maximumGrammarWorkBytes());
    for (long[] extra :
        new long[][] {
          {1, 0, 0, 0, 0}, {0, 1, 0, 0, 0}, {0, 0, 1, 0, 0}, {0, 0, 0, 1, 0}, {0, 0, 0, 0, 1}
        })
      assertEquals(
          BuildOpeningException.Code.LIMIT_EXCEEDED,
          assertThrows(
                  BuildOpeningException.class,
                  () -> ledger.reserve(extra[0], extra[1], extra[2], extra[3], extra[4]))
              .code());
  }

  @Test
  void conservativeReplayCostIsFiniteAndMonotonicDeadlineCannotWrap() {
    var p = BuildOpeningPolicy.initialCohort();
    var time = new AtomicLong(Long.MAX_VALUE - 100);
    var ledger = new OfficialBuildOpeningGateway.Ledger(p, CancellationToken.none(), time::get);
    ledger.reserve(100, 100, 1, 200, 200);
    ledger.reserve(100, 100, 1, 200, 200);
    assertEquals(200, ledger.wire);
    time.set(Long.MAX_VALUE - 100 + p.timeout().toNanos());
    assertEquals(
        BuildOpeningException.Code.LIMIT_EXCEEDED,
        assertThrows(BuildOpeningException.class, ledger::check).code());
  }
}
