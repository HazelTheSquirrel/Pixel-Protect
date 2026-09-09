package de.pixelprotect.service;

import de.pixelprotect.database.DatabaseManager;
import de.pixelprotect.model.Endpoint;
import de.pixelprotect.model.Owner;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class OwnershipService {
    private final DatabaseManager database;
    private final AsyncLogQueue queue;
    private final ConcurrentMap<String, Owner> owners = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, UUID> incarnations = new ConcurrentHashMap<>();

    public OwnershipService(DatabaseManager database, AsyncLogQueue queue) {
        this.database = database;
        this.queue = queue;
    }

    public void record(Endpoint endpoint, Owner owner) {
        String endpointId = endpoint.identity();
        owners.put(endpointId, owner);
        UUID incarnation = UUID.randomUUID();
        incarnations.put(endpointId, incarnation);
        queue.submitOwnership(new AsyncLogQueue.OwnershipRecord(endpointId, endpoint, owner, Instant.now(), incarnation, true));
    }

    public void invalidate(Endpoint endpoint) {
        String endpointId = endpoint.identity();
        owners.remove(endpointId);
        incarnations.remove(endpointId);
        queue.submitOwnership(new AsyncLogQueue.OwnershipRecord(endpointId, endpoint, null, Instant.now(), null, false));
    }

    public CompletableFuture<Optional<Owner>> resolve(Endpoint endpoint) {
        Owner cached = owners.get(endpoint.identity());
        if (cached != null) return CompletableFuture.completedFuture(Optional.of(cached));
        return CompletableFuture.supplyAsync(() -> {
            try {
                return database.findOwner(endpoint.identity());
            } catch (Exception exception) {
                throw new IllegalStateException("Owner lookup failed for " + endpoint.identity(), exception);
            }
        });
    }

    public UUID incarnation(Endpoint endpoint) {
        return incarnations.get(endpoint.identity());
    }
}
