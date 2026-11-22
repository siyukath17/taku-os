package org.agentos.lab;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The broker's stderr as a small, size-capped log file. init starts {@code taku_broker} with stdio on
 * {@code /dev/null}, so every {@code System.err} line (prewarm timings such as
 * {@code acp prewarm (...) ready in Nms}, runtime failures, loop errors) used to vanish.
 *
 * <p>{@link #install} points {@code System.err} at {@code broker.log}: each line gets a local
 * timestamp; once the file passes {@code maxBytes} it becomes {@code broker.log.1} (replacing the
 * previous one) and a new file starts, so at most about {@code 2 × maxBytes} is ever kept. Logging
 * never throws into the caller: on any I/O failure the line is dropped and the next write retries.
 */
final class BrokerLog extends OutputStream {
    static final File DIR = new File("/data/local/agentos/acp/logs");
    static final long MAX_BYTES = 1_572_864; // 1.5 MiB per file, one older file kept

    private final File file, older;
    private final long maxBytes;
    private final SimpleDateFormat stamp = new SimpleDateFormat("MM-dd HH:mm:ss.SSS ", Locale.ROOT);
    private FileOutputStream out;
    private long size;
    private boolean lineStart = true;

    BrokerLog(File file, long maxBytes) {
        this.file = file;
        this.older = new File(file.getPath() + ".1");
        this.maxBytes = maxBytes;
    }

    /** Sends {@code System.err} to {@code DIR/broker.log}; keeps the old stream when the file cannot be opened. */
    static void install(String header) {
        try {
            DIR.mkdirs();
            BrokerLog log = new BrokerLog(new File(DIR, "broker.log"), MAX_BYTES);
            log.open();
            System.setErr(new PrintStream(log, true, "UTF-8"));
            System.err.println(header);
        } catch (Throwable unavailable) {
            System.err.println("broker log unavailable: " + unavailable);
        }
    }

    private void open() throws IOException {
        out = new FileOutputStream(file, true);
        size = file.length();
    }

    @Override
    public synchronized void write(int b) {
        write(new byte[]{(byte) b}, 0, 1);
    }

    @Override
    public synchronized void write(byte[] bytes, int off, int len) {
        try {
            if (out == null) open();
            int from = off, end = off + len;
            for (int i = off; i < end; i++) {
                if (lineStart) {
                    if (size >= maxBytes) rotate();
                    else if (!file.exists()) reopen(); // removed under us (cleanup): start a new file
                    byte[] prefix = stamp.format(new Date()).getBytes(StandardCharsets.UTF_8);
                    out.write(prefix);
                    size += prefix.length;
                    lineStart = false;
                }
                if (bytes[i] == '\n') {
                    out.write(bytes, from, i + 1 - from);
                    size += i + 1 - from;
                    from = i + 1;
                    lineStart = true;
                }
            }
            if (from < end) {
                out.write(bytes, from, end - from);
                size += end - from;
            }
        } catch (IOException | RuntimeException dropped) {
            // Never fail the caller over a log line; reopen on the next write.
            closeQuietly();
        }
    }

    @Override
    public synchronized void flush() {
        // FileOutputStream writes through; nothing is buffered here.
    }

    private void rotate() throws IOException {
        closeQuietly();
        if (older.exists() && !older.delete()) throw new IOException("cannot delete " + older);
        if (file.exists() && !file.renameTo(older)) throw new IOException("cannot rotate " + file);
        open();
    }

    private void reopen() throws IOException {
        closeQuietly();
        open();
    }

    private void closeQuietly() {
        try {
            if (out != null) out.close();
        } catch (IOException ignored) {
            // Already broken.
        }
        out = null;
    }
}
