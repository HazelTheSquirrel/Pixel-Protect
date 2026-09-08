package de.pixelprotect.service;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InventoryDiffServiceTest {
    @Test
    void detectsSlotChangesDeterministically() {
        ItemStack[] before = {ItemStack.of(Material.STONE), null};
        ItemStack[] after = {ItemStack.of(Material.STONE, 2), ItemStack.of(Material.DIRT, 3)};

        String diff = InventoryDiffService.diff(before, after);

        assertTrue(diff.contains("\"slot\":0"));
        assertTrue(diff.contains("\"slot\":1"));
        assertTrue(InventoryDiffService.matches(after, diff, true));
        assertTrue(InventoryDiffService.matches(before, diff, false));
        assertFalse(InventoryDiffService.matches(
                new ItemStack[]{ItemStack.of(Material.STONE, 9), after[1]}, diff, true));
    }
}
