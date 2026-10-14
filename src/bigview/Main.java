package bigview;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.nio.file.Path;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // fall back to the default look and feel
            }
            ViewerFrame frame = new ViewerFrame();
            frame.setVisible(true);
            if (args.length > 0) {
                frame.openFile(Path.of(args[0]), -1);
            }
        });
    }
}
