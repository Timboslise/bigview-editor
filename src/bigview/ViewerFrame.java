package bigview;

import javax.swing.AbstractAction;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollBar;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.border.EmptyBorder;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import java.awt.Adjustable;
import java.awt.BorderLayout;
import java.awt.FileDialog;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class ViewerFrame extends JFrame {

    private static final int CTRL = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    private static final String STATUS_GAP = "     ";

    private final TextView view = new TextView();
    private final JScrollBar vbar = new JScrollBar(Adjustable.VERTICAL);
    private final JScrollBar hbar = new JScrollBar(Adjustable.HORIZONTAL);
    private final JLabel statusLeft = new JLabel(" ");
    private final JLabel statusRight = new JLabel(" ");

    private FileText text;
    private Charset charset = StandardCharsets.UTF_8;
    private boolean syncing;
    private long vScale = 1;
    private long lastLineCount = -1;

    /** Line to scroll to once the indexer has reached it (go-to-line or reload while indexing). */
    private long pendingLine = -1;
    private boolean pendingCenter;

    private String lastQuery = "";
    private boolean lastMatchCase;
    private long lastMatchOffset = -1;
    private volatile boolean searchCancel;
    private volatile int searchPercent;
    private boolean searching;
    /** One-off message shown in the status bar instead of the file path. */
    private String notice;

    ViewerFrame() {
        super("BigView");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                closeText();
                System.exit(0);
            }
        });

        JPanel south = new JPanel(new BorderLayout());
        south.add(hbar, BorderLayout.CENTER);
        south.add(Box.createHorizontalStrut(vbar.getPreferredSize().width), BorderLayout.EAST);
        JPanel center = new JPanel(new BorderLayout());
        center.add(view, BorderLayout.CENTER);
        center.add(vbar, BorderLayout.EAST);
        center.add(south, BorderLayout.SOUTH);

        JPanel status = new JPanel(new BorderLayout(16, 0));
        status.setBorder(new EmptyBorder(3, 8, 3, 8));
        status.add(statusLeft, BorderLayout.CENTER);
        status.add(statusRight, BorderLayout.EAST);

        getContentPane().add(center, BorderLayout.CENTER);
        getContentPane().add(status, BorderLayout.SOUTH);
        setJMenuBar(buildMenu());

        view.setOnChange(this::syncScrollbars);
        vbar.addAdjustmentListener(e -> {
            if (!syncing) {
                int v = vbar.getValue();
                view.setTopLine(v >= vbar.getMaximum() - vbar.getVisibleAmount() ? view.maxTop() : v * vScale);
            }
        });
        hbar.addAdjustmentListener(e -> {
            if (!syncing) {
                view.setLeftCol(hbar.getValue());
            }
        });

        TransferHandler dnd = new FileDropHandler();
        setTransferHandler(dnd);
        view.setTransferHandler(dnd);

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancelSearch");
        getRootPane().getActionMap().put("cancelSearch", action(this::cancelSearch));

        new Timer(150, e -> poll()).start();

        setSize(1150, 780);
        setLocationRelativeTo(null);
        updateStatus();
    }

    // ---- menu -----------------------------------------------------------------------------

    private JMenuBar buildMenu() {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        file.setMnemonic('F');
        file.add(item("Open...", KeyEvent.VK_O, CTRL, this::chooseFile));
        file.add(item("Reload", KeyEvent.VK_F5, 0, this::reload));
        file.addSeparator();
        file.add(item("Exit", KeyEvent.VK_F4, InputEvent.ALT_DOWN_MASK, this::dispose));
        bar.add(file);

        JMenu edit = new JMenu("Edit");
        edit.setMnemonic('E');
        edit.add(item("Copy", KeyEvent.VK_C, CTRL, this::copy));
        edit.add(item("Select All", KeyEvent.VK_A, CTRL, view::selectAll));
        bar.add(edit);

        JMenu search = new JMenu("Search");
        search.setMnemonic('S');
        search.add(item("Find...", KeyEvent.VK_F, CTRL, this::showFind));
        search.add(item("Find Next", KeyEvent.VK_F3, 0, this::findNext));
        search.add(item("Go to Line...", KeyEvent.VK_G, CTRL, this::showGoTo));
        bar.add(search);

        JMenu viewMenu = new JMenu("View");
        viewMenu.setMnemonic('V');
        viewMenu.add(item("Zoom In", KeyEvent.VK_EQUALS, CTRL, () -> view.setFontSize(view.fontSize() + 1)));
        viewMenu.add(item("Zoom Out", KeyEvent.VK_MINUS, CTRL, () -> view.setFontSize(view.fontSize() - 1)));
        viewMenu.add(item("Reset Zoom", KeyEvent.VK_0, CTRL, () -> view.setFontSize(14)));
        viewMenu.addSeparator();
        JMenu encoding = new JMenu("Encoding");
        ButtonGroup group = new ButtonGroup();
        for (Charset cs : List.of(StandardCharsets.UTF_8, Charset.forName("windows-1252"))) {
            JRadioButtonMenuItem mi = new JRadioButtonMenuItem(cs.displayName(), cs.equals(charset));
            mi.addActionListener(e -> setCharset(cs));
            group.add(mi);
            encoding.add(mi);
        }
        viewMenu.add(encoding);
        bar.add(viewMenu);
        return bar;
    }

    private JMenuItem item(String label, int key, int mods, Runnable r) {
        JMenuItem mi = new JMenuItem(action(r));
        mi.setText(label);
        mi.setAccelerator(KeyStroke.getKeyStroke(key, mods));
        return mi;
    }

    private static AbstractAction action(Runnable r) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        };
    }

    // ---- files ----------------------------------------------------------------------------

    private void chooseFile() {
        FileDialog fd = new FileDialog(this, "Open", FileDialog.LOAD);
        if (text != null) {
            fd.setDirectory(text.path.toAbsolutePath().getParent().toString());
        }
        fd.setVisible(true);
        if (fd.getFile() != null) {
            openFile(Path.of(fd.getDirectory(), fd.getFile()), -1);
        }
    }

    private void reload() {
        if (text != null) {
            openFile(text.path, view.topLine());
        }
    }

    /** Opens {@code path}; if {@code restoreTop >= 0} that line is scrolled to the top once indexed. */
    void openFile(Path path, long restoreTop) {
        if (!Files.isRegularFile(path)) {
            error("Cannot open " + path + ": not a file.");
            return;
        }
        FileText opened;
        try {
            opened = new FileText(path);
        } catch (IOException e) {
            error("Cannot open " + path + ":\n" + e.getMessage());
            return;
        }
        closeText();
        text = opened;
        text.setCharset(charset);
        lastMatchOffset = -1;
        lastLineCount = -1;
        notice = null;
        pendingLine = restoreTop > 0 ? restoreTop : -1;
        pendingCenter = false;
        view.setText(text);
        setTitle(path.getFileName() + " - BigView");
        view.requestFocusInWindow();
        updateStatus();
    }

    private void closeText() {
        cancelSearch();
        if (text != null) {
            try {
                text.close();
            } catch (IOException ignored) {
                // nothing useful to do when closing a read-only file fails
            }
            text = null;
        }
    }

    private void setCharset(Charset cs) {
        charset = cs;
        if (text != null) {
            text.setCharset(cs);
            lastMatchOffset = -1;
            view.invalidateCache();
        }
        updateStatus();
    }

    // ---- edit -----------------------------------------------------------------------------

    private void copy() {
        try {
            String s = view.selectedText();
            if (s != null) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
            }
        } catch (IllegalStateException e) {
            error(e.getMessage());
        }
    }

    // ---- go to line -----------------------------------------------------------------------

    private void showGoTo() {
        if (text == null) {
            return;
        }
        String range = text.index.isDone() ? "1 - " + Format.number(view.lineCount()) : "1 - " + Format.number(view.lineCount()) + "+ (still indexing)";
        String input = JOptionPane.showInputDialog(this, "Line number (" + range + "):", "Go to Line", JOptionPane.PLAIN_MESSAGE);
        if (input == null) {
            return;
        }
        long line;
        try {
            line = Long.parseLong(input.replaceAll("[\\s,._]", "")) - 1;
        } catch (NumberFormatException e) {
            error("\"" + input + "\" is not a line number.");
            return;
        }
        if (line >= view.lineCount() && !text.index.isDone()) {
            pendingLine = line;
            pendingCenter = true;
            updateStatus();
        } else {
            view.goToLine(line);
        }
    }

    // ---- find -----------------------------------------------------------------------------

    private void showFind() {
        if (text == null) {
            return;
        }
        String selected = view.selectedWord();
        JTextField field = new JTextField(selected != null ? selected : lastQuery, 32);
        field.selectAll();
        field.addAncestorListener(new AncestorListener() {
            @Override
            public void ancestorAdded(AncestorEvent e) {
                SwingUtilities.invokeLater(field::requestFocusInWindow);
            }

            @Override
            public void ancestorRemoved(AncestorEvent e) {
                // only the initial focus matters
            }

            @Override
            public void ancestorMoved(AncestorEvent e) {
                // only the initial focus matters
            }
        });
        JCheckBox matchCase = new JCheckBox("Match case", lastMatchCase);
        JPanel panel = new JPanel(new GridLayout(0, 1, 0, 4));
        panel.add(new JLabel("Find what:"));
        panel.add(field);
        panel.add(matchCase);
        int r = JOptionPane.showConfirmDialog(this, panel, "Find", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION || field.getText().isEmpty()) {
            return;
        }
        if (!field.getText().equals(lastQuery) || matchCase.isSelected() != lastMatchCase) {
            lastMatchOffset = -1;
        }
        lastQuery = field.getText();
        lastMatchCase = matchCase.isSelected();
        findNext();
    }

    private void findNext() {
        if (text == null) {
            return;
        }
        if (lastQuery.isEmpty()) {
            showFind();
            return;
        }
        if (searching) {
            return;
        }
        FileText t = text;
        byte[] pattern = lastQuery.getBytes(t.charset());
        boolean matchCase = lastMatchCase;
        long from = lastMatchOffset >= 0 ? lastMatchOffset + 1 : view.topLineOffset();
        String query = lastQuery;

        searchCancel = false;
        searchPercent = 0;
        searching = true;
        updateStatus();
        Thread worker = new Thread(() -> {
            try {
                search(t, pattern, matchCase, from, query);
            } catch (IOException | UncheckedIOException e) {
                SwingUtilities.invokeLater(() -> error("Search failed: " + e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                SwingUtilities.invokeLater(() -> {
                    searching = false;
                    updateStatus();
                });
            }
        }, "search");
        worker.setDaemon(true);
        worker.start();
    }

    /** Runs on the search thread; shows the result on the event thread. */
    private void search(FileText t, byte[] pattern, boolean matchCase, long from, String query)
            throws IOException, InterruptedException {
        Searcher s = new Searcher(t.channel, t.size(), pattern, matchCase, () -> searchCancel,
                done -> searchPercent = (int) (done * 100 / Math.max(1, t.size())));
        long hit = s.findNext(from);
        if (searchCancel) {
            return;
        }
        if (hit < 0) {
            SwingUtilities.invokeLater(() -> error("Cannot find \"" + query + "\"."));
            return;
        }
        // The line number of the hit is only known once the indexer has passed it.
        while (!t.index.isDone() && t.index.scannedBytes() <= hit && !searchCancel) {
            Thread.sleep(20);
        }
        FileText.Position pos = t.locate(hit, pattern.length);
        SwingUtilities.invokeLater(() -> {
            if (text == t && !searchCancel) {
                lastMatchOffset = hit;
                view.showMatch(pos);
                notice = hit < from ? "Search wrapped to the beginning of the file." : null;
            }
        });
    }

    private void cancelSearch() {
        searchCancel = true;
    }

    // ---- view sync ------------------------------------------------------------------------

    private void poll() {
        if (text == null) {
            return;
        }
        long count = view.lineCount();
        if (count != lastLineCount) {
            lastLineCount = count;
            if (pendingLine >= 0 && (pendingLine < count - view.rows() || text.index.isDone())) {
                long line = pendingLine;
                pendingLine = -1;
                if (pendingCenter) {
                    view.goToLine(line);
                } else {
                    view.setTopLine(line);
                }
            }
            view.repaint();
            syncScrollbars();
        } else if (searching) {
            updateStatus();
        }
    }

    private void syncScrollbars() {
        syncing = true;
        try {
            long maxTop = view.maxTop();
            int rows = view.rows();
            vScale = Math.max(1, (maxTop + rows) / 1_000_000_000L + 1);
            int extent = (int) Math.max(1, rows / vScale);
            vbar.setValues((int) (view.topLine() / vScale), extent, 0, (int) (maxTop / vScale) + extent);
            vbar.setBlockIncrement(extent);

            int cols = view.visibleCols();
            hbar.setValues(view.leftCol(), cols, 0, Math.max(view.maxCols(), view.leftCol() + cols));
            hbar.setBlockIncrement(cols);
            hbar.setUnitIncrement(4);
        } finally {
            syncing = false;
        }
        updateStatus();
    }

    private void updateStatus() {
        if (text == null) {
            statusLeft.setText("No file open");
            statusRight.setText(charset.displayName());
            return;
        }
        if (searching) {
            statusLeft.setText("Searching... " + searchPercent + "%   (Esc to cancel)");
        } else if (pendingLine >= 0) {
            statusLeft.setText("Waiting for the index to reach line " + Format.number(pendingLine + 1) + "...");
        } else {
            statusLeft.setText(notice != null ? notice : text.path.toString());
        }
        StringBuilder sb = new StringBuilder();
        TextView.Pos caret = view.caret();
        if (caret != null) {
            String col = caret.col() == Integer.MAX_VALUE ? "end" : Integer.toString(caret.col() + 1);
            sb.append("Ln ").append(Format.number(caret.line() + 1)).append(", Col ").append(col).append(STATUS_GAP);
        }
        sb.append(Format.number(view.lineCount())).append(" lines").append(indexState(text.index));
        sb.append(STATUS_GAP).append(Format.bytes(text.size()));
        sb.append(STATUS_GAP).append(text.charset().displayName());
        statusRight.setText(sb.toString());
    }

    private static String indexState(LineIndex idx) {
        if (idx.error() != null) {
            return " (index error: " + idx.error().getMessage() + ")";
        }
        if (!idx.isDone()) {
            return " (indexing " + idx.scannedBytes() * 100 / Math.max(1, idx.fileSize) + "%)";
        }
        return "";
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(this, message, "BigView", JOptionPane.WARNING_MESSAGE);
    }

    private final class FileDropHandler extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @Override
        public boolean importData(TransferSupport support) {
            try {
                @SuppressWarnings("unchecked")
                List<File> files = (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                if (files.isEmpty()) {
                    return false;
                }
                openFile(files.get(0).toPath(), -1);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
