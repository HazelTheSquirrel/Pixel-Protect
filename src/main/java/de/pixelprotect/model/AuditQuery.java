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
        if (world == null) throw new IllegalArgumentException("world must not be null");
        if (radius < 0 || radius > 30_000) throw new IllegalArgumentException("radius out of range");
        if (since < 0 || until < since) throw new IllegalArgumentException("invalid time range");
        if (limit < 1 || limit > 100_000) throw new IllegalArgumentException("limit out of range");
        if (offset < 0 || offset > 10_000_000) throw new IllegalArgumentException("offset out of range");
        actorName = actorName == null ? null : actorName.trim();
        if (actorName != null && actorName.length() > 255) throw new IllegalArgumentException("actorName too long");
        includeActions = Set.copyOf(includeActions == null ? Set.of() : includeActions);
        excludeActions = Set.copyOf(excludeActions == null ? Set.of() : excludeActions);
        includeBlocks = normalizeBlocks(includeBlocks);
        excludeBlocks = normalizeBlocks(excludeBlocks);
        if (!java.util.Collections.disjoint(includeActions, excludeActions)) throw new IllegalArgumentException("action include/exclude overlap");
    }

    public AuditQuery(UUID world, int centerX, int centerY, int centerZ, int radius, long since, long until,
                      String actorName, Set<ActionType> includeActions, Set<ActionType> excludeActions,
                      Set<String> includeBlocks, Set<String> excludeBlocks, int limit) {
        this(world, centerX, centerY, centerZ, radius, since, until, actorName, includeActions,
                excludeActions, includeBlocks, excludeBlocks, limit, 0);
    }

    private static Set<String> normalizeBlocks(Set<String> blocks) {
        if (blocks == null || blocks.isEmpty()) return Set.of();
        return blocks.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).map(String::toLowerCase).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
