package de.pixelprotect;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.pixelprotect.command.PixelProtectCommand;
import de.pixelprotect.database.DatabaseManager;
import de.pixelprotect.listener.ForensicListener;
import de.pixelprotect.service.AsyncLogQueue;
import de.pixelprotect.service.InspectorService;
import de.pixelprotect.service.OwnershipService;
import de.pixelprotect.service.RollbackService;
import de.pixelprotect.service.TransferService;
import de.pixelprotect.service.WorldAuditService;
import de.pixelprotect.util.ItemCodec;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

public final class PixelProtect extends JavaPlugin {
    private DatabaseManager database;
    private AsyncLogQueue queue;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            String storageMode = getConfig().getString("storage.mode", "local");
            boolean fallbackToLocal = getConfig().getBoolean("storage.fallback-to-local", true);
            database = new DatabaseManager(
                    Path.of(getDataFolder().getPath(), "logs"),
                    storageMode,
                    fallbackToLocal,
                    getConfig().getString("mysql.host", "127.0.0.1"),
                    getConfig().getInt("mysql.port", 3306),
                    getConfig().getString("mysql.database", "pixelprotect"),
                    getConfig().getString("mysql.username", "pixelprotect"),
                    getConfig().getString("mysql.password", "change-me"),
                    getConfig().getInt("mysql.pool-size", 8)
            );
            database.initialize();

            queue = new AsyncLogQueue(database, getConfig().getInt("logging.queue-capacity", 100000),
                    t -> getLogger().severe("Forensik-Logging fehlgeschlagen: " + t.getMessage()));

            Gson gson = new GsonBuilder().disableHtmlEscaping().create();
            ItemCodec codec = new ItemCodec(gson);
            OwnershipService ownership = new OwnershipService(database, queue);
            TransferService transfers = new TransferService(this, queue, ownership, codec);
            WorldAuditService world = new WorldAuditService(this, queue, ownership, database);
            InspectorService inspector = new InspectorService(this, database, codec);
            RollbackService rollback = new RollbackService(this, database, codec);

            getServer().getPluginManager().registerEvents(new ForensicListener(transfers, world, inspector), this);
            getLifecycleManager().registerEventHandler(
                    io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                    event -> event.registrar().register("pp", new PixelProtectCommand(database, inspector, rollback))
            );

            getLogger().info("Pixel-Protect Forensik-System aktiviert. Speicher: " + (storageMode.equalsIgnoreCase("local") ? "lokale JSONL-Dateien" : "MySQL"));
        } catch (Exception e) {
            getLogger().severe("Pixel-Protect konnte nicht gestartet werden: " + e.getMessage());
            if (database != null) database.close();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (queue != null) queue.close();
        if (database != null) database.close();
    }
}
