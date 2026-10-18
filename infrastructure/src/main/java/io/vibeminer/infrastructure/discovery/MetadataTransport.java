package io.vibeminer.infrastructure.discovery;

import java.net.URI;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
interface MetadataTransport {
    CompletionStage<CapturedMetadata> fetch(URI uri);
}
