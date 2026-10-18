package io.vibeminer.infrastructure.bootstrap;

import io.vibeminer.application.bootstrap.BootstrapException;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.infrastructure.acquisition.CapturedBuildSource;
import io.vibeminer.infrastructure.acquisition.CdnRequest;
import io.vibeminer.infrastructure.acquisition.SourceContextException;

/** Install-scope error translation over the shared captured official-source policy. */
final class PinnedBuildSource {
    static CdnRequest.Source resolve(BuildSnapshot build) {
        try {
            return CapturedBuildSource.resolve(build);
        } catch (SourceContextException rejected) {
            throw translate(rejected);
        }
    }

    static void validateConfigurationProof(String url, String key) {
        try {
            CapturedBuildSource.validateConfigurationProof(url, key);
        } catch (SourceContextException rejected) {
            throw translate(rejected);
        }
    }

    private static BootstrapException translate(SourceContextException rejected) {
        return new BootstrapException(
                BootstrapException.Reason.valueOf(rejected.reason().name()),
                rejected.getMessage(),
                rejected);
    }
}
