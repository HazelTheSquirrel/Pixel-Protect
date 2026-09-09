package de.pixelprotect.service;

import de.pixelprotect.database.DatabaseManager;
import de.pixelprotect.model.BlockLog;
import de.pixelprotect.model.Endpoint;
import de.pixelprotect.model.Owner;
import de.pixelprotect.model.TransferLog;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AsyncLogQueue implements AutoCloseable {
    private final DatabaseManager database;
    private final BlockingQueue<Entry> queue;
    private final Thread worker;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public AsyncLogQueue(DatabaseManager database, int capacity) {
        this.database = database;
        this.queue = new ArrayBlockingQueue<>(Math.max(1000, capacity));
        this.worker = Thread.ofPlatform().name("PixelProtect-Logger").start(this::run);
    }

    public void submitTransfer(TransferLog log) { offer(new TransferEntry(log)); }
    public void submitBlock(BlockLog log) { offer(new BlockEntry(log)); }
    public void submitOwner(String endpointId, Endpoint endpoint, Owner owner, Instant placedAt) { offer(new OwnerEntry(endpointId, endpoint, owner, placedAt)); }

    private void offer(Entry entry) {
        if (!running.get() || !queue.offer(entry)) throw new IllegalStateException("Pixel-Protect logging queue is full");
    }

    private void run() {
        while (running.get() || !queue.isEmpty()) {
            try {
                Entry entry = queue.poll(250, TimeUnit.MILLISECONDS);
                if (entry != null) entry.write(database);
            } catch (InterruptedException e) {
                if (!running.get()) Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("[Pixel-Protect] Async log write failed: " + e.getMessage());
            }
        }
    }

    public int pending() { return queue.size(); }

    @Override public void close() {
        if (!running.compareAndSet(true, false)) return;
        worker.interrupt();
        try { worker.join(10_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        while (!queue.isEmpty()) {
            Entry entry = queue.poll();
            if (entry == null) break;
            try { entry.write(database); } catch (Exception e) { System.err.println("[Pixel-Protect] Final log write failed: " + e.getMessage()); }
        }
    }

    private sealed interface Entry permits TransferEntry, BlockEntry, OwnerEntry { void write(DatabaseManager database) throws Exception; }
    private record TransferEntry(TransferLog log) implements Entry { public void write(DatabaseManager d)throws Exception{d.insertTransfer(log);} }
    private record BlockEntry(BlockLog log) implements Entry { public void write(DatabaseManager d)throws Exception{d.insertBlock(log);} }
    private record OwnerEntry(String id, Endpoint endpoint, Owner owner, Instant placedAt) implements Entry { public void write(DatabaseManager d)throws Exception{d.upsertOwner(id,endpoint,owner,placedAt);} }
}
