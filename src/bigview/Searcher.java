package bigview;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Byte-level search through a file in large chunks. Case folding is ASCII only. */
final class Searcher {

    private static final int CHUNK = 8 * 1024 * 1024;

    private final FileChannel channel;
    private final long size;
    private final byte[] pattern;
    private final boolean matchCase;
    private final BooleanSupplier cancelled;
    private final LongConsumer progress;

    Searcher(FileChannel channel, long size, byte[] pattern, boolean matchCase,
             BooleanSupplier cancelled, LongConsumer progress) {
        this.channel = channel;
        this.size = size;
        this.pattern = matchCase ? pattern : lower(pattern);
        this.matchCase = matchCase;
        this.cancelled = cancelled;
        this.progress = progress;
    }

    /**
     * Finds the first match starting at or after {@code from}, wrapping around to the
     * beginning of the file. Returns its byte offset, or -1 if there is none (or cancelled).
     */
    long findNext(long from) throws IOException {
        long hit = search(from, size, 0);
        if (hit < 0 && from > 0) {
            hit = search(0, Math.min(from, size), size - from);
        }
        return hit;
    }

    /** Search for a match starting in {@code [from, to)}; {@code done} is progress before this range. */
    private long search(long from, long to, long done) throws IOException {
        int m = pattern.length;
        if (m == 0) {
            return -1;
        }
        ByteBuffer buf = ByteBuffer.allocate(CHUNK + m - 1);
        byte[] a = buf.array();
        byte first = pattern[0];
        long pos = from;
        while (pos < to) {
            if (cancelled.getAsBoolean()) {
                return -1;
            }
            buf.clear();
            buf.limit((int) Math.min(buf.capacity(), size - pos));
            while (buf.hasRemaining() && channel.read(buf, pos + buf.position()) > 0) {
                // keep filling
            }
            int n = buf.position();
            int limit = (int) Math.min(n - m + 1, to - pos);
            for (int i = 0; i < limit; i++) {
                if (fold(a[i]) == first && matchesAt(a, i)) {
                    return pos + i;
                }
            }
            if (n < m) {
                break;
            }
            pos += CHUNK;
            progress.accept(done + Math.min(pos, to) - from);
        }
        return -1;
    }

    private boolean matchesAt(byte[] a, int i) {
        for (int j = 1; j < pattern.length; j++) {
            if (fold(a[i + j]) != pattern[j]) {
                return false;
            }
        }
        return true;
    }

    private byte fold(byte b) {
        return matchCase || b < 'A' || b > 'Z' ? b : (byte) (b + 32);
    }

    private static byte[] lower(byte[] p) {
        byte[] r = p.clone();
        for (int i = 0; i < r.length; i++) {
            if (r[i] >= 'A' && r[i] <= 'Z') {
                r[i] += 32;
            }
        }
        return r;
    }
}
