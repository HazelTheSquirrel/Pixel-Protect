package de.pixelprotect;

import de.pixelprotect.listener.*;
import de.pixelprotect.service.*;
import de.pixelprotect.storage.Database;
import de.pixelprotect.storage.MySqlDatabase;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class PixelProtect extends JavaPlugin {
    private Database database;
    private RollbackService rollback;
    private InspectService inspect;
    private AutomationTracker automation;
    private ApiService api;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        database = createDatabase();
        database.open();
        automation = new AutomationTracker();
        final AuditService audit = new AuditService(database, getLogger());
        inspect = new InspectService(database);
        rollback = new RollbackService(database, this, getLogger());
        api = new ApiService(database, audit, inspect, rollback);

        getServer().getPluginManager().registerEvents(new BlockAuditListener(this, audit), this);
        getServer().getPluginManager().registerEvents(new ContainerAuditListener(this, audit), this);
        getServer().getPluginManager().registerEvents(new EntityForensicsAuditListener(this, audit, getConfig().getBoolean("logging.entity-damage", true)), this);
        getServer().getPluginManager().registerEvents(new ExplosionAuditListener(this, audit), this);
        getServer().getPluginManager().registerEvents(new FluidAuditListener(this, audit), this);
        getServer().getPluginManager().registerEvents(new ItemEntityAuditListener(this, audit), this);
        getServer().getPluginManager().registerEvents(new PlayerActivityAuditListener(this, audit), this);
        getServer().getPluginManager().registerEvents(new PlayerMechanicsAuditListener(this, audit), this);
        getServer().getPluginManager().registerEvents(new ActivityAuditListener(this, audit), this);
        getServer().getPluginManager().registerEvents(new PlayerAuditListener(this, audit), this);
        getServer().getPluginManager().registerEvents(new PlayerTransactionAuditListener(audit), this);
        getServer().getPluginManager().registerEvents(new InventoryAuditListener(this, audit, automation), this);
        getServer().getPluginManager().registerEvents(new PlayerInventoryAuditListener(this, audit), this);
        getServer().getPluginManager().registerEvents(new ContainerProcessingAuditListener(this, audit), this);
        getServer().getPluginManager().registerEvents(new AutomationAuditListener(this, automation), this);
        getServer().getPluginManager().registerEvents(new InspectListener(this, inspect, automation), this);
        getServer().getPluginManager().registerEvents(new AdminCommandGuardListener(), this);

        final PixelProtectCommand command = new PixelProtectCommand(this, database, rollback, inspect, getConfig().getInt("rollback.max-hours", 168), getConfig().getInt("rollback.max-radius", 128), getConfig().getInt("rollback.max-records", 100_000));
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> commands.registrar().register(command.create().build(), "Protokolliert, untersucht und setzt Weltänderungen zurück"));

        if (getConfig().getBoolean("retention.enabled", true)) {
            final int days = Math.max(1, getConfig().getInt("retention.days", 30));
            final long interval = Math.max(1L, getConfig().getLong("retention.maintenance-interval-ticks", 24_000L));
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> database.purgeBefore(System.currentTimeMillis() - days * 86_400_000L).thenAccept(api::addRetentionPurged), 20L, interval);
        }
        if (getConfig().getBoolean("diagnostics.enabled", true)) {
            Diagnostics.schedule(this, database, api, getConfig().getLong("diagnostics.interval-ticks", 600L));
        }
    }

    private Database createDatabase() {
        final String backend = getConfig().getString("storage.backend", "sqlite");
        if ("mysql".equalsIgnoreCase(backend)) {
            return new MySqlDatabase(getConfig(), getLogger());
        }
        return new Database(getDataFolder().toPath().resolve(getConfig().getString("storage.file", "pixelprotect.db")),
                getConfig().getInt("storage.queue-capacity", 10_000),
                getConfig().getInt("storage.batch-size", 256),
                getConfig().getLong("storage.flush-interval-millis", 250L), getLogger());
    }

    @Override
    public void onDisable() {
        if (database != null) database.close();
    }
}
