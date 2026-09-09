package de.pixelprotect.service;

import de.pixelprotect.model.BlockLog;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.UUID;

public final class WorldForensicsService {
    private final AsyncLogQueue queue;
    public WorldForensicsService(AsyncLogQueue queue) { this.queue = queue; }

    public void blockPlaced(Player player, Block block, String before) {
        queue.submitBlock(new BlockLog(UUID.randomUUID(), Instant.now(), player.getUniqueId(), player.getName(), block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), before, block.getBlockData().getAsString(), "PLACE", block.getType().getKey().toString()));
    }

    public void blockBroken(Player player, Block block, String before) {
        queue.submitBlock(new BlockLog(UUID.randomUUID(), Instant.now(), player.getUniqueId(), player.getName(), block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), before, "minecraft:air", "BREAK", block.getType().getKey().toString()));
    }
}
