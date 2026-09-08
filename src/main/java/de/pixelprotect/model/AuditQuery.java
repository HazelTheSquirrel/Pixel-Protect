package de.pixelprotect.model;

import java.util.Set;
import java.util.UUID;

/** Immutable, validated description of an audit query. */
public record AuditQuery(
        UUID world,
        int centerX,
        int centerY,
        int centerZ,
        int radius,
        long since,
        long until,
        String actorName,
        Set<ActionType> includeActions,
        Set<ActionType> excludeActions,
        Set<String> includeBlocks,
        Set<String> excludeBlocks,
        int limit,
        int offset
) {
    public AuditQuery {
        radius = Math.max(0, radius);
        limit = Math.max(1, limit);
        offset = Math.max(0, offset);
        includeActions = Set.copyOf(includeActions == null ? Set.of() : includeActions);
        excludeActions = Set.copyOf(excludeActions == null ? Set.of() : excludeActions);
        includeBlocks = Set.copyOf(includeBlocks == null ? Set.of() : includeBlocks);
        excludeBlocks = Set.copyOf(excludeBlocks == null ? Set.of() : excludeBlocks);
    }

    public AuditQuery(UUID world, int centerX, int centerY, int centerZ, int radius, long since, long until,
                      String actorName, Set<ActionType> includeActions, Set<ActionType> excludeActions,
                      Set<String> includeBlocks, Set<String> excludeBlocks, int limit) {
        this(world, centerX, centerY, centerZ, radius, since, until, actorName, includeActions,
                excludeActions, includeBlocks, excludeBlocks, limit, 0);
    }
}
