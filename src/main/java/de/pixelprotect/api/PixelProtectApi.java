package de.pixelprotect.api;

import de.pixelprotect.model.ActionType;

import java.util.List;
import java.util.UUID;

/** Stable entry point for external PixelProtect integrations. */
public interface PixelProtectApi {
    UUID newTransaction();
    boolean isWorldIncluded(UUID world);
    Diagnostics diagnostics();
    void registerProtectionAttributor(ProtectionAttributor attributor);
    void unregisterProtectionAttributor(ProtectionAttributor attributor);
    List<ProtectionAttributor> protectionAttributors();

    record Diagnostics(boolean running, int queueSize, int schemaVersion, long auditCount,
                       long retentionPurged, long overflowRecords, long failedRollbacks) {}

    @FunctionalInterface
    interface ProtectionAttributor {
        /** Returns an optional external actor identifier for a protected-world action. */
        String attribute(UUID world, int x, int y, int z, ActionType action);
    }
}
