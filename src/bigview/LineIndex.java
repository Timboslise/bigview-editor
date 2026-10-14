package bigview;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

/**
 * Sparse index of line start offsets, built on a background thread.
 * Only the start of every {@link #STRIDE}-th line is stored, so memory stays tiny
 * even for files with hundreds of millions of lines. Readers may use the index
 * while it is still being built; everything they see is already final.
 */
final class LineIndex {

    static final int STRIDE = 64;

    /** Lines longer than this are split into several display lines. */
    static final int MAX_LINE_BYTES = 10_000;

    private static final int CHUNK = 8 * 1024 * 1024;

    final long fileSize;
    private final FileChannel channel;

    /*
     * Single writer, many readers. The writer stores an element (copying into a new array
     * first when growing) and only then publishes it by writing checkpointCount. Readers read
     * checkpointCount first and then the array, so every index below the count is visible:
     * a later grown array is a copy that contains it as well. AtomicLongArray cannot grow.
     */
    private volatile long[] checkpoints = new long[1024];
    private volatile int checkpointCount;
    private volatile long lineCount = 1;
    private volatile long scannedBytes;
    private volatile int maxLineBytes;
    private volatile boolean done;
    private volatile boolean cancelled;
    private volatile IOException error;

    LineIndex(FileChannel channel, long fileSize) {
        this.channel = channel;
        this.fileSize = fileSize;
        addCheckpoint(0);
    }

    /**
     * The splitting rule shared by the indexer and every reader: a line that has already
     * reached the maximum length is broken before {@code b}, unless {@code b} continues a
     * UTF-8 multi-byte character (a few bytes of slack keep binary garbage bounded).
     */
    static boolean breaksBefore(byte b, int lineLength) {
        return lineLength >= MAX_LINE_BYTES
                && ((b & 0xC0) != 0x80 || lineLength >= MAX_LINE_BYTES + 4);
    }

    void start() {
        Thread t = new Thread(this::build, "line-indexer");
        t.setDaemon(true);
        t.start();
    }

    void cancel() {
        cancelled = true;
    }

    private void build() {
        ByteBuffer buf = ByteBuffer.allocate(CHUNK);
        byte[] a = buf.array();
        long lines = 1;
        int len = 0;
        int maxLen = 0;
        long pos = 0;
        try {
            while (pos < fileSize && !cancelled) {
                buf.clear();
                buf.limit((int) Math.min(CHUNK, fileSize - pos));
                int n = channel.read(buf, pos);
                if (n <= 0) {
                    break;
                }
                for (int i = 0; i < n; i++) {
                    byte b = a[i];
                    if (b == '\n') {
                        if (len > maxLen) {
                            maxLen = len;
                        }
                        len = 0;
                        long next = pos + i + 1;
                        if (next < fileSize) {
                            if (lines % STRIDE == 0) {
                                addCheckpoint(next);
                            }
                            lines++;
                        }
                    } else if (breaksBefore(b, len)) {
                        maxLen = Math.max(maxLen, len);
                        len = 1;
                        if (lines % STRIDE == 0) {
                            addCheckpoint(pos + i);
                        }
                        lines++;
                    } else {
                        len++;
                    }
                }
                pos += n;
                lineCount = lines;
                maxLineBytes = Math.max(maxLen, len);
                scannedBytes = pos;
            }
        } catch (IOException e) {
            error = e;
        } finally {
            lineCount = lines;
            maxLineBytes = Math.max(maxLen, len);
            scannedBytes = pos;
            done = true;
        }
    }

    private void addCheckpoint(long offset) {
        long[] arr = checkpoints;
        int n = checkpointCount;
        if (n == arr.length) {
            arr = Arrays.copyOf(arr, n * 2);
            checkpoints = arr;
        }
        arr[n] = offset;
        checkpointCount = n + 1;
    }

    /** Byte offset where line {@code k * STRIDE} starts. */
    long checkpoint(int k) {
        return checkpoints[k];
    }

    /** Index of the last checkpoint at or before {@code offset}. */
    int checkpointBefore(long offset) {
        int n = checkpointCount;
        long[] arr = checkpoints;
        int lo = 0;
        int hi = n - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (arr[mid] <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    long lineCount() {
        return lineCount;
    }

    long scannedBytes() {
        return scannedBytes;
    }

    int maxLineBytes() {
        return maxLineBytes;
    }

    boolean isDone() {
        return done;
    }

    IOException error() {
        return error;
    }
}
