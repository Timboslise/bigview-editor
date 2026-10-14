package bigview;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** A large file opened read-only; lines are read from disk on demand. */
final class FileText implements Closeable {

    static final int TAB_SIZE = 4;

    /** One display line. {@code start}/{@code end} are byte offsets of its content (newline excluded). */
    record Line(long number, long start, long end, String raw) {
        String display() {
            return expandTabs(raw);
        }
    }

    /** A located byte range: line number plus raw character columns within that line. */
    record Position(long line, int rawStart, int rawEnd) {
    }

    final Path path;
    final FileChannel channel;
    final LineIndex index;
    private volatile Charset charset = StandardCharsets.UTF_8;

    FileText(Path path) throws IOException {
        this.path = path;
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
        this.index = new LineIndex(channel, channel.size());
        index.start();
    }

    long size() {
        return index.fileSize;
    }

    Charset charset() {
        return charset;
    }

    void setCharset(Charset charset) {
        this.charset = charset;
    }

    @Override
    public void close() throws IOException {
        index.cancel();
        channel.close();
    }

    /** Reads up to {@code count} lines starting at line {@code first}. */
    List<Line> readLines(long first, int count) {
        List<Line> result = new ArrayList<>(count);
        if (first < 0 || first >= index.lineCount() || count <= 0) {
            return result;
        }
        int k = (int) (first / LineIndex.STRIDE);
        long pos = index.checkpoint(k);
        long line = (long) k * LineIndex.STRIDE;
        Scanner sc = new Scanner();
        while (line < first) {
            sc.scanLine(pos);
            pos = sc.next;
            if (pos < 0) {
                return result;
            }
            line++;
        }
        while (result.size() < count) {
            long end = sc.scanLine(pos);
            result.add(new Line(line, pos, end, decode(pos, end)));
            pos = sc.next;
            if (pos < 0) {
                break;
            }
            line++;
        }
        return result;
    }

    /** Finds the line containing byte {@code offset} and the raw columns of {@code [offset, offset+length)}. */
    Position locate(long offset, int length) {
        int k = index.checkpointBefore(offset);
        long pos = index.checkpoint(k);
        long line = (long) k * LineIndex.STRIDE;
        Scanner sc = new Scanner();
        while (true) {
            long end = sc.scanLine(pos);
            if (sc.next < 0 || sc.next > offset) {
                int rawStart = decode(pos, Math.min(offset, end)).length();
                int rawEnd = decode(pos, Math.min(offset + length, end)).length();
                return new Position(line, rawStart, Math.max(rawStart, rawEnd));
            }
            pos = sc.next;
            line++;
        }
    }

    /** Byte offset of raw column {@code rawCol} in {@code line}. */
    long byteOffset(Line line, int rawCol) {
        int col = Math.min(rawCol, line.raw().length());
        return Math.min(line.start() + line.raw().substring(0, col).getBytes(charset).length, line.end());
    }

    String decode(long from, long to) {
        String s = new String(readBytes(from, to), charset);
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    byte[] readBytes(long from, long to) {
        int len = (int) Math.max(0, to - from);
        ByteBuffer buf = ByteBuffer.allocate(len);
        try {
            while (buf.hasRemaining()) {
                if (channel.read(buf, from + buf.position()) < 0) {
                    break;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buf.position() == len ? buf.array() : java.util.Arrays.copyOf(buf.array(), buf.position());
    }

    static String expandTabs(String raw) {
        if (raw.indexOf('\t') < 0) {
            return raw;
        }
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\t') {
                do {
                    sb.append(' ');
                } while (sb.length() % TAB_SIZE != 0);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Display column of raw index {@code rawIdx}. */
    static int displayCol(String raw, int rawIdx) {
        int col = 0;
        for (int i = 0; i < rawIdx && i < raw.length(); i++) {
            col = raw.charAt(i) == '\t' ? (col / TAB_SIZE + 1) * TAB_SIZE : col + 1;
        }
        return col;
    }

    /** Raw index of the character at display column {@code col} (a column inside a tab maps to the tab). */
    static int rawIndex(String raw, int col) {
        int c = 0;
        for (int i = 0; i < raw.length(); i++) {
            int next = raw.charAt(i) == '\t' ? (c / TAB_SIZE + 1) * TAB_SIZE : c + 1;
            if (col < next) {
                return col == c ? i : (col - c < next - col ? i : i + 1);
            }
            c = next;
        }
        return raw.length();
    }

    /** Walks line boundaries using the same rule as {@link LineIndex}, through a read buffer. */
    private final class Scanner {
        private final ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
        private long bufStart;
        private int bufLen;
        /** Start of the line after the last scanned one, or -1 at end of file. */
        long next;

        private int get(long p) {
            if (p >= index.fileSize) {
                return -1;
            }
            if (p < bufStart || p >= bufStart + bufLen) {
                buf.clear();
                try {
                    bufLen = Math.max(0, channel.read(buf, p));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                bufStart = p;
                if (bufLen == 0) {
                    return -1;
                }
            }
            return buf.array()[(int) (p - bufStart)] & 0xFF;
        }

        /** Returns the content end of the line starting at {@code start}; sets {@link #next}. */
        long scanLine(long start) {
            int len = 0;
            long p = start;
            while (true) {
                int b = get(p);
                if (b < 0) {
                    next = -1;
                    return p;
                }
                if (b == '\n') {
                    next = p + 1 < index.fileSize ? p + 1 : -1;
                    return p;
                }
                if (LineIndex.breaksBefore((byte) b, len)) {
                    next = p;
                    return p;
                }
                len++;
                p++;
            }
        }
    }
}
