package de.pixelprotect.util;

import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

public final class InventoryDiff {
    private InventoryDiff() {}

    public static Map<String, Integer> totals(ItemStack[] contents, ItemCodec codec) {
        Map<String, Integer> totals = new HashMap<>();
        for (ItemStack stack : contents) {
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) continue;
            totals.merge(codec.key(stack), stack.getAmount(), Integer::sum);
        }
        return totals;
    }

    public static Map<String, ItemStack> representatives(ItemStack[] contents, ItemCodec codec) {
        Map<String, ItemStack> result = new HashMap<>();
        for (ItemStack stack : contents) {
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) continue;
            result.putIfAbsent(codec.key(stack), stack.clone());
        }
        return result;
    }
}
