package io.vibeminer.desktop.itemexport;

import java.util.Optional;
import javafx.stage.Window;

/** Native composition boundary; the exact chosen pathname is not normalized by the desktop. */
@FunctionalInterface
public interface ItemEvidenceDestinationChooser {
  Optional<String> choose(Window owner, String suggestedFileName);
}
