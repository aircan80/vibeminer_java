package io.vibeminer.application.bootstrap;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.domain.ResolvedSourceIdentity;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.VerifiedSourceAlias;

/** Blocking outbound port. Adapter owns bounded source interpretation, protocol verification and byte mechanics. */
public interface BootstrapArtifactGateway {
    InstallPlan inspectConfiguration(BuildSnapshot build, CancellationToken cancellation);
    void validatePlan(InstallPlan plan, CancellationToken cancellation);
    VerifiedSourceAlias retainConfiguration(InstallPlan plan, CancellationToken cancellation);
    InstallPlan restoreConfiguration(BuildSnapshot build, VerifiedSourceAlias configuration, CancellationToken cancellation);
    ResolvedSourceIdentity configurationIdentity(BuildSnapshot build);
    ResolvedSourceIdentity installIdentity(InstallPlan plan);
    VerifiedSourceAlias acquireInstall(InstallPlan plan, CancellationToken cancellation);
    InstallInspection inspectRetained(InstallPlan plan, VerifiedSourceAlias artifact, CancellationToken cancellation);
}
