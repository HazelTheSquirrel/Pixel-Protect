package de.pixelprotect.storage;

import com.google.gson.Gson;
import de.pixelprotect.model.AuditEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.PreparedStatement;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Dedicated durable overflow writer. Event and region threads only enqueue serialized records. */
public class AsyncOverflowDatabase extends Database {
    private static final Gson GSON = new Gson();
    private final LinkedBlockingQueue<String> overflowQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean overflowRunning = new AtomicBoolean();
    private final AtomicLong overflowAccepted = new AtomicLong();
    private final AtomicLong overflowDropped = new AtomicLong();
    private final Object overflowFileLock = new Object();
    private final Thread overflowWriter;

    public AsyncOverflowDatabase(Path file, int queueCapacity, int batchSize, long flushIntervalMillis, Logger logger) {
        super(file, queueCapacity, batchSize, flushIntervalMillis, logger);
        this.overflowWriter = new Thread(this::writeOverflowLoop, "PixelProtect-OverflowWriter");
        this.overflowWriter.setDaemon(true);
    }

    @Override
    public void open() throws java.sql.SQLException, IOException {
        super.open();
        startOverflowWriter();
    }

    protected final void startOverflowWriter() {
        if (overflowRunning.compareAndSet(false, true)) overflowWriter.start();
    }

    @Override
    protected boolean spool(AuditEntry entry) {
        if (!overflowRunning.get()) {
            try {
                Files.writeString(overflowFile, GSON.toJson(entry) + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                overflowAccepted.incrementAndGet();
                return true;
            } catch (IOException exception) {
                overflowDropped.incrementAndGet();
                logger.log(Level.SEVERE, "Failed to persist an audit overflow record while the overflow writer was offline.", exception);
                return false;
            }
        }
        try {
            overflowQueue.put(GSON.toJson(entry) + System.lineSeparator());
            overflowAccepted.incrementAndGet();
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            overflowDropped.incrementAndGet();
            return false;
        } catch (RuntimeException exception) {
            overflowDropped.incrementAndGet();
            logger.log(Level.SEVERE, "Audit record could not be serialized for overflow storage.", exception);
            return false;
        }
    }

    @Override
    public java.util.concurrent.CompletableFuture<Integer> purgeBefore(long cutoff) {
        return executeAsync(() -> {
            flushQueue();
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM audit WHERE time < ? AND id NOT IN (SELECT audit_id FROM rollback_job_entries)")) {
                statement.setLong(1, cutoff);
                return statement.executeUpdate();
            }
        });
    }

    public long overflowAccepted() { return overflowAccepted.get(); }
    public long overflowDropped() { return overflowDropped.get(); }
    public int overflowQueueSize() { return overflowQueue.size(); }

    @Override
    protected void replayOverflow() {
        synchronized (overflowFileLock) {
            super.replayOverflow();
        }
    }

    private void writeOverflowLoop() {
        while (overflowRunning.get() || !overflowQueue.isEmpty()) {
            try {
                String line = overflowQueue.poll(250, TimeUnit.MILLISECONDS);
                if (line != null) appendLine(line);
            } catch (InterruptedException exception) {
                if (!overflowRunning.get()) Thread.currentThread().interrupt();
            } catch (IOException exception) {
                logger.log(Level.SEVERE, "Failed to persist an audit overflow record; retrying.", exception);
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException interrupted) {
                    if (!overflowRunning.get()) Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void appendLine(String line) throws IOException {
        synchronized (overflowFileLock) {
            Path parent = overflowFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(overflowFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        }
    }

    @Override
    public void close() {
        overflowRunning.set(false);
        overflowWriter.interrupt();
        try {
            overflowWriter.join(10_000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        super.close();
    }
}
