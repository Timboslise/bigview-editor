package bigview;

import javax.swing.AbstractAction;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Paints only the rows currently on screen; never holds more than one screen of text. */
final class TextView extends JComponent {

    /** A caret/selection position: line number and display column. */
    record Pos(long line, int col) implements Comparable<Pos> {
        @Override
        public int compareTo(Pos o) {
            return line != o.line ? Long.compare(line, o.line) : Integer.compare(col, o.col);
        }
    }

    static final long MAX_COPY_BYTES = 50L * 1024 * 1024;

    private static final Color BG = Color.WHITE;
    private static final Color FG = new Color(0x1E1E1E);
    private static final Color GUTTER_BG = new Color(0xF3F3F3);
    private static final Color GUTTER_FG = new Color(0x9A9A9A);
    private static final Color CURRENT_LINE = new Color(0xF0F5FF);
    private static final Color SELECTION = new Color(0xADD6FF);
    private static final Color MATCH = new Color(0xFFD54F);
    private static final String FONT_FAMILY = pickFontFamily();

    private FileText text;
    private long topLine;
    private int leftCol;
    private Pos anchor;
    private Pos caret;
    private FileText.Position match;

    private Font font;
    private int charW;
    private int lineH;
    private int ascent;

    private List<FileText.Line> cache = List.of();
    private long cacheFirst = -1;
    private int cacheRows;
    private int longestVisible;

    private Runnable onChange = () -> { };
    private double wheelRemainder;
    private final Timer autoScroll;
    private int autoScrollDir;

