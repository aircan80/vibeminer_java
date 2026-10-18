import io.vibeminer.application.DiscoveryService;
import io.vibeminer.application.FixtureDiscovery;
import io.vibeminer.desktop.AtlasApplication;
import io.vibeminer.domain.BuildSnapshot;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.animation.PauseTransition;
import javafx.application.*;
import javafx.scene.image.*;
import javafx.stage.*;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/**
 * Authored discovery only; validates packaged branding and captures only this isolated app scene.
 */
public final class CaptureBranding {
  private static Path output;
  private static volatile boolean complete;

  public static void main(String[] args) throws Exception {
    if (args.length != 4
        || !args[0].equals("--isolated-display")
        || !args[1].equals(System.getenv("DISPLAY"))
        || !args[2].equals("--output"))
      throw new IllegalArgumentException(
          "Usage: CaptureBranding --isolated-display :N --output DIRECTORY");
    output = Path.of(args[3]);
    Files.createDirectories(output);
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, failure) -> {
          failure.printStackTrace();
          System.exit(1);
        });
    AtlasApplication.configure(
        new DiscoveryService() {
          public List<BuildSnapshot> fixtureBuilds() {
            return FixtureDiscovery.builds();
          }

          public CompletionStage<List<BuildSnapshot>> refresh(String product, String region) {
            throw new AssertionError("Branding review cannot request discovery");
          }
        },
        true);
    var deadline =
        new Thread(
            () -> {
              try {
                Thread.sleep(30000);
              } catch (InterruptedException stopped) {
                return;
              }
              if (!complete) {
                System.err.println("Branding capture timed out");
                System.exit(2);
              }
            });
    deadline.setDaemon(true);
    deadline.start();
    var controller =
        new Thread(
            () -> {
              for (int i = 0; i < 100; i++) {
                try {
                  Platform.runLater(CaptureBranding::findStage);
                  return;
                } catch (IllegalStateException pending) {
                  try {
                    Thread.sleep(50);
                  } catch (InterruptedException stopped) {
                    return;
                  }
                }
              }
            });
    controller.setDaemon(true);
    controller.start();
    Application.launch(AtlasApplication.class);
    if (!complete) throw new IllegalStateException("Branding review did not complete");
    System.out.println(
        "Packaged Excavate branding: 9 window icons, shared 32px header, 2 scene captures OK");
  }

  private static void findStage() {
    var stage =
        Window.getWindows().stream()
            .filter(w -> w instanceof Stage && w.isShowing())
            .map(w -> (Stage) w)
            .findFirst();
    if (stage.isEmpty()) {
      later(CaptureBranding::findStage);
      return;
    }
    var window = stage.orElseThrow();
    int[] sizes = {16, 24, 32, 48, 64, 128, 256, 512, 1024};
    if (window.getIcons().size() != sizes.length)
      throw new AssertionError("Incomplete window icon set");
    for (int i = 0; i < sizes.length; i++) {
      var icon = window.getIcons().get(i);
      if (icon.isError() || icon.getWidth() != sizes[i] || icon.getHeight() != sizes[i])
        throw new AssertionError("Wrong packaged window icon " + sizes[i]);
    }
    var mark = (ImageView) window.getScene().lookup("#vibeminer-brand-icon");
    if (mark == null
        || mark.getImage() != window.getIcons().get(2)
        || mark.getFitWidth() != 32
        || mark.getFitHeight() != 32
        || !mark.isPreserveRatio())
      throw new AssertionError("Header must share the loaded 32px Excavate icon");
    window.setWidth(1440);
    window.setHeight(1000);
    later(
        () -> {
          capture(window, "excavate-atlas-1440x1000.png");
          window.setWidth(1280);
          window.setHeight(800);
          later(
              () -> {
                capture(window, "excavate-atlas-1280x800.png");
                complete = true;
                window.close();
                Platform.exit();
              });
        });
  }

  private static void capture(Stage stage, String filename) {
    try {
      stage.getScene().getRoot().applyCss();
      stage.getScene().getRoot().layout();
      WritableImage fx = stage.getScene().snapshot(null);
      var image =
          new BufferedImage((int) fx.getWidth(), (int) fx.getHeight(), BufferedImage.TYPE_INT_ARGB);
      int[] pixels = new int[image.getWidth() * image.getHeight()];
      fx.getPixelReader()
          .getPixels(
              0,
              0,
              image.getWidth(),
              image.getHeight(),
              PixelFormat.getIntArgbInstance(),
              pixels,
              0,
              image.getWidth());
      image.setRGB(0, 0, image.getWidth(), image.getHeight(), pixels, 0, image.getWidth());
      ImageIO.write(image, "png", output.resolve(filename).toFile());
    } catch (Exception failure) {
      throw new RuntimeException(failure);
    }
  }

  private static void later(Runnable action) {
    var pause = new PauseTransition(Duration.millis(250));
    pause.setOnFinished(event -> action.run());
    pause.play();
  }
}
