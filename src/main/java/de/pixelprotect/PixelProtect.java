package de.pixelprotect;

import de.pixelprotect.command.PixelProtectCommand;
import de.pixelprotect.listener.BlockAuditListener;
import de.pixelprotect.listener.InspectListener;
import de.pixelprotect.listener.PlayerAuditListener;
import de.pixelprotect.service.AuditService;
import de.pixelprotect.service.InspectService;
import de.pixelprotect.service.RollbackService;
import de.pixelprotect.storage.Database;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.sql.SQLException;

public final class PixelProtect extends JavaPlugin {
    private Database database;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        try {
            final Path databaseFile = getDataFolder().toPath().resolve(
                    getConfig().getString("storage.file", "pixelprotect.db"));
            database = new Database(
                    databaseFile,
                    getConfig().getInt("storage.queue-capacity", 10_000),
                    getConfig().getInt("storage.batch-size", 256),
                    getConfig().getLong("storage.flush-interval-millis", 250L),
                    getLogger());
            database.open();
        } catch (SQLException | java.io.IOException exception) {
            getLogger().severe("Failed to initialize SQLite: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        final AuditService audit = new AuditService(database);
        final InspectService inspect = new InspectService(database);
        final RollbackService rollback = new RollbackService(this, audit);
        getServer().getPluginManager().registerEvents(new BlockAuditListener(audit), this);
        getServer().getPluginManager().registerEvents(new PlayerAuditListener(audit), this);
        getServer().getPluginManager().registerEvents(new InspectListener(this, inspect), this);

        final PixelProtectCommand command = new PixelProtectCommand(
                this,
                database,
                rollback,
                inspect,
                getConfig().getInt("rollback.max-hours", 168),
                getConfig().getInt("rollback.max-radius", 128),
                getConfig().getInt("rollback.max-records", 100_000));
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> {
            final var builder = command.create().build();
            commands.registrar().register(builder, "Audit and rollback world changes");
        });

        if (getConfig().getBoolean("retention.enabled", true)) {
            final int days = Math.max(1, getConfig().getInt("retention.days", 30));
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task ->
                    database.purgeBefore(System.currentTimeMillis() - days * 86_400_000L),
                    20L, 24_000L);
        }

        getLogger().info("PixelProtect enabled. Standalone audit core is ready.");
    }

    @Override
    public void onDisable() {
        if (database != null) {
            database.close();
            database = null;
        }
    }
}
