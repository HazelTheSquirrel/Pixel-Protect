package de.pixelprotect.api;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.storage.Database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Default API implementation owned by the plugin lifecycle. */
public final class PixelProtectApiImpl implements PixelProtectApi {
    private final Database database;
    private final Set<UUID> includedWorlds;
    private final CopyOnWriteArrayList<ProtectionAttributor> attributors = new CopyOnWriteArrayList<>();
    private final Path overflowFile;
    private volatile long retentionPurged;
    private volatile boolean running = true;

    public PixelProtectApiImpl(Database database, Set<UUID> includedWorlds, Path overflowFile) {
        this.database = database;
        this.includedWorlds = Set.copyOf(includedWorlds);
        this.overflowFile = overflowFile;
    }

    @Override public UUID newTransaction() { return UUID.randomUUID(); }
    @Override public boolean isWorldIncluded(UUID world) { return includedWorlds.isEmpty() || includedWorlds.contains(world); }
    @Override public Diagnostics diagnostics() {
        long overflow = 0;
        try { if (Files.exists(overflowFile)) try (var lines = Files.lines(overflowFile)) { overflow = lines.count(); } }
        catch (Exception ignored) { }
        return new Diagnostics(running, database.queueSize(), database.schemaVersion(), database.count().join(), retentionPurged, overflow, 0L);
    }
    @Override public void registerProtectionAttributor(ProtectionAttributor attributor) { if (attributor != null) attributors.addIfAbsent(attributor); }
    @Override public void unregisterProtectionAttributor(ProtectionAttributor attributor) { attributors.remove(attributor); }
    @Override public List<ProtectionAttributor> protectionAttributors() { return List.copyOf(attributors); }
    public String attribute(UUID world, int x, int y, int z, ActionType action) {
        for (ProtectionAttributor attributor : attributors) {
            try { String result = attributor.attribute(world, x, y, z, action); if (result != null && !result.isBlank()) return result; }
            catch (RuntimeException ignored) { }
        }
        return null;
    }
    public void addRetentionPurged(long amount) { retentionPurged += Math.max(0, amount); }
    public void shutdown() { running = false; }
}
