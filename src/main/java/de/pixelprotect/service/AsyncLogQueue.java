package de.pixelprotect.service;

import de.pixelprotect.database.DatabaseManager;
import de.pixelprotect.model.BlockLog;
import de.pixelprotect.model.Endpoint;
import de.pixelprotect.model.Owner;
import de.pixelprotect.model.TransferLog;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class AsyncLogQueue implements AutoCloseable {
    private final DatabaseManager database;
    private final LinkedBlockingQueue<Entry> queue;
    private final Thread worker;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong pendingWrites = new AtomicLong();
    private final AtomicLong failedWrites = new AtomicLong();

    public AsyncLogQueue(DatabaseManager database, int capacity) {
        this.database = database;
        this.queue = new LinkedBlockingQueue<>();
        this.worker = Thread.ofPlatform().name("PixelProtect-Logger").start(this::run);
    }

    public void submitTransfer(TransferLog log) { offer(new TransferEntry(log)); }
    public void submitBlock(BlockLog log) { offer(new BlockEntry(log)); }
    public void submitOwner(String endpointId, Endpoint endpoint, Owner owner, Instant placedAt) { offer(new OwnerEntry(endpointId, endpoint, owner, placedAt)); }

    private void offer(Entry entry) {
        if (!running.get()) {
            throw new IllegalStateException("Pixel-Protect logging queue is stopped");
        }
        queue.add(entry);
        pendingWrites.incrementAndGet();
    }

    private void run() {
        while (running.get() || !queue.isEmpty()) {
            try {
                Entry entry = queue.poll(250, TimeUnit.MILLISECONDS);
                if (entry == null) continue;
                try {
                    entry.write(database);
                } catch (Exception e) {
                    failedWrites.incrementAndGet();
                    System.err.println("[Pixel-Protect] Async log write failed: " + e.getMessage());
                } finally {
                    pendingWrites.decrementAndGet();
                }
            } catch (InterruptedException e) {
                if (!running.get()) Thread.currentThread().interrupt();
            }
        }
    }

    public int pending() {
        long value = pendingWrites.get();
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    public long failedWrites() { return failedWrites.get(); }

    @Override public void close() {
        if (!running.compareAndSet(true, false)) return;
        worker.interrupt();
        try {
            worker.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            System.err.println("[Pixel-Protect] Logger worker did not stop within 10 seconds; queued entries remain in memory.");
        }
    }

    private sealed interface Entry permits TransferEntry, BlockEntry, OwnerEntry {
        void write(DatabaseManager database) throws Exception;
    }

    private record TransferEntry(TransferLog log) implements Entry {
        public void write(DatabaseManager d) throws Exception { d.insertTransfer(log); }
    }

    private record BlockEntry(BlockLog log) implements Entry {
        public void write(DatabaseManager d) throws Exception { d.insertBlock(log); }
    }

    private record OwnerEntry(String id, Endpoint endpoint, Owner owner, Instant placedAt) implements Entry {
        public void write(DatabaseManager d) throws Exception { d.upsertOwner(id, endpoint, owner, placedAt); }
    }
}
