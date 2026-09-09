package de.pixelprotect;

import de.pixelprotect.command.PixelProtectCommand;
import de.pixelprotect.database.DatabaseManager;
import de.pixelprotect.listener.ForensicListener;
import de.pixelprotect.service.AsyncLogQueue;
import de.pixelprotect.service.InspectorService;
import de.pixelprotect.service.OwnershipService;
import de.pixelprotect.service.RollbackService;
import de.pixelprotect.service.TransferService;
import de.pixelprotect.service.WorldForensicsService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

public final class PixelProtect extends JavaPlugin {
    private DatabaseManager database;
    private AsyncLogQueue queue;
    private InspectorService inspector;
    private RollbackService rollback;

    @Override public void onEnable(){
        saveDefaultConfig();
        database=new DatabaseManager(getDataFolder().toPath().resolve("storage"),getConfig().getString("storage.mode","local"),getConfig().getBoolean("storage.fallback-to-local",true),getConfig().getString("storage.mysql.host","127.0.0.1"),getConfig().getInt("storage.mysql.port",3306),getConfig().getString("storage.mysql.database","pixelprotect"),getConfig().getString("storage.mysql.username","pixelprotect"),getConfig().getString("storage.mysql.password","change-me"),getConfig().getInt("storage.mysql.pool-size",8));
        try{database.initialize();}catch(SQLException e){getLogger().severe("Database initialization failed: "+e.getMessage());Bukkit.getPluginManager().disablePlugin(this);return;}
        queue=new AsyncLogQueue(database,getConfig().getInt("logging.queue-capacity",50000));
        OwnershipService ownership=new OwnershipService(database,queue);
        inspector=new InspectorService(this,database,getConfig().getInt("logging.inspector-limit",12));
        rollback=new RollbackService(this,database);
        TransferService transfer=new TransferService(this,queue,ownership);
        WorldForensicsService world=new WorldForensicsService(queue);
        Bukkit.getPluginManager().registerEvents(new ForensicListener(world,transfer,inspector,ownership),this);
        PixelProtectCommand command=new PixelProtectCommand(inspector,rollback,queue);
        if(getCommand("pp")!=null)getCommand("pp").setExecutor(command);
        getLogger().info("Pixel-Protect forensic engine enabled. Storage="+(getConfig().getString("storage.mode","local")));
    }

    @Override public void onDisable(){
        if(queue!=null)queue.close();
        if(database!=null)database.close();
        getLogger().info("Pixel-Protect forensic engine stopped.");
    }
}
