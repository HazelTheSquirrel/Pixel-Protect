package de.pixelprotect.service;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InventoryDiffServiceTest {
    @Test
    void detectsSlotChangesDeterministically() {
        String[] before = {"stone:1", ""};
        String[] after = {"stone:2", "dirt:3"};

        String diff = InventoryDiffService.diffEncoded(before, after);

        assertTrue(diff.contains("\"slot\":0"));
        assertTrue(diff.contains("\"slot\":1"));
        assertTrue(InventoryDiffService.matchesEncoded(after, diff, true));
        assertTrue(InventoryDiffService.matchesEncoded(before, diff, false));
        assertFalse(InventoryDiffService.matchesEncoded(
                new String[]{"stone:9", "dirt:3"}, diff, true));
    }

    @Test
    void handlesNullAndDifferentLengthInventories() {
        String diff = InventoryDiffService.diffEncoded(null, new String[]{"stone:1", null, "dirt:2"});

        assertTrue(InventoryDiffService.matchesEncoded(
                new String[]{"stone:1", null, "dirt:2"}, diff, true));
        assertTrue(InventoryDiffService.matchesEncoded(null, diff, false));
        assertFalse(InventoryDiffService.matchesEncoded(
                new String[]{"stone:1", null}, diff, true));
    }

    @Test
    void emptyDiffMatchesAnyInventory() {
        assertTrue(InventoryDiffService.matchesEncoded(null, null, true));
        assertTrue(InventoryDiffService.matchesEncoded(new String[]{"stone:1"}, "", false));
        assertTrue(InventoryDiffService.diffEncoded(null, null).contains("\"changes\":[]"));
    }

    @Test
    void emptyItemStackArraysNeverEscapeDiffCalculation() {
        assertTrue(InventoryDiffService.itemChanges((ItemStack[]) null, (ItemStack[]) null).isEmpty());
        assertTrue(InventoryDiffService.itemChanges(new ItemStack[0], new ItemStack[0]).isEmpty());
    }

    @Test
    void reportsBothInsertedAndRemovedItems() {
        ItemStack[] before = {new ItemStack(Material.STONE, 10), new ItemStack(Material.OAK_LOG, 2)};
        ItemStack[] after = {new ItemStack(Material.STONE, 4), new ItemStack(Material.OAK_PLANKS, 5)};

        var changes = InventoryDiffService.itemChanges(before, after);

        assertEquals(3, changes.size());
        assertEquals(-6, changes.stream().filter(c -> c.item().getType() == Material.STONE)
                .findFirst().orElseThrow().amount());
        assertEquals(-2, changes.stream().filter(c -> c.item().getType() == Material.OAK_LOG)
                .findFirst().orElseThrow().amount());
        assertEquals(5, changes.stream().filter(c -> c.item().getType() == Material.OAK_PLANKS)
                .findFirst().orElseThrow().amount());
        assertTrue(changes.stream().anyMatch(InventoryDiffService.ItemChange::removed));
        assertTrue(changes.stream().anyMatch(InventoryDiffService.ItemChange::added));
    }

    @Test
    void keepsItemMetadataAsPartOfItemIdentity() {
        ItemStack plain = new ItemStack(Material.STONE, 10);
        ItemStack named = new ItemStack(Material.STONE, 10);
        named.editMeta(meta -> meta.displayName(net.kyori.adventure.text.Component.text("Spezialstein")));

        var changes = InventoryDiffService.itemChanges(new ItemStack[]{plain}, new ItemStack[]{named});

        assertEquals(2, changes.size());
        assertTrue(changes.stream().anyMatch(c -> c.amount() < 0));
        assertTrue(changes.stream().anyMatch(c -> c.amount() > 0));
    }
}
