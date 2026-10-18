package io.vibeminer.desktop;

import static org.junit.jupiter.api.Assertions.*;

import java.io.DataInputStream;
import org.junit.jupiter.api.Test;

class BrandingResourcesTest {
  @Test
  void everyRuntimeIconIsPackagedAsAnExactSizePng() throws Exception {
    for (int size : new int[] {16, 24, 32, 48, 64, 128, 256, 512, 1024}) {
      String path = "/io/vibeminer/desktop/icons/vibeminer-" + size + ".png";
      try (var resource = AtlasApplication.class.getResourceAsStream(path)) {
        assertNotNull(resource, path);
        var input = new DataInputStream(resource);
        assertEquals(0x89504e470d0a1a0aL, input.readLong(), path);
        assertEquals(13, input.readInt(), path);
        assertEquals(0x49484452, input.readInt(), path);
        assertEquals(size, input.readInt(), path);
        assertEquals(size, input.readInt(), path);
      }
    }
  }
}
