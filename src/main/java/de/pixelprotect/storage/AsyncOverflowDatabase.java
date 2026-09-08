package de.pixelprotect.storage;

import com.google.gson.Gson;
import de.pixelprotect.model.AuditEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Durable overflow writer that never performs disk I/O on an event or region thread. */
public class AsyncOverflowDatabase extends Database {
    private static final Gson GSON = new Gson();
    private final BlockingQueue<String> overflowQueue;
    private final AtomicBoolean overflowRunning = new AtomicBoolean();
    private final AtomicLong overflowAccepted = new AtomicLong();
    private final AtomicLong overflowDropped = new AtomicLong();
    private final Thread overflowWriter;

    public AsyncOverflowDatabase(Path file, int queueCapacity, int batchSize, long flushIntervalMillis, Logger logger) {
        super(file, queueCapacity, batchSize, flushIntervalMillis, logger);
        int capacity = Math.max(256, Math.min(50_000, queueCapacity));
        this.overflowQueue = new ArrayBlockingQueue<>(capacity);
        this.overflowWriter = new Thread(this::writeOverflowLoop, "PixelProtect-OverflowWriter");
        this.overflowWriter.setDaemon(true);
    }

    @Override
    public void open() throws java.sql.SQLException, IOException {
        super.open();
        overflowRunning.set(true);
        overflowWriter.start();
    }

    @Override
    protected boolean spool(AuditEntry entry) {
        if (!overflowRunning.get()) return false;
        final String line;
        try {
            line = GSON.toJson(entry) + System.lineSeparator();
        } catch (RuntimeException ex) {
            logger.log(Level.SEVERE, "Audit record could not be serialized for overflow storage.", ex);
            overflowDropped.incrementAndGet();
            return false;
        }
        if (!overflowQueue.offer(line)) {
            overflowDropped.incrementAndGet();
            return false;
        }
        overflowAccepted.incrementAndGet();
        return true;
    }

    public long overflowAccepted() { return overflowAccepted.get(); }
    public long overflowDropped() { return overflowDropped.get(); }
    public int overflowQueueSize() { return overflowQueue.size(); }

    private void writeOverflowLoop() {
        while (overflowRunning.get() || !overflowQueue.isEmpty()) {
            try {
                String line = overflowQueue.poll(250, TimeUnit.MILLISECONDS);
                if (line != null) appendLine(line);
            } catch (InterruptedException ex) {
                if (!overflowRunning.get()) Thread.currentThread().interrupt();
            } catch (IOException ex) {
                logger.log(Level.SEVERE, "Failed to persist an audit overflow record.", ex);
                try { Thread.sleep(250L); }
                catch (InterruptedException interrupted) {
                    if (!overflowRunning.get()) Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void appendLine(String line) throws IOException {
        Path parent = overflowFile.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(overflowFile, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    @Override
    public void close() {
        overflowRunning.set(false);
        overflowWriter.interrupt();
        try { overflowWriter.join(10_000L); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        super.close();
    }
}
