package de.pixelprotect.service;

import de.pixelprotect.database.DatabaseManager;
import de.pixelprotect.model.Endpoint;
import de.pixelprotect.model.Owner;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class OwnershipService {
    private final DatabaseManager database;
    private final AsyncLogQueue queue;
    private final ConcurrentMap<String, Owner> owners = new ConcurrentHashMap<>();

    public OwnershipService(DatabaseManager database, AsyncLogQueue queue) { this.database = database; this.queue = queue; }

    public void register(Endpoint endpoint, Owner owner) {
        owners.put(endpoint.key(), owner);
        queue.submitOwner(endpoint.key(), endpoint, owner, Instant.now());
    }

    public Optional<Owner> cached(Endpoint endpoint) { return Optional.ofNullable(owners.get(endpoint.key())); }

    public Owner cachedOrUnknown(Endpoint endpoint) { return cached(endpoint).orElse(null); }

    public Owner load(Endpoint endpoint) {
        Owner cached = owners.get(endpoint.key());
        if (cached != null) return cached;
        try {
            Optional<Owner> stored = database.findOwner(endpoint.key());
            stored.ifPresent(owner -> owners.put(endpoint.key(), owner));
            return stored.orElse(null);
        } catch (Exception ignored) { return null; }
    }

    public Owner playerOwner(UUID uuid, String name) { return new Owner(uuid, name); }
}
