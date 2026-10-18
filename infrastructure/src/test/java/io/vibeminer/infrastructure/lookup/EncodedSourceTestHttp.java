package io.vibeminer.infrastructure.lookup;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.List;

/** Synthetic in-process HTTP protocol fixture bridge; never contacts a real CDN. */
public final class EncodedSourceTestHttp {
  private final SelectedFileHttpFixture fixture;

  public EncodedSourceTestHttp() throws Exception {
    fixture = new SelectedFileHttpFixture();
  }

  public HttpClient client() {
    return fixture.client();
  }

  public void object(String key, byte[] bytes) {
    fixture.objects.put(key, bytes);
  }

  public List<HttpRequest> requests() {
    return List.copyOf(fixture.requests);
  }

  public void observe(java.util.function.Consumer<HttpRequest> observer) { fixture.requestObserver=observer; }

  public void forbidNetwork() {
    fixture.rejectNetwork = true;
  }
}
