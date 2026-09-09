package de.pixelprotect.service;

import de.pixelprotect.database.DatabaseManager;
import de.pixelprotect.model.BlockLog;
import de.pixelprotect.model.Endpoint;
import de.pixelprotect.model.EndpointType;
import de.pixelprotect.model.TransferLog;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class InspectorService {
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final DatabaseManager database;
    private final int limit;
    private final Set<UUID> enabled = ConcurrentHashMap.newKeySet();

    public InspectorService(org.bukkit.plugin.java.JavaPlugin plugin, DatabaseManager database, int limit) { this.plugin=plugin;this.database=database;this.limit=limit; }
    public boolean toggle(Player player) { if (enabled.remove(player.getUniqueId())) return false; enabled.add(player.getUniqueId()); return true; }
    public boolean isEnabled(Player player) { return enabled.contains(player.getUniqueId()); }
    public void disable(Player player) { enabled.remove(player.getUniqueId()); }

    public void inspect(Player player, Block block) {
        if (!isEnabled(player)) return;
        String world=block.getWorld().getName(); int x=block.getX(),y=block.getY(),z=block.getZ();
        player.sendMessage(Component.text("Pixel-Protect: Datenbankabfrage läuft …", NamedTextColor.GRAY));
        CompletableLookup lookup=new CompletableLookup(database,world,x,y,z,limit);
        java.util.concurrent.CompletableFuture.supplyAsync(lookup::load).thenAccept(result -> Bukkit.getScheduler().runTask(plugin,()->send(player,result)));
    }

    private void send(Player player, Result result) {
        if (!player.isOnline()) return;
        if (result.error!=null) { player.sendMessage(Component.text("Pixel-Protect: Lookup fehlgeschlagen: "+result.error.getMessage(),NamedTextColor.RED)); return; }
        if (result.blocks.isEmpty()&&result.transfers.isEmpty()) { player.sendMessage(Component.text("Pixel-Protect: Keine Forensik-Einträge gefunden.",NamedTextColor.YELLOW)); return; }
        player.sendMessage(Component.text("Pixel-Protect Forensik",NamedTextColor.GOLD));
        Map<UUID,Tx> grouped=new LinkedHashMap<>();
        result.blocks.forEach(b->grouped.computeIfAbsent(b.log().transactionId(),k->new Tx(k,new ArrayList<>(),new ArrayList<>())).blocks.add(b));
        result.transfers.forEach(t->grouped.computeIfAbsent(t.log().transactionId(),k->new Tx(k,new ArrayList<>(),new ArrayList<>())).transfers.add(t));
        grouped.values().stream().sorted(Comparator.comparing(Tx::timestamp).reversed()).limit(limit).forEach(tx->{
            player.sendMessage(Component.text("["+rollbackId(tx.id)+"]",NamedTextColor.AQUA));
            tx.blocks.forEach(b->sendBlock(player,b.log()));
            tx.transfers.forEach(t->sendTransfer(player,t.log()));
        });
    }

    private void sendBlock(Player p, BlockLog l) {
        String verb=l.action().equals("BREAK")?"abgebaut":"platziert";
        p.sendMessage(Component.text(l.playerName()+" hat "+l.blockType()+" "+verb+".",NamedTextColor.WHITE));
        p.sendMessage(Component.text("  "+l.world()+" "+l.x()+" "+l.y()+" "+l.z(),NamedTextColor.GRAY));
    }

    private void sendTransfer(Player p, TransferLog l) {
        String actor=l.actorName()==null?"UNKNOWN":l.actorName();
        String item=l.itemKey().replace("minecraft:","");
        String sentence;
        if(l.source().type()==EndpointType.PLAYER&&l.destination().type()!=EndpointType.PLAYER)
            sentence=actor+" hat "+l.amount()+" "+item+" vom Inventar in "+label(l.destination())+" gelegt.";
        else if(l.destination().type()==EndpointType.PLAYER&&l.source().type()!=EndpointType.PLAYER)
            sentence=actor+" hat "+l.amount()+" "+item+" aus "+label(l.source())+" ins Inventar gelegt.";
        else sentence=actor+" hat "+l.amount()+" "+item+" von "+label(l.source())+" nach "+label(l.destination())+" bewegt.";
        p.sendMessage(Component.text(sentence,NamedTextColor.WHITE));
        p.sendMessage(Component.text("  "+location(l.source())+" -> "+location(l.destination()),NamedTextColor.GRAY));
    }

    private static String label(Endpoint e){return e.label()!=null?e.label():e.type().name().toLowerCase(Locale.ROOT);}
    private static String location(Endpoint e){return e.world()==null?label(e):e.world()+" "+e.x()+" "+e.y()+" "+e.z();}
    public static String rollbackId(UUID tx){return "#"+tx.toString().replace("-","").substring(0,5).toUpperCase(Locale.ROOT);}

    private static final class Tx {
        private final UUID id; private final List<DatabaseManager.StoredTransfer> transfers; private final List<DatabaseManager.StoredBlock> blocks;
        private Tx(UUID id,List<DatabaseManager.StoredTransfer> transfers,List<DatabaseManager.StoredBlock> blocks){this.id=id;this.transfers=transfers;this.blocks=blocks;}
        private UUID id(){return id;}
        private Instant timestamp(){Instant latest=Instant.MIN;for(var b:blocks)if(b.log().timestamp().isAfter(latest))latest=b.log().timestamp();for(var t:transfers)if(t.log().timestamp().isAfter(latest))latest=t.log().timestamp();return latest;}
    }
    private static final class CompletableLookup {
        private final DatabaseManager db; private final String world; private final int x,y,z,limit;
        private CompletableLookup(DatabaseManager db,String world,int x,int y,int z,int limit){this.db=db;this.world=world;this.x=x;this.y=y;this.z=z;this.limit=limit;}
        private Result load(){try{return new Result(db.inspectBlocks(world,x,y,z,1.5,Instant.now().minus(Duration.ofDays(30)),limit),db.inspectTransfers(world,x,y,z,1.5,Instant.now().minus(Duration.ofDays(30)),limit),null);}catch(Exception e){return new Result(List.of(),List.of(),e);}}
    }
    private record Result(List<DatabaseManager.StoredBlock> blocks,List<DatabaseManager.StoredTransfer> transfers,Exception error) {}
}
