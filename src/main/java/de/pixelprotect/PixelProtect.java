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
    private DatabaseManager database;private AsyncLogQueue queue;
    @Override public void onEnable(){saveDefaultConfig();try{String mode=getConfig().getString("storage.mode","local");database=new DatabaseManager(Path.of(getDataFolder().getPath(),"logs"),mode,getConfig().getBoolean("storage.fallback-to-local",true),getConfig().getString("mysql.host","127.0.0.1"),getConfig().getInt("mysql.port",3306),getConfig().getString("mysql.database","pixelprotect"),getConfig().getString("mysql.username","pixelprotect"),getConfig().getString("mysql.password","change-me"),getConfig().getInt("mysql.pool-size",8));database.initialize();queue=new AsyncLogQueue(database,getConfig().getInt("logging.queue-capacity",100000),t->getLogger().severe("Forensic logging failure: "+t));Gson gson=new GsonBuilder().disableHtmlEscaping().create();ItemCodec codec=new ItemCodec(gson);OwnershipService ownership=new OwnershipService(database,queue);TransferService transfers=new TransferService(this,database,queue,ownership,codec);WorldAuditService world=new WorldAuditService(this,database,queue,ownership);InspectorService inspector=new InspectorService(this,database,codec);RollbackService rollback=new RollbackService(this,database,codec);getServer().getPluginManager().registerEvents(new ForensicListener(transfers,world,inspector),this);getLifecycleManager().registerEventHandler(io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,e->e.registrar().register("pp",new PixelProtectCommand(database,inspector,rollback,queue)));getLogger().info("Pixel-Protect forensic system enabled.");}catch(Exception e){getLogger().severe("Pixel-Protect startup failed: "+e);if(queue!=null)try{queue.close();}catch(Exception ignored){}if(database!=null)database.close();getServer().getPluginManager().disablePlugin(this);}}
    @Override public void onDisable(){if(queue!=null)try{queue.close();}catch(Exception e){getLogger().severe("Forensic queue shutdown failed: "+e);}if(database!=null)database.close();}
}
