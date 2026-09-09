package de.pixelprotect.storage;

import de.pixelprotect.model.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseTest {
    @Test void migratesAndPagesAuditRows() throws Exception {
        var dir = Files.createTempDirectory("pixelprotect-db-");
        var db = new Database(dir.resolve("test.db"), 100, 10, 25, Logger.getAnonymousLogger());
        try {
            db.open();
            assertEquals(8, db.schemaVersion());
            UUID world = UUID.randomUUID();
            for (int i = 0; i < 3; i++) {
                db.record(new AuditEntry(0, System.currentTimeMillis() + i, world, i, 64, 0, null,
                        "Environment", ActionType.BREAK, "minecraft:stone", "minecraft:air", null, null,
                        "{\"kind\":\"sign\"}", "{\"kind\":\"sign\"}"));
            }
            var q = new AuditQuery(world, 1, 64, 0, 16, 0, Long.MAX_VALUE, null,
                    Set.of(), Set.of(), Set.of(), Set.of(), 2, 1);
            assertEquals(3, db.count(q).join());
            var page = db.query(q).join();
            assertEquals(2, page.size());
            assertTrue(page.getFirst().id() > 0);
        } finally {
            db.close();
        }
    }
}