    TextView() {
        setFocusable(true);
        setOpaque(true);
        setFontSize(14);
        installKeys();
        MouseAdapter mouse = new MouseHandler();
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(this::wheel);
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                setTopLine(topLine);
                changed();
            }
        });
        autoScroll = new Timer(40, e -> {
            scrollLines(autoScrollDir);
            Pos p = caret;
            if (p != null) {
                long line = autoScrollDir < 0 ? topLine : Math.min(lastLine(), topLine + rows() - 1);
                caret = clampToLine(new Pos(line, p.col()));
                changed();
            }
        });
    }

    void setOnChange(Runnable r) {
        onChange = r;
    }

    void setText(FileText t) {
        text = t;
        topLine = 0;
        leftCol = 0;
        anchor = caret = null;
        match = null;
        invalidateCache();
        changed();
    }

    void invalidateCache() {
        cache = List.of();
        cacheFirst = -1;
        repaint();
    }

    // ---- geometry -------------------------------------------------------------------------

    int rows() {
        return Math.max(1, getHeight() / lineH);
    }

    int visibleCols() {
        return Math.max(1, (getWidth() - textX()) / charW);
    }

    long lineCount() {
        return text == null ? 0 : text.index.lineCount();
    }

    private long lastLine() {
        return Math.max(0, lineCount() - 1);
    }

    long topLine() {
        return topLine;
    }

    long maxTop() {
        return Math.max(0, lineCount() - rows());
    }

    int leftCol() {
        return leftCol;
    }

    int maxCols() {
        int bytes = text == null ? 0 : text.index.maxLineBytes();
        return Math.max(bytes, longestVisible) + 1;
    }

    private int gutterWidth() {
        int digits = Math.max(3, Long.toString(Math.max(1, lineCount())).length());
        return digits * charW + 2 * charW;
    }

    private int textX() {
        return gutterWidth() + charW / 2;
    }

    Pos caret() {
        return caret;
    }

    // ---- scrolling ------------------------------------------------------------------------

    void setTopLine(long line) {
        long t = Math.max(0, Math.min(line, maxTop()));
        if (t != topLine) {
            topLine = t;
            repaint();
        }
        changed();
    }

    void scrollLines(long delta) {
        setTopLine(topLine + delta);
    }

    void setLeftCol(int col) {
        int c = Math.max(0, Math.min(col, maxCols()));
        if (c != leftCol) {
            leftCol = c;
            repaint();
        }
        changed();
    }

    /** Scrolls so that {@code line} is in the middle of the screen and marks it as the current line. */
    void goToLine(long line) {
        long l = Math.max(0, Math.min(line, lastLine()));
        caret = anchor = new Pos(l, 0);
        setLeftCol(0);
        setTopLine(l - rows() / 2);
    }

    void showMatch(FileText.Position p) {
        match = p;
        FileText.Line line = lineAt(p.line());
        int start = FileText.displayCol(line.raw(), p.rawStart());
        int end = FileText.displayCol(line.raw(), p.rawEnd());
        anchor = new Pos(p.line(), start);
        caret = new Pos(p.line(), end);
        if (p.line() < topLine || p.line() >= topLine + rows()) {
            setTopLine(p.line() - rows() / 2);
        }
        if (start < leftCol || end > leftCol + visibleCols()) {
            setLeftCol(Math.max(0, start - 10));
        }
        repaint();
        changed();
    }

    /** Byte offset where the topmost visible line starts. */
    long topLineOffset() {
        List<FileText.Line> lines = visibleLines();
        return lines.isEmpty() ? 0 : lines.get(0).start();
    }

    void setFontSize(int size) {
        font = new Font(FONT_FAMILY, Font.PLAIN, Math.max(6, Math.min(72, size)));
        FontMetrics fm = getFontMetrics(font);
        charW = Math.max(1, fm.charWidth('m'));
        lineH = fm.getHeight();
        ascent = fm.getAscent();
        setTopLine(topLine);
        repaint();
        changed();
    }

    int fontSize() {
        return font.getSize();
    }

    // ---- selection ------------------------------------------------------------------------

    void selectAll() {
        if (text == null) {
            return;
        }
        anchor = new Pos(0, 0);
        caret = new Pos(lastLine(), Integer.MAX_VALUE);
        repaint();
        changed();
    }

    /** The exact original text of the selection, or null if nothing is selected. */
    String selectedText() {
        if (text == null || anchor == null || caret == null || anchor.equals(caret)) {
            return null;
        }
        Pos s = anchor.compareTo(caret) <= 0 ? anchor : caret;
        Pos e = s == anchor ? caret : anchor;
        FileText.Line ls = lineAt(s.line());
        FileText.Line le = lineAt(e.line());
        long from = text.byteOffset(ls, FileText.rawIndex(ls.raw(), s.col()));
        long to = text.byteOffset(le, FileText.rawIndex(le.raw(), e.col()));
        if (to - from > MAX_COPY_BYTES) {
            throw new IllegalStateException("The selection is too large to copy ("
                    + Format.bytes(to - from) + "; the limit is " + Format.bytes(MAX_COPY_BYTES) + ").");
        }
        return new String(text.readBytes(from, to), text.charset());
    }

    /** Short single-line selection, used to prefill the find dialog. */
    String selectedWord() {
        if (anchor == null || caret == null || anchor.line() != caret.line() || anchor.equals(caret)) {
            return null;
        }
        String s = selectedText();
        return s != null && s.length() <= 200 ? s : null;
    }

    private Pos clampToLine(Pos p) {
        FileText.Line line = lineAt(p.line());
        int len = line == null ? 0 : FileText.expandTabs(line.raw()).length();
        return new Pos(p.line(), Math.max(0, Math.min(p.col(), len)));
    }

    private Pos posAt(Point pt) {
        long line = topLine + Math.floorDiv(pt.y, lineH);
        line = Math.max(0, Math.min(line, lastLine()));
        int col = leftCol + (int) Math.round((pt.x - textX()) / (double) charW);
        return clampToLine(new Pos(line, Math.max(0, col)));
    }

    // ---- reading --------------------------------------------------------------------------

    private List<FileText.Line> visibleLines() {
        if (text == null) {
            return List.of();
        }
        int want = rows() + 1;
        boolean incomplete = cache.size() < want && cacheFirst + cache.size() < lineCount();
        if (cacheFirst != topLine || cacheRows != want || incomplete) {
            cache = text.readLines(topLine, want);
            cacheFirst = topLine;
            cacheRows = want;
        }
        return cache;
    }

    private FileText.Line lineAt(long n) {
        long i = n - cacheFirst;
        if (i >= 0 && i < cache.size()) {
            return cache.get((int) i);
        }
        List<FileText.Line> l = text.readLines(n, 1);
        return l.isEmpty() ? new FileText.Line(n, text.size(), text.size(), "") : l.get(0);
    }

    // ---- painting -------------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0;
        Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
        if (hints instanceof Map<?, ?> m) {
            g.addRenderingHints(m);
        } else {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        }
        int w = getWidth();
        int h = getHeight();
        g.setColor(BG);
        g.fillRect(0, 0, w, h);
        g.setFont(font);
        if (text == null) {
            g.setColor(GUTTER_FG);
            g.drawString("Open a file with Ctrl+O, or drop it here.", charW * 2, lineH * 2);
            return;
        }

        List<FileText.Line> lines = visibleLines();
        int gutter = gutterWidth();
        int tx = textX();
        int cols = visibleCols() + 1;
        g.setColor(GUTTER_BG);
        g.fillRect(0, 0, gutter, h);

        Pos selStart = null;
        Pos selEnd = null;
        if (anchor != null && caret != null && !anchor.equals(caret)) {
            selStart = anchor.compareTo(caret) <= 0 ? anchor : caret;
            selEnd = selStart == anchor ? caret : anchor;
        }

        int longest = 0;
        for (int i = 0; i < lines.size(); i++) {
            FileText.Line line = lines.get(i);
            int y = i * lineH;
            String display = line.display();
            longest = Math.max(longest, display.length());

            if (caret != null && selStart == null && caret.line() == line.number()) {
                g.setColor(CURRENT_LINE);
                g.fillRect(gutter, y, w - gutter, lineH);
            }
            if (selStart != null && line.number() >= selStart.line() && line.number() <= selEnd.line()) {
                int a = line.number() == selStart.line() ? selStart.col() : 0;
                int b = line.number() == selEnd.line() ? Math.min(selEnd.col(), display.length()) : display.length() + 1;
                fillCols(g, SELECTION, tx, y, a, b);
            }
            if (match != null && match.line() == line.number()) {
                fillCols(g, MATCH, tx, y, FileText.displayCol(line.raw(), match.rawStart()),
                        Math.max(FileText.displayCol(line.raw(), match.rawEnd()),
                                FileText.displayCol(line.raw(), match.rawStart()) + 1));
            }

            g.setColor(GUTTER_FG);
            String num = Long.toString(line.number() + 1);
            g.drawString(num, gutter - charW - num.length() * charW, y + ascent);

            if (leftCol < display.length()) {
                String visible = display.substring(leftCol, Math.min(display.length(), leftCol + cols));
                g.setColor(FG);
                Graphics2D clip = (Graphics2D) g.create(gutter, 0, w - gutter, h);
                clip.drawString(visible, tx - gutter, y + ascent);
                clip.dispose();
            }
        }
        if (longest > longestVisible) {
            longestVisible = longest;
            changed();
        }
    }

    private void fillCols(Graphics2D g, Color c, int tx, int y, int fromCol, int toCol) {
        int a = Math.max(fromCol, leftCol);
        int b = Math.min(toCol, leftCol + visibleCols() + 1);
        if (b > a) {
            g.setColor(c);
            g.fillRect(tx + (a - leftCol) * charW, y, (b - a) * charW, lineH);
        }
    }

    private void changed() {
        onChange.run();
    }

    // ---- input ----------------------------------------------------------------------------

    private void installKeys() {
        InputMap im = getInputMap(WHEN_FOCUSED);
        bind(im, KeyEvent.VK_UP, 0, "up", () -> scrollLines(-1));
        bind(im, KeyEvent.VK_DOWN, 0, "down", () -> scrollLines(1));
        bind(im, KeyEvent.VK_PAGE_UP, 0, "pageUp", () -> scrollLines(-(rows() - 1)));
        bind(im, KeyEvent.VK_PAGE_DOWN, 0, "pageDown", () -> scrollLines(rows() - 1));
        bind(im, KeyEvent.VK_HOME, KeyEvent.CTRL_DOWN_MASK, "top", () -> setTopLine(0));
        bind(im, KeyEvent.VK_END, KeyEvent.CTRL_DOWN_MASK, "bottom", () -> setTopLine(Long.MAX_VALUE));
        bind(im, KeyEvent.VK_LEFT, 0, "left", () -> setLeftCol(leftCol - 4));
        bind(im, KeyEvent.VK_RIGHT, 0, "right", () -> setLeftCol(leftCol + 4));
        bind(im, KeyEvent.VK_HOME, 0, "lineStart", () -> setLeftCol(0));
        bind(im, KeyEvent.VK_END, 0, "lineEnd", () -> setLeftCol(longestVisible - visibleCols() + 1));
    }

    private void bind(InputMap im, int key, int mods, String name, Runnable r) {
        im.put(KeyStroke.getKeyStroke(key, mods), name);
        getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }

    private void wheel(MouseWheelEvent e) {
        if (e.isControlDown()) {
            setFontSize(fontSize() - e.getWheelRotation());
            return;
        }
        wheelRemainder += e.getPreciseWheelRotation() * 3;
        int whole = (int) wheelRemainder;
        wheelRemainder -= whole;
        if (whole == 0) {
            return;
        }
        if (e.isShiftDown()) {
            setLeftCol(leftCol + whole * 2);
        } else {
            scrollLines(whole);
        }
    }

    private final class MouseHandler extends MouseAdapter {
        @Override
        public void mousePressed(MouseEvent e) {
            requestFocusInWindow();
            if (text == null || e.getButton() != MouseEvent.BUTTON1) {
                return;
            }
            Pos p = posAt(e.getPoint());
            if (!e.isShiftDown() || anchor == null) {
                anchor = p;
            }
            caret = p;
            repaint();
            changed();
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            if (text == null || anchor == null) {
                return;
            }
            caret = posAt(e.getPoint());
            autoScrollDir = e.getY() < 0 ? -1 : e.getY() >= getHeight() ? 1 : 0;
            if (autoScrollDir != 0) {
                autoScroll.start();
            } else {
                autoScroll.stop();
            }
            repaint();
            changed();
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            autoScroll.stop();
        }
    }

    private static String pickFontFamily() {
        List<String> available = Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String f : List.of("Consolas", "Cascadia Mono", "DejaVu Sans Mono", "Menlo")) {
            if (available.contains(f)) {
                return f;
            }
        }
        return Font.MONOSPACED;
    }
}
