package io.vibeminer.application.content;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.domain.content.MetadataContentProof;

/** Verifier-issued facts at physical-publication and supported-parser EOF boundaries. */
public interface MetadataPublicationObserver {
  void beforePromotion(MetadataContentProof prospective, CancellationToken cancellation);

  void afterStructureValidation(MetadataContentProof complete, CancellationToken cancellation);

  static MetadataPublicationObserver none() {
    return new MetadataPublicationObserver() {
      @Override
      public void beforePromotion(MetadataContentProof proof, CancellationToken token) {}

      @Override
      public void afterStructureValidation(MetadataContentProof proof, CancellationToken token) {}
    };
  }
}
