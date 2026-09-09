package de.pixelprotect.service;

import de.pixelprotect.database.DatabaseManager;
import de.pixelprotect.model.Endpoint;
import de.pixelprotect.model.EndpointType;
import de.pixelprotect.util.ItemCodec;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class InspectorService {
    private static final String SEP="________________________________________________________________________________";
    private static final DateTimeFormatter FORMAT=DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).withZone(ZoneId.systemDefault());
    private final JavaPlugin plugin; private final DatabaseManager database; private final ItemCodec codec; private final java.util.Set<UUID> inspectors=java.util.concurrent.ConcurrentHashMap.newKeySet();
    public InspectorService(JavaPlugin plugin,DatabaseManager database,ItemCodec codec){this.plugin=plugin;this.database=database;this.codec=codec;}
    public boolean toggle(Player player){if(inspectors.remove(player.getUniqueId()))return false;inspectors.add(player.getUniqueId());return true;}
    public boolean isInspector(Player player){return inspectors.contains(player.getUniqueId());}
    public void interact(PlayerInteractEvent event){
        Player player=event.getPlayer(); if(!isInspector(player)||event.getAction()==Action.PHYSICAL)return; Block block=event.getClickedBlock(); if(block==null)return;
        event.setCancelled(true); String world=block.getWorld().getName(); int x=block.getX(),y=block.getY(),z=block.getZ();
        CompletableAsync.lookup(database,world,x,y,z).thenAccept(result->Bukkit.getScheduler().runTask(plugin,()->send(player,result.transfers(),result.blocks())));
    }
    private void send(Player player,List<DatabaseManager.StoredTransfer> transfers,List<DatabaseManager.StoredBlock> blocks){
        if(!player.isOnline())return; player.sendMessage(Component.text(SEP)); player.sendMessage(Component.text("Pixel-Protect Forensik: " + player.getLocation().getBlockX()+" / "+player.getLocation().getBlockY()+" / "+player.getLocation().getBlockZ()));
        if(transfers.isEmpty()&&blocks.isEmpty()){player.sendMessage(Component.text("Keine protokollierten Ereignisse gefunden."));player.sendMessage(Component.text(SEP));return;}
        for(DatabaseManager.StoredTransfer t:transfers){player.sendMessage(Component.text(formatTransfer(t)));player.sendMessage(Component.text(SEP));}
        for(DatabaseManager.StoredBlock b:blocks){player.sendMessage(Component.text(formatBlock(b)));player.sendMessage(Component.text(SEP));}
    }
    public static String formatTransfer(DatabaseManager.StoredTransfer t){
        String actor=t.actorName()==null?"Unbekannt":t.actorName(); String item=t.itemKey().split(":",3).length>1?t.itemKey().split(":",3)[1]:t.itemKey();
        String direction=t.source().type()==EndpointType.PLAYER?"vom Inventar in":"aus";
        String text;
        if(t.destination().type()==EndpointType.PLAYER)text=actor+" hat "+t.amount()+" "+item+" aus "+t.source().label()+" in sein Inventar genommen.";
        else if(t.source().type()==EndpointType.PLAYER)text=actor+" hat "+t.amount()+" "+item+" vom Inventar in "+t.destination().label()+" gelegt.";
        else text=t.amount()+" "+item+" wurden automatisch von "+t.source().label()+" nach "+t.destination().label()+" transportiert.";
        String attribution=t.attributionName()==null?"nicht festgestellt":t.attributionName();
        return text+"\nQuelle: "+endpoint(t.source())+"\nZiel: "+endpoint(t.destination())+"\nZeitpunkt: "+FORMAT.format(t.timestamp())+"\nTransportsystem platziert von: "+attribution+"\nTransaction: "+t.transactionId()+"\nChain: "+t.chainId();
    }
    public static String formatBlock(DatabaseManager.StoredBlock b){return b.playerName()+" hat Block "+b.action().toLowerCase(Locale.GERMANY)+" ("+b.blockType()+") bei X: "+b.x()+" Y: "+b.y()+" Z: "+b.z()+".\nZeitpunkt: "+FORMAT.format(b.timestamp())+"\nTransaction: "+b.transactionId();}
    private static String endpoint(Endpoint e){if(e.world()==null)return e.label();return e.label()+" @ X: "+e.x()+" Y: "+e.y()+" Z: "+e.z();}
    private record Lookup(List<DatabaseManager.StoredTransfer> transfers,List<DatabaseManager.StoredBlock> blocks){}
    private static final class CompletableAsync{
        static java.util.concurrent.CompletableFuture<Lookup> lookup(DatabaseManager db,String world,int x,int y,int z){return java.util.concurrent.CompletableFuture.supplyAsync(()->{try{Instant since=Instant.now().minus(Duration.ofDays(3650));return new Lookup(db.findTransfers(world,x,y,z,1.5,since,50),db.findBlocks(world,x,y,z,1.5,since,50));}catch(Exception e){throw new RuntimeException(e);}});}
    }
}
