package de.pixelprotect.service;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class InventoryDiffServiceTest {
    @Test
    void detectsSlotChangesDeterministically() {
        ItemStack[] before = {new ItemStack(org.bukkit.Material.STONE, 1), null};
        ItemStack[] after = {new ItemStack(org.bukkit.Material.STONE, 2), new ItemStack(org.bukkit.Material.DIRT, 3)};
        String diff = InventoryDiffService.diff(before, after);
        assertTrue(diff.contains("\"slot\":0"));
        assertTrue(diff.contains("\"slot\":1"));
        assertTrue(InventoryDiffService.matches(after, diff, true));
        assertTrue(InventoryDiffService.matches(before, diff, false));
        assertFalse(InventoryDiffService.matches(new ItemStack[]{new ItemStack(org.bukkit.Material.STONE, 9), after[1]}, diff, true));
    }
}
