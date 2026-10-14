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
import java.awt.event.InputEvent;
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

    private record Selection(Pos start, Pos end) {
        boolean coversLine(long line) {
            return line >= start.line() && line <= end.line();
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
    private FileText.Position searchMatch;

    private Font textFont;
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
        searchMatch = null;
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
        long t = Math.clamp(line, 0L, maxTop());
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
        int c = Math.clamp(col, 0, maxCols());
        if (c != leftCol) {
            leftCol = c;
            repaint();
        }
        changed();
    }

    /** Scrolls so that {@code line} is in the middle of the screen and marks it as the current line. */
    void goToLine(long line) {
        long l = Math.clamp(line, 0L, lastLine());
        caret = anchor = new Pos(l, 0);
        setLeftCol(0);
        setTopLine(l - rows() / 2);
    }

    void showMatch(FileText.Position p) {
        searchMatch = p;
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
        textFont = new Font(FONT_FAMILY, Font.PLAIN, Math.clamp(size, 6, 72));
        FontMetrics fm = getFontMetrics(textFont);
        charW = Math.max(1, fm.charWidth('m'));
        lineH = fm.getHeight();
        ascent = fm.getAscent();
        setTopLine(topLine);
        repaint();
        changed();
    }

    int fontSize() {
        return textFont.getSize();
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
        Selection sel = selection();
        if (text == null || sel == null) {
            return null;
        }
        FileText.Line ls = lineAt(sel.start().line());
        FileText.Line le = lineAt(sel.end().line());
        long from = text.byteOffset(ls, FileText.rawIndex(ls.raw(), sel.start().col()));
        long to = text.byteOffset(le, FileText.rawIndex(le.raw(), sel.end().col()));
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

    /** The selection with start before end, or null when nothing is selected. */
    private Selection selection() {
        if (anchor == null || caret == null || anchor.equals(caret)) {
            return null;
        }
        return anchor.compareTo(caret) < 0 ? new Selection(anchor, caret) : new Selection(caret, anchor);
    }

    private Pos clampToLine(Pos p) {
        FileText.Line line = lineAt(p.line());
        int len = line == null ? 0 : FileText.expandTabs(line.raw()).length();
        return new Pos(p.line(), Math.clamp(p.col(), 0, len));
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
        g.setColor(BG);
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setFont(textFont);
        if (text == null) {
            g.setColor(GUTTER_FG);
            g.drawString("Open a file with Ctrl+O, or drop it here.", charW * 2, lineH * 2);
            return;
        }
        g.setColor(GUTTER_BG);
        g.fillRect(0, 0, gutterWidth(), getHeight());

        Selection selection = selection();
        List<FileText.Line> lines = visibleLines();
        int longest = 0;
        for (int i = 0; i < lines.size(); i++) {
            longest = Math.max(longest, paintLine(g, lines.get(i), i * lineH, selection));
        }
        if (longest > longestVisible) {
            longestVisible = longest;
            changed();
        }
    }

    /** Paints one row and returns its display length. */
    private int paintLine(Graphics2D g, FileText.Line line, int y, Selection selection) {
        String display = line.display();
        long n = line.number();
        int gutter = gutterWidth();

        if (selection == null && caret != null && caret.line() == n) {
            g.setColor(CURRENT_LINE);
            g.fillRect(gutter, y, getWidth() - gutter, lineH);
        }
        if (selection != null && selection.coversLine(n)) {
            int from = n == selection.start().line() ? selection.start().col() : 0;
            int to = n == selection.end().line()
                    ? Math.min(selection.end().col(), display.length())
                    : display.length() + 1;
            fillCols(g, SELECTION, y, from, to);
        }
        if (searchMatch != null && searchMatch.line() == n) {
            int from = FileText.displayCol(line.raw(), searchMatch.rawStart());
            int to = FileText.displayCol(line.raw(), searchMatch.rawEnd());
            fillCols(g, MATCH, y, from, Math.max(to, from + 1));
        }

        g.setColor(GUTTER_FG);
        String num = Long.toString(n + 1);
        g.drawString(num, gutter - charW - num.length() * charW, y + ascent);

        if (leftCol < display.length()) {
            String visible = display.substring(leftCol, Math.min(display.length(), leftCol + visibleCols() + 1));
            g.setColor(FG);
            Graphics2D clip = (Graphics2D) g.create(gutter, 0, getWidth() - gutter, getHeight());
            clip.drawString(visible, textX() - gutter, y + ascent);
            clip.dispose();
        }
        return display.length();
    }

    private void fillCols(Graphics2D g, Color c, int y, int fromCol, int toCol) {
        int a = Math.max(fromCol, leftCol);
        int b = Math.min(toCol, leftCol + visibleCols() + 1);
        if (b > a) {
            g.setColor(c);
            g.fillRect(textX() + (a - leftCol) * charW, y, (b - a) * charW, lineH);
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
        bind(im, KeyEvent.VK_HOME, InputEvent.CTRL_DOWN_MASK, "top", () -> setTopLine(0));
        bind(im, KeyEvent.VK_END, InputEvent.CTRL_DOWN_MASK, "bottom", () -> setTopLine(Long.MAX_VALUE));
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
            autoScrollDir = autoScrollDirection(e.getY());
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

        private Pos posAt(Point pt) {
            long line = Math.clamp(topLine + Math.floorDiv(pt.y, lineH), 0L, lastLine());
            int col = leftCol + (int) Math.round((pt.x - textX()) / (double) charW);
            return clampToLine(new Pos(line, Math.max(0, col)));
        }

        /** -1 above the view, 1 below it, 0 inside. */
        private int autoScrollDirection(int y) {
            if (y < 0) {
                return -1;
            }
            return y >= getHeight() ? 1 : 0;
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
