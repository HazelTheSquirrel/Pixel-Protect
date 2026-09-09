package de.pixelprotect.service;

import de.pixelprotect.database.DatabaseManager;
import de.pixelprotect.model.BlockLog;
import de.pixelprotect.model.Endpoint;
import de.pixelprotect.model.TransferLog;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
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
        CompletableLookup lookup = new CompletableLookup(database, world,x,y,z,limit);
        java.util.concurrent.CompletableFuture.supplyAsync(lookup::load).thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> send(player,result)));
    }

    private void send(Player player, Result result) {
        if (!player.isOnline()) return;
        if (result.error != null) { player.sendMessage(Component.text("Pixel-Protect: Lookup fehlgeschlagen: "+result.error.getMessage(),NamedTextColor.RED)); return; }
        if (result.blocks.isEmpty() && result.transfers.isEmpty()) { player.sendMessage(Component.text("Pixel-Protect: Keine Forensik-Einträge gefunden.",NamedTextColor.YELLOW)); return; }
        player.sendMessage(Component.text("Pixel-Protect Forensik",NamedTextColor.GOLD));
        record Tx(UUID id, List<DatabaseManager.StoredTransfer> transfers, List<DatabaseManager.StoredBlock> blocks) {}
        Map<UUID,Tx> grouped=new LinkedHashMap<>();
        result.blocks.forEach(b -> grouped.computeIfAbsent(b.log().transactionId(),k->new Tx(k,new ArrayList<>(),new ArrayList<>())).blocks().add(b));
        result.transfers.forEach(t -> grouped.computeIfAbsent(t.log().transactionId(),k->new Tx(k,new ArrayList<>(),new ArrayList<>())).transfers().add(t));
        grouped.values().stream().sorted(Comparator.comparing(tx -> timestamp(tx), Comparator.reverseOrder())).limit(limit).forEach(tx -> {
            UUID id=tx.id(); player.sendMessage(Component.text("["+rollbackId(id)+"]",NamedTextColor.AQUA).append(Component.text(" ")));
            for (DatabaseManager.StoredBlock b:tx.blocks()) sendBlock(player,b.log());
            for (DatabaseManager.StoredTransfer t:tx.transfers()) sendTransfer(player,t.log());
        });
    }

    private static Instant timestamp(Object tx) { return tx instanceof Record r ? Instant.MIN : Instant.MIN; }
    private void sendBlock(Player p, BlockLog l) {
        String verb=l.action().equals("BREAK")?"abgebaut":"platziert";
        p.sendMessage(Component.text(l.playerName()+" hat "+l.blockType()+" "+verb+".",NamedTextColor.WHITE));
        p.sendMessage(Component.text("  "+l.world()+" "+l.x()+" "+l.y()+" "+l.z(),NamedTextColor.GRAY));
    }
    private void sendTransfer(Player p, TransferLog l) {
        String actor=l.actorName()==null?"UNKNOWN":l.actorName();
        String item=l.itemKey().replace("minecraft:","");
        String verb=l.source().type()==de.pixelprotect.model.EndpointType.PLAYER?"in":"aus";
        String prep=l.destination().type()==de.pixelprotect.model.EndpointType.PLAYER?"aus":"in";
        p.sendMessage(Component.text(actor+" hat "+l.amount()+" "+item+" "+(verb.equals("in")?"vom Inventar in ":"aus ")+label(l.destination())+" gelegt.",NamedTextColor.WHITE));
        p.sendMessage(Component.text("  "+location(l.source())+" -> "+location(l.destination())+"  "+verb+"/"+prep,NamedTextColor.GRAY));
    }
    private static String label(Endpoint e){return e.label()!=null?e.label():e.type().name().toLowerCase(Locale.ROOT);}
    private static String location(Endpoint e){return e.world()==null?label(e):e.world()+" "+e.x()+" "+e.y()+" "+e.z();}
    public static String rollbackId(UUID tx){return "#"+tx.toString().replace("-","").substring(0,5).toUpperCase(Locale.ROOT);}

    private static final class CompletableLookup {
        private final DatabaseManager db; private final String world; private final int x,y,z,limit;
        private CompletableLookup(DatabaseManager db,String world,int x,int y,int z,int limit){this.db=db;this.world=world;this.x=x;this.y=y;this.z=z;this.limit=limit;}
        private Result load(){try{return new Result(db.inspectBlocks(world,x,y,z,1.5,Instant.now().minus(Duration.ofDays(30)),limit),db.inspectTransfers(world,x,y,z,1.5,Instant.now().minus(Duration.ofDays(30)),limit),null);}catch(Exception e){return new Result(List.of(),List.of(),e);}}
    }
    private record Result(List<DatabaseManager.StoredBlock> blocks,List<DatabaseManager.StoredTransfer> transfers,Exception error) {}
}
