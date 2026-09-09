package de.pixelprotect.service;

import de.pixelprotect.database.DatabaseManager;
import de.pixelprotect.model.Endpoint;
import de.pixelprotect.model.Owner;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class OwnershipService {
    private final DatabaseManager database;
    private final AsyncLogQueue queue;
    private final ConcurrentHashMap<String, CompletableFuture<Optional<Owner>>> cache = new ConcurrentHashMap<>();

    public OwnershipService(DatabaseManager database, AsyncLogQueue queue){this.database=database;this.queue=queue;}

    public void record(Endpoint endpoint, UUID ownerUuid, String ownerName){
        Owner owner=new Owner(ownerUuid,ownerName);
        cache.put(endpoint.identity(),CompletableFuture.completedFuture(Optional.of(owner)));
        CompletableFuture.runAsync(()->{try{database.upsertOwner(endpoint.identity(),endpoint,owner,Instant.now());}catch(Exception e){throw new RuntimeException(e);}});
    }

    public CompletableFuture<Optional<Owner>> resolve(Endpoint endpoint){
        if(endpoint.type()!=de.pixelprotect.model.EndpointType.BLOCK_CONTAINER && endpoint.type()!=de.pixelprotect.model.EndpointType.MINECART)return CompletableFuture.completedFuture(Optional.empty());
        return cache.computeIfAbsent(endpoint.identity(),key->CompletableFuture.supplyAsync(()->{try{return database.findOwner(key);}catch(Exception e){throw new RuntimeException(e);}}));
    }
}
