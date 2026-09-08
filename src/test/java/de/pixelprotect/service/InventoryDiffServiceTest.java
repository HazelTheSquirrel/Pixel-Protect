package de.pixelprotect.service;

import org.junit.jupiter.api.Test;

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
    void detectsBothInsertedAndRemovedSlotChanges() {
        String[] before = {"stone:10", "oak_log:2"};
        String[] after = {"stone:4", "oak_planks:5"};

        String diff = InventoryDiffService.diffEncoded(before, after);

        assertTrue(diff.contains("\"before\":\"stone:10\""));
        assertTrue(diff.contains("\"after\":\"stone:4\""));
        assertTrue(diff.contains("\"before\":\"oak_log:2\""));
        assertTrue(diff.contains("\"after\":\"oak_planks:5\""));
        assertTrue(InventoryDiffService.matchesEncoded(after, diff, true));
        assertTrue(InventoryDiffService.matchesEncoded(before, diff, false));
    }
}
