package io.vibeminer.application.selectedfile;

import io.vibeminer.domain.selectedfile.ContentRepresentation;

/** Persist verified prospective SHA/receipt at EOF before physical CAS promotion. */
@FunctionalInterface
public interface EncodedPublicationObserver {
  void verifiedBeforePromotion(ContentRepresentation prospective);
}
