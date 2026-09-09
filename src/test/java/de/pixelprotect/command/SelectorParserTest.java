package de.pixelprotect.command;

import de.pixelprotect.model.ActionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SelectorParserTest {
    @Test
    void parsesExtendedSelectors() {
        var p = SelectorParser.parse(List.of("u:Steve", "t:90m", "r:32", "a:BREAK,-PLACE", "i:stone,oak_log", "e:dirt", "w:world_nether", "c:10,64,-20", "#page:3", "#count"), 64, 1, 128, 168);
        assertTrue(p.errors().isEmpty());
        assertEquals(2, p.hours());
        assertEquals(32, p.radius());
        assertEquals("Steve", p.user());
        assertEquals("world_nether", p.world());
        assertEquals(10, p.x());
        assertEquals(64, p.y());
        assertEquals(-20, p.z());
        assertNull(p.chunkX());
        assertTrue(p.includeActions().contains(ActionType.BREAK));
        assertTrue(p.excludeActions().contains(ActionType.PLACE));
        assertEquals(3, p.page());
        assertTrue(p.countOnly());
    }

    @Test
    void parsesChunkSelector() {
        var p = SelectorParser.parse(List.of("ch:-2,7", "t:30s"), 5, 1, 128, 168);
        assertTrue(p.errors().isEmpty());
        assertEquals(-2, p.chunkX());
        assertEquals(7, p.chunkZ());
        assertEquals(1, p.hours());
    }

    @Test
    void parsesActionPrefixes() {
        var placed = SelectorParser.parse(List.of("a:+block"), 5, 1, 128, 168);
        assertTrue(placed.errors().isEmpty());
        assertEquals(Set.of(ActionType.PLACE), placed.includeActions());

        var broken = SelectorParser.parse(List.of("a:-block"), 5, 1, 128, 168);
        assertTrue(broken.errors().isEmpty());
        assertEquals(Set.of(ActionType.BREAK), broken.excludeActions());
    }

    @Test
    void parsesDecimalDuration() {
        var p = SelectorParser.parse(List.of("t:2.5h"), 5, 1, 128, 168);
        assertTrue(p.errors().isEmpty());
        assertEquals(9_000_000L, p.durationMillis());
    }

    @Test
    void rejectsInvalidSelector() {
        var p = SelectorParser.parse(List.of("c:1,2", "#page:0", "a:UNKNOWN"), 5, 1, 128, 168);
        assertFalse(p.errors().isEmpty());
    }
}
