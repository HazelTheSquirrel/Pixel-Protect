package de.pixelprotect.service;

import de.pixelprotect.database.DatabaseManager;
import de.pixelprotect.model.BlockLog;
import de.pixelprotect.model.Endpoint;
import de.pixelprotect.model.Owner;
import de.pixelprotect.model.TransferLog;

import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class AsyncLogQueue implements AutoCloseable {
    private final ArrayBlockingQueue<Object> queue;
    private final DatabaseManager database;
    private final Thread worker;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Consumer<Throwable> errorHandler;

    public AsyncLogQueue(DatabaseManager database, int capacity, Consumer<Throwable> errorHandler) {
        if (capacity < 1024) throw new IllegalArgumentException("Logging queue capacity must be at least 1024");
        this.database = database;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.errorHandler = errorHandler;
        this.worker = Thread.ofPlatform().name("PixelProtect-LogWorker").daemon(true).start(this::run);
    }

    public void submit(TransferLog log) { offer(log); }
    public void submit(BlockLog log) { offer(log); }
    public void submitOwnership(OwnershipRecord record) { offer(record); }

    private void offer(Object record) {
        if (!accepting.get()) throw new IllegalStateException("Pixel-Protect logging is shutting down");
        boolean interrupted = false;
        try {
            for (;;) {
                try {
                    if (queue.offer(record, 250, TimeUnit.MILLISECONDS)) return;
                    if (!accepting.get()) throw new IllegalStateException("Pixel-Protect logging is shutting down");
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void run() {
        for (;;) {
            Object record = null;
            try {
                record = queue.poll(250, TimeUnit.MILLISECONDS);
                if (record != null) persist(record);
                if (!accepting.get() && queue.isEmpty()) return;
            } catch (InterruptedException ignored) {
                if (!accepting.get() && queue.isEmpty()) return;
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
                errorHandler.accept(throwable);
                if (record != null) requeue(record);
            }
        }
    }

    private void requeue(Object record) {
        for (;;) {
            try {
                if (queue.offer(record, 250, TimeUnit.MILLISECONDS)) return;
            } catch (InterruptedException ignored) {
                Thread.interrupted();
            }
        }
    }

    private void persist(Object record) throws Exception {
        if (record instanceof TransferLog transfer) database.insertTransfer(transfer);
        else if (record instanceof BlockLog block) database.insertBlock(block);
        else if (record instanceof OwnershipRecord ownership) database.persistOwnership(ownership);
    }

    public int pending() { return queue.size(); }
    public boolean healthy() { return failure.get() == null; }
    public Throwable failure() { return failure.get(); }

    @Override
    public void close() {
        accepting.set(false);
        worker.interrupt();
        try {
            worker.join(30000);
            if (worker.isAlive()) throw new IllegalStateException("Pixel-Protect logging worker did not drain within 30 seconds");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while draining Pixel-Protect logging queue", exception);
        }
        if (!queue.isEmpty()) throw new IllegalStateException("Pixel-Protect logging queue was not fully drained");
    }

    public record OwnershipRecord(String endpointId, Endpoint endpoint, Owner owner, Instant placedAt, java.util.UUID incarnationId, boolean active) {}
}
