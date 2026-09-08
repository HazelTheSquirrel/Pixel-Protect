package de.pixelprotect.model;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class EntitySnapshotTest {
    @Test void roundTripsStructuredSnapshot(){
        UUID id=UUID.randomUUID(); EntitySnapshot original=new EntitySnapshot(id,"minecraft:zombie","Zombie",null);
        EntitySnapshot parsed=EntitySnapshot.parse(original.serialize());
        assertNotNull(parsed); assertEquals(original.uuid(),parsed.uuid()); assertEquals(original.type(),parsed.type()); assertEquals(original.name(),parsed.name());
    }
    @Test void acceptsLegacySnapshot(){UUID id=UUID.randomUUID();EntitySnapshot parsed=EntitySnapshot.parse("pixelprotect:entity;type=minecraft:item;uuid="+id+";name=Diamond");assertNotNull(parsed);assertEquals(id,parsed.uuid());assertEquals("minecraft:item",parsed.type());}
}
