package de.pixelprotect.model;

import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

public record BlockSnapshot(String blockData, byte[] inventory) {
    public static BlockSnapshot capture(Block block) {
        return fromState(block.getState(), block.getBlockData());
    }

    public static BlockSnapshot fromState(BlockState state) {
        return fromState(state, state.getBlockData());
    }

    public static BlockSnapshot fromState(BlockState state, BlockData data) {
        final byte[] inventory = state instanceof InventoryHolder holder
                ? ItemStack.serializeItemsAsBytes(holder.getInventory().getContents())
                : null;
        return new BlockSnapshot(data.getAsString(), inventory);
    }
}
