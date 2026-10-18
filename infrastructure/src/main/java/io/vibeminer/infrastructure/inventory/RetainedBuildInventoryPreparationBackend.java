package io.vibeminer.infrastructure.inventory;

import static io.vibeminer.application.inventory.BuildInventoryPreparationException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.buildopening.*;
import io.vibeminer.application.buildretention.*;
import io.vibeminer.application.catalog.CatalogException;
import io.vibeminer.application.content.ContentClaimException;
import io.vibeminer.application.inventory.*;
import io.vibeminer.format.wow.root.StreamingRootException;
import io.vibeminer.infrastructure.acquisition.SourceContextException;
import io.vibeminer.infrastructure.buildopening.*;
import io.vibeminer.infrastructure.encoding.*;
import io.vibeminer.protocol.tact.blte.*;
import io.vibeminer.protocol.tact.download.DownloadInventoryException;
import io.vibeminer.protocol.tact.downloadsize.DownloadSizeInventoryException;
import io.vibeminer.protocol.tact.encoding.FullEncodingInventoryException;
import io.vibeminer.protocol.tact.install.InstallManifestException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Blocking app-owned source replay, complete inventory preparation and actual sealed issuance. */
public final class RetainedBuildInventoryPreparationBackend
    implements BuildInventoryPreparationBackend {
  private static final int MAXIMUM_ATTEMPTS = 3;
  private final BuildMetadataAdmissionRepository admissions;
  private final SqliteBuildInventory inventory;
  private final SqliteBuildRetentionInventory authority;
  private final MetadataStreamBudgets budgets;
  private final SqliteEncodingLookupIndex.Limits encodingLimits;
  private final SqliteBuildInventory.Limits inventoryLimits;

  public RetainedBuildInventoryPreparationBackend(
      BuildMetadataAdmissionRepository admissions,
      SqliteBuildInventory inventory,
      SqliteBuildRetentionInventory authority,
      MetadataStreamBudgets budgets,
      SqliteEncodingLookupIndex.Limits encodingLimits,
      SqliteBuildInventory.Limits inventoryLimits) {
    this.admissions = Objects.requireNonNull(admissions);
    this.inventory = Objects.requireNonNull(inventory);
    this.authority = Objects.requireNonNull(authority);
    this.budgets = Objects.requireNonNull(budgets);
    this.encodingLimits = Objects.requireNonNull(encodingLimits);
    this.inventoryLimits = Objects.requireNonNull(inventoryLimits);
  }

  @Override
  public BuildInventoryPreparationResult prepare(
      BuildMetadataAdmission admission,
      Consumer<BuildInventoryPreparationProgress> progress,
      CancellationToken token) {
    Objects.requireNonNull(progress);
    Objects.requireNonNull(token);
    if (admission == null)
      throw new BuildInventoryPreparationException(
          INVALID_REQUEST, "Exact metadata admission required");
    try {
      stage(progress, BuildInventoryPreparationProgress.Stage.REVALIDATING_ADMISSION, token);
      stage(progress, BuildInventoryPreparationProgress.Stage.VERIFYING_SOURCES, token);
      var actual =
          admissions
              .find(admission.reference(), admission.locale(), admission.scopeRevision(), token)
              .orElseThrow(
                  () ->
                      new BuildInventoryPreparationException(
                          NOT_READY, "Complete retained metadata admission is missing"));
      if (!actual.admission().equals(admission))
        throw new BuildInventoryPreparationException(
            STALE_CONTEXT, "Provided metadata admission differs from current original facts");
      stage(progress, BuildInventoryPreparationProgress.Stage.PREPARING_INVENTORY, token);
      var prepared =
          inventory.prepare(
              admission.reference(),
              admission.locale(),
              admission.scopeRevision(),
              budgets,
              encodingLimits,
              inventoryLimits,
              token);
      stage(progress, BuildInventoryPreparationProgress.Stage.ISSUING_TARGET, token);
      var target =
          authority.issueTarget(
              admission.reference(),
              admission.locale(),
              admission.scopeRevision(),
              prepared,
              token);
      var policy = policy(target, prepared, token);
      // Resource cleanup above has finished. The service owns terminal progress and late-cancel
      // policy.
      return new BuildInventoryPreparationResult(admission, target, Optional.of(policy));
    } catch (CancellationException cancelled) {
      throw cancelled;
    } catch (BuildInventoryPreparationException typed) {
      throw typed;
    } catch (IOException | RuntimeException failure) {
      throw translate(failure);
    }
  }

  private BuildRetention.Policy policy(
      BuildRetention.Target target,
      SqliteBuildInventory.Prepared prepared,
      CancellationToken token) {
    try (var opened = authority.openSealed(target, token)) {
      var seal = opened.seal();
      if (!seal.target().equals(target) || seal.memberCount() != prepared.uniqueContentTargets())
        throw new BuildInventoryPreparationException(
            CORRUPT_INVENTORY, "Issued seal differs from completed inventory");
      if (seal.memberCount() > inventoryLimits.maximumReferences())
        throw new BuildInventoryPreparationException(
            LIMIT_EXCEEDED, "Offered policy exceeds inventory member bound");
      long maximumObject = 1, wire = 0, additional = 0, requests = 0;
      for (long first = 0; first < seal.memberCount(); ) {
        token.throwIfCancellationRequested();
        int count = (int) Math.min(128, seal.memberCount() - first);
        var members = opened.members(first, count, token);
        if (members.size() != count)
          throw new BuildInventoryPreparationException(
              CORRUPT_INVENTORY, "Policy enumeration omitted inventory members");
        for (var member : members) {
          token.throwIfCancellationRequested();
          if (member.ordinal() != first)
            throw new BuildInventoryPreparationException(
                CORRUPT_INVENTORY, "Policy inventory ordinal changed");
          maximumObject = Math.max(maximumObject, member.encodedBytes());
          wire = Math.addExact(wire, member.cost().wireBytes());
          additional = Math.addExact(additional, member.cost().additionalBytes());
          requests = Math.addExact(requests, member.cost().requests());
          first = Math.addExact(first, 1);
        }
      }
      return new BuildRetention.Policy(
          maximumObject,
          Math.multiplyExact(wire, MAXIMUM_ATTEMPTS),
          Math.multiplyExact(additional, MAXIMUM_ATTEMPTS),
          Math.multiplyExact(requests, MAXIMUM_ATTEMPTS),
          MAXIMUM_ATTEMPTS);
    }
  }

  private static void stage(
      Consumer<BuildInventoryPreparationProgress> observer,
      BuildInventoryPreparationProgress.Stage stage,
      CancellationToken token) {
    token.throwIfCancellationRequested();
    // Origin-reference counts are not exposed by this parser adapter. Unique CKeys are not that
    // unit.
    observer.accept(
        new BuildInventoryPreparationProgress(
            stage, OptionalLong.empty(), OptionalLong.empty(), Optional.empty()));
    token.throwIfCancellationRequested();
  }

  private static BuildInventoryPreparationException translate(Throwable primary) {
    var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    for (Throwable cause = primary;
        cause != null && seen.size() < 32 && seen.add(cause);
        cause = cause.getCause()) {
      if (cause instanceof CancellationException cancelled) throw cancelled;
      var code = typedCode(cause);
      if (code != null)
        return new BuildInventoryPreparationException(
            code, "Retained inventory preparation refused", primary);
    }
    return new BuildInventoryPreparationException(
        primary instanceof IOException || hasSqlCause(primary)
            ? STORAGE_UNAVAILABLE
            : CORRUPT_INVENTORY,
        "Retained inventory preparation could not complete",
        primary);
  }

  private static BuildInventoryPreparationException.Code typedCode(Throwable cause) {
    if (cause instanceof ObjectStoreException e)
      return switch (e.code()) {
        case MISSING_OBJECT -> NOT_READY;
        case INTEGRITY_FAILURE -> CORRUPT_INVENTORY;
        case LIMIT_EXCEEDED -> LIMIT_EXCEEDED;
        case BUSY, STORE_IN_USE -> BUSY;
        case IO_FAILURE, CLOSED -> STORAGE_UNAVAILABLE;
      };
    if (cause instanceof BuildOpeningException e)
      return switch (e.code()) {
        case INVALID_REQUEST -> INVALID_REQUEST;
        case NOT_READY, MISSING_METADATA, UNAVAILABLE -> NOT_READY;
        case CORRUPT_METADATA -> CORRUPT_INVENTORY;
        case UNSUPPORTED_METADATA -> UNSUPPORTED;
        case LIMIT_EXCEEDED -> LIMIT_EXCEEDED;
        case BUSY -> BUSY;
        case CLOSED, STORAGE_UNAVAILABLE -> STORAGE_UNAVAILABLE;
      };
    if (cause instanceof BuildRetentionException e)
      return switch (e.code()) {
        case INVALID_REQUEST -> INVALID_REQUEST;
        case NOT_FOUND, NOT_READY -> NOT_READY;
        case STALE_GENERATION -> STALE_CONTEXT;
        case BUSY -> BUSY;
        case LIMIT_EXCEEDED, QUOTA_EXCEEDED -> LIMIT_EXCEEDED;
        case CORRUPT_STATE -> CORRUPT_INVENTORY;
        case STORAGE_UNAVAILABLE -> STORAGE_UNAVAILABLE;
      };
    if (cause instanceof ContentClaimException e)
      return switch (e.code()) {
        case INVALID_REQUEST -> INVALID_REQUEST;
        case BUSY -> BUSY;
        case REPLAN_REQUIRED, LEGACY_CONFLICT, RECOVERY_REQUIRED -> STALE_CONTEXT;
        case UNSUPPORTED_REUSE -> UNSUPPORTED;
        case MISSING_LOCAL -> NOT_READY;
        case INTEGRITY_FAILURE, INVALID_STATE -> CORRUPT_INVENTORY;
        case STORAGE_UNAVAILABLE -> STORAGE_UNAVAILABLE;
      };
    if (cause instanceof CatalogException e)
      return switch (e.code()) {
        case MISSING_OBJECT, UNKNOWN_SNAPSHOT -> NOT_READY;
        case CORRUPT_METADATA, IMMUTABLE_CONFLICT, INTEGRITY_FAILURE, INVALID_EVIDENCE ->
            CORRUPT_INVENTORY;
        case STORAGE_UNAVAILABLE -> STORAGE_UNAVAILABLE;
      };
    if (cause instanceof InventoryException) return UNSUPPORTED;
    if (cause instanceof EncodingLookupException || cause instanceof ArithmeticException)
      return LIMIT_EXCEEDED;
    if (cause instanceof SourceContextException e)
      return e.reason() == SourceContextException.Reason.UNSUPPORTED
          ? UNSUPPORTED
          : CORRUPT_INVENTORY;
    if (cause instanceof StreamingRootException e) return parserCode(e.reason().name(), cause);
    if (cause instanceof InstallManifestException e) return parserCode(e.reason().name(), cause);
    if (cause instanceof DownloadInventoryException e) return parserCode(e.reason().name(), cause);
    if (cause instanceof DownloadSizeInventoryException e)
      return parserCode(e.reason().name(), cause);
    if (cause instanceof FullEncodingInventoryException e)
      return parserCode(e.reason().name(), cause);
    if (cause instanceof StreamingDecodedBlteException e)
      return parserCode(e.reason().name(), cause);
    if (cause instanceof EncodedBlteException e) return parserCode(e.reason().name(), cause);
    // Older staging adapters predate typed refusal codes. Match only their exact fixed bounds,
    // never arbitrary user text or a generic substring; retain the original cause for diagnosis.
    if ((cause instanceof IllegalStateException
            || cause instanceof IllegalArgumentException
            || cause instanceof IOException)
        && cause.getMessage() != null
        && LEGACY_LIMITS.contains(cause.getMessage())) return LIMIT_EXCEEDED;
    if (cause instanceof IllegalStateException
        && cause.getMessage() != null
        && UNRESOLVED.contains(cause.getMessage())) return NOT_READY;
    return null;
  }

  private static BuildInventoryPreparationException.Code parserCode(
      String reason, Throwable cause) {
    return switch (reason) {
      case "UNSUPPORTED" -> UNSUPPORTED;
      case "LIMIT" -> LIMIT_EXCEEDED;
      case "CANCELLED" -> {
        var cancelled = new CancellationException("Local inventory verification cancelled");
        cancelled.initCause(cause);
        throw cancelled;
      }
      case "IO", "CLOSED" -> STORAGE_UNAVAILABLE;
      default -> CORRUPT_INVENTORY;
    };
  }

  private static boolean hasSqlCause(Throwable failure) {
    for (int n = 0; failure != null && n < 32; n++, failure = failure.getCause())
      if (failure instanceof SQLException) return true;
    return false;
  }

  private static final Set<String> LEGACY_LIMITS =
      Set.of(
          "Inventory generation quota exceeded",
          "Inventory derived quota exceeded",
          "Table-global inventory quota exceeded",
          "Inventory batch limit",
          "Aggregate inventory replay work exceeds budget",
          "IN source exceeds admitted materialization limit",
          "EN index replay exceeds admitted work",
          "EN row bound exceeded",
          "EN candidate bound exceeded",
          "EN logical derived byte bound exceeded",
          "EN batch bound exceeded",
          "EN query key bound exceeded",
          "EN query candidate bound exceeded");
  private static final Set<String> UNRESOLVED =
      Set.of(
          "Unresolved inventory expectation: INCOMPLETE_ENCODING_FACTS",
              "Unresolved inventory expectation: MISSING_MAPPING",
          "Unresolved inventory expectation: AMBIGUOUS_MAPPING",
              "Unresolved inventory expectation: UNKNOWN_SIZE");
}
