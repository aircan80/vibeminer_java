package io.vibeminer.infrastructure.lookup;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.protocol.tact.archive.ArchiveIndexParser;
import java.net.URI;
import java.net.http.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SelectedFileHttpFixtureTest {
  @Test
  void actualPinnedConfigurationTransportAcceptsBothVerifiedConfigurationRoutes() throws Exception {
    var f = new SelectedFileHttpFixture();
    var source =
        new io.vibeminer.infrastructure.acquisition.CdnRequest.Source(
            SelectedFileHttpFixture.HOST,
            "tpr/wow",
            "tpr/configs/data",
            java.util.List.of(SelectedFileHttpFixture.HOST));
    var transport =
        new io.vibeminer.infrastructure.acquisition.PinnedCdnTransport(
            f.client(),
            java.time.Clock.fixed(RootLookupFixture.NOW, java.time.ZoneOffset.UTC),
            java.time.Duration.ofSeconds(5));
    var build = f.build();
    assertArrayEquals(
        f.config,
        transport
            .fetch(
                io.vibeminer.infrastructure.acquisition.CdnRequest.configuration(
                    source, build.buildConfig()))
            .join()
            .bytes());
    assertArrayEquals(
        f.cdnConfig,
        transport
            .fetch(
                io.vibeminer.infrastructure.acquisition.CdnRequest.configuration(
                    source, build.cdnConfig()))
            .join()
            .bytes());
    assertEquals(2, f.requests.size());
    assertTrue(
        f.requests.stream()
            .allMatch(request -> request.uri().getPath().startsWith("/tpr/wow/config/")));
  }

  @Test
  void strictRangeHeadAndSuffixRepresentSameSyntheticBytes() throws Exception {
    var f = new SelectedFileHttpFixture();
    String path =
        "https://"
            + SelectedFileHttpFixture.HOST
            + "/tpr/wow/data/"
            + f.selectedEKey.substring(0, 2)
            + "/"
            + f.selectedEKey.substring(2, 4)
            + "/"
            + f.selectedEKey;
    var head =
        f.client()
            .sendAsync(
                HttpRequest.newBuilder(URI.create(path))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray())
            .join();
    assertEquals(200, head.statusCode());
    assertEquals(
        f.selected.length, head.headers().firstValueAsLong("Content-Length").orElseThrow());
    assertEquals(0, head.body().length);
    var range =
        f.client()
            .sendAsync(
                HttpRequest.newBuilder(URI.create(path)).header("Range", "bytes=3-7").build(),
                HttpResponse.BodyHandlers.ofByteArray())
            .join();
    assertEquals(206, range.statusCode());
    assertArrayEquals(Arrays.copyOfRange(f.selected, 3, 8), range.body());
    var suffix =
        f.client()
            .sendAsync(
                HttpRequest.newBuilder(URI.create(path)).header("Range", "bytes=-3").build(),
                HttpResponse.BodyHandlers.ofByteArray())
            .join();
    assertArrayEquals(
        Arrays.copyOfRange(f.selected, f.selected.length - 3, f.selected.length), suffix.body());
    assertEquals(3, f.requests.size());
    assertTrue(f.demand.get() > 0);
    assertThrows(
        AssertionError.class,
        () ->
            f.client()
                .sendAsync(
                    HttpRequest.newBuilder(URI.create("https://example.com/unknown")).build(),
                    HttpResponse.BodyHandlers.ofByteArray()));
  }

  @Test
  void syntheticIndexAuthenticatesAndResolvesExactChildOffset() throws Exception {
    var f = new SelectedFileHttpFixture();
    var parser = new ArchiveIndexParser();
    var footer =
        parser.authenticateFooter(
            Arrays.copyOfRange(f.archiveIndex, f.archiveIndex.length - 28, f.archiveIndex.length),
            f.archiveKey,
            f.archiveIndex.length,
            new ArchiveIndexParser.Limits(10000, 100, 1000000),
            () -> false);
    var toc =
        parser.authenticateToc(footer, Arrays.copyOfRange(f.archiveIndex, 4096, 4120), () -> false);
    var page = toc.pageFor(f.selectedEKey).orElseThrow();
    var result =
        parser
            .lookup(toc, page, Arrays.copyOf(f.archiveIndex, 4096), f.selectedEKey, () -> false)
            .orElseThrow();
    assertEquals(f.archiveOffset, result.archiveOffset());
    assertEquals(f.selected.length, result.encodedSize());
    assertArrayEquals(
        f.selected,
        Arrays.copyOfRange(f.archive, f.archiveOffset, f.archiveOffset + f.selected.length));
    assertEquals(RootLookupFixture.hash("MD5", f.config), f.build().buildConfig());
    assertEquals(RootLookupFixture.hash("MD5", f.cdnConfig), f.build().cdnConfig());
  }
}
