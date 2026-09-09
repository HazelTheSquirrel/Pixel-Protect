package de.pixelprotect;

import de.pixelprotect.api.PixelProtectApi;
import de.pixelprotect.api.PixelProtectApiImpl;
import de.pixelprotect.command.PixelProtectCommand;
import de.pixelprotect.listener.ActivityAuditListener;
import de.pixelprotect.listener.AdminCommandGuardListener;
import de.pixelprotect.listener.AutomationAuditListener;
import de.pixelprotect.listener.BlockAuditListener;
import de.pixelprotect.listener.ContainerProcessingAuditListener;
import de.pixelprotect.listener.EntityForensicsAuditListener;
import de.pixelprotect.listener.InspectListener;
import de.pixelprotect.listener.InventoryAuditListener;
import de.pixelprotect.listener.ModernMechanicsAuditListener;
import de.pixelprotect.listener.PlayerAuditListener;
import de.pixelprotect.listener.PlayerInventoryAuditListener;
import de.pixelprotect.listener.PlayerMechanicsAuditListener;
import de.pixelprotect.listener.PlayerTransactionAuditListener;
import de.pixelprotect.listener.WorldEditAuditListener;
import de.pixelprotect.service.AuditService;
import de.pixelprotect.service.AutomationTracker;
import de.pixelprotect.service.InspectService;
import de.pixelprotect.service.RollbackService;
import de.pixelprotect.storage.AsyncOverflowDatabase;
import de.pixelprotect.storage.Database;
import de.pixelprotect.storage.MySqlDatabase;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class PixelProtect extends JavaPlugin {
    private Database database;
    private PixelProtectApiImpl api;
    private WorldEditAuditListener worldEditAudit;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        final String backend = getConfig().getString("storage.backend", "sqlite").trim().toLowerCase(Locale.ROOT);
        final Path databaseFile = getDataFolder().toPath().resolve(getConfig().getString("storage.file", "pixelprotect.db"));
        try {
            database = switch (backend) {
                case "sqlite" -> new AsyncOverflowDatabase(databaseFile, getConfig().getInt("storage.queue-capacity", 10_000), getConfig().getInt("storage.batch-size", 256), getConfig().getLong("storage.flush-interval-millis", 250L), getLogger());
                case "mysql", "mariadb" -> new MySqlDatabase(databaseFile, getConfig().getInt("storage.queue-capacity", 10_000), getConfig().getInt("storage.batch-size", 256), getConfig().getLong("storage.flush-interval-millis", 250L), getLogger(), getConfig().getString("storage.mysql.jdbc-url"), getConfig().getString("storage.mysql.username"), getConfig().getString("storage.mysql.password"), getConfig().getInt("storage.mysql.maximum-pool-size", 10), getConfig().getInt("storage.mysql.minimum-idle", 2), getConfig().getLong("storage.mysql.connection-timeout-millis", 5000L), getConfig().getLong("storage.mysql.leak-detection-millis", 0L));
                default -> throw new IllegalArgumentException("Nicht unterstütztes Speicher-Backend: " + backend);
            };
            database.open();
        } catch (IllegalArgumentException | SQLException | java.io.IOException ex) {
            getLogger().severe("Speicher-Backend '" + backend + "' konnte nicht initialisiert werden: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        final Set<UUID> includedWorlds = resolveWorlds(getConfig().getStringList("worlds.include"));
        final Set<UUID> excludedWorlds = resolveWorlds(getConfig().getStringList("worlds.exclude"));
        final AuditService audit = new AuditService(database, includedWorlds, excludedWorlds);
        api = new PixelProtectApiImpl(database, includedWorlds, excludedWorlds, databaseFile.resolveSibling(databaseFile.getFileName() + ".overflow.jsonl"));
        getServer().getServicesManager().register(PixelProtectApi.class, api, this, ServicePriority.Normal);

        if (getServer().getPluginManager().getPlugin("WorldEdit") != null) {
            try {
                worldEditAudit = new WorldEditAuditListener(audit);
                worldEditAudit.register();
                getLogger().info("WorldEdit-Forensik aktiviert.");
            } catch (RuntimeException exception) {
                worldEditAudit = null;
                getLogger().warning("WorldEdit-Forensik konnte nicht aktiviert werden: " + exception.getMessage());
            }
        }

        final AutomationTracker automation = new AutomationTracker();
        final InspectService inspect = new InspectService(database);
        final RollbackService rollback = new RollbackService(this, audit, database, getConfig().getInt("rollback.max-concurrent-jobs", 1));
        getServer().getPluginManager().registerEvents(new BlockAuditListener(this, audit, getConfig().getBoolean("logging.block-place-break", true), getConfig().getBoolean("logging.explosions", true), getConfig().getBoolean("logging.fire", true), getConfig().getBoolean("logging.piston", true), getConfig().getBoolean("logging.fluids", true), getConfig().getBoolean("logging.growth", true), getConfig().getBoolean("logging.entity-block-changes", true)), this);
        getServer().getPluginManager().registerEvents(new ModernMechanicsAuditListener(this, audit), this);
        getServer().getPluginManager().registerEvents(new PlayerAuditListener(this, audit), this);
        getServer().getPluginManager().registerEvents(new ActivityAuditListener(this, audit), this);
        getServer().getPluginManager().registerEvents(new PlayerTransactionAuditListener(audit), this);
        getServer().getPluginManager().registerEvents(new InventoryAuditListener(this, audit, automation), this);
        getServer().getPluginManager().registerEvents(new PlayerInventoryAuditListener(this, audit), this);
        getServer().getPluginManager().registerEvents(new PlayerMechanicsAuditListener(this, audit), this);
        getServer().getPluginManager().registerEvents(new EntityForensicsAuditListener(this, audit, getConfig().getBoolean("logging.entity-damage", true)), this);
        getServer().getPluginManager().registerEvents(new ContainerProcessingAuditListener(this, audit), this);
        getServer().getPluginManager().registerEvents(new AutomationAuditListener(this, automation), this);
        getServer().getPluginManager().registerEvents(new InspectListener(this, inspect, automation), this);
        getServer().getPluginManager().registerEvents(new AdminCommandGuardListener(this), this);

        final PixelProtectCommand command = new PixelProtectCommand(this, database, rollback, inspect, getConfig().getInt("rollback.max-hours", 168), getConfig().getInt("rollback.max-radius", 128), getConfig().getInt("rollback.max-records", 100_000));
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> commands.registrar().register(command.create().build(), "Protokolliert, untersucht und setzt Weltänderungen zurück"));

        if (getConfig().getBoolean("retention.enabled", true)) {
            final int days = Math.max(1, getConfig().getInt("retention.days", 30));
            final long interval = Math.max(1L, getConfig().getLong("retention.maintenance-interval-ticks", 24_000L));
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> database.purgeBefore(System.currentTimeMillis() - days * 86_400_000L).thenAccept(api::addRetentionPurged), 20L, interval);
        }
        if (getConfig().getBoolean("diagnostics.enabled", true)) {
            final long interval = Math.max(1L, getConfig().getLong("diagnostics.log-interval-minutes", 5L) * 1200L);
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> logDiagnostics(), interval, interval);
        }
        getLogger().info("PixelProtect wurde mit Speicher-Backend '" + backend + "' aktiviert. Einziger Befehlspräfix: /pixelprotect");
    }

    private void logDiagnostics() {
        if (database == null) return;
        database.count().thenCombine(database.failedRollbackCount(), (auditCount, failedRollbacks) -> "Diagnose: Warteschlange=" + database.queueSize() + ", Protokolle=" + auditCount + ", " + overflowDiagnostics() + ", fehlgeschlagene Rücksetzungen=" + failedRollbacks + ", Schema=" + database.schemaVersion()).thenAccept(getLogger()::info).exceptionally(failure -> { getLogger().warning("Diagnose konnte nicht vollständig erstellt werden: " + failure.getMessage()); return null; });
    }

    private String overflowDiagnostics() {
        if (database instanceof AsyncOverflowDatabase overflow) return "Überlauf-Warteschlange=" + overflow.overflowQueueSize() + ", Überlauf-angenommen=" + overflow.overflowAccepted() + ", Überlauf-verworfen=" + overflow.overflowDropped();
        return "Überlauf-Warteschlange=0, Überlauf-angenommen=0, Überlauf-verworfen=0";
    }

    private Set<UUID> resolveWorlds(List<String> names) {
        final Set<UUID> result = new HashSet<>();
        for (String name : names) {
            final var world = Bukkit.getWorld(name);
            if (world == null) getLogger().warning("Konfigurierte Welt existiert nicht: " + name); else result.add(world.getUID());
        }
        return Set.copyOf(result);
    }

    public PixelProtectApi api() { return api; }

    @Override
    public void onDisable() {
        if (worldEditAudit != null) { worldEditAudit.unregister(); worldEditAudit = null; }
        if (api != null) { getServer().getServicesManager().unregisterAll(this); api.shutdown(); api = null; }
        if (database != null) { database.close(); database = null; }
    }
}
