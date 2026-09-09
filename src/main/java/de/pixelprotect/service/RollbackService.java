package de.pixelprotect.service;

import de.pixelprotect.database.DatabaseManager;
import de.pixelprotect.model.Endpoint;
import de.pixelprotect.model.EndpointType;
import de.pixelprotect.util.ItemCodec;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class RollbackService {
    private final JavaPlugin plugin;
    private final DatabaseManager database;
    public RollbackService(JavaPlugin plugin, DatabaseManager database) { this.plugin=plugin;this.database=database; }

    public void rollback(Player requester, String rawId) {
        requester.sendMessage("§7Pixel-Protect: Rollback-ID wird asynchron geladen …");
        CompletableFuture.supplyAsync(()->{
            try{return database.findRollback(rawId);}catch(Exception e){throw new RuntimeException(e);}
        }).thenAccept(lookup->Bukkit.getScheduler().runTask(plugin,()->apply(requester,lookup))).exceptionally(error->{Bukkit.getScheduler().runTask(plugin,()->requester.sendMessage("§cPixel-Protect: "+root(error).getMessage()));return null;});
    }

    private void apply(Player requester, DatabaseManager.RollbackLookup lookup) {
        if(!lookup.found()){requester.sendMessage("§ePixel-Protect: Kein Eintrag für "+lookup.id()+" gefunden.");return;}
        int restored=0,conflicts=0;
        List<DatabaseManager.StoredBlock> blocks=new ArrayList<>(lookup.blocks());
        blocks.sort(Comparator.comparing((DatabaseManager.StoredBlock b)->b.log().timestamp()).reversed());
        for(var stored:blocks){
            var log=stored.log();
            World world=Bukkit.getWorld(log.world());
            if(world==null){conflicts++;continue;}
            Block block=world.getBlockAt(log.x(),log.y(),log.z());
            String current=block.getBlockData().getAsString();
            if(!current.equals(log.afterData())){conflicts++;continue;}
            try{block.setBlockData(Bukkit.createBlockData(log.beforeData()),true);restored++;}catch(IllegalArgumentException ex){conflicts++;}
        }
        List<DatabaseManager.StoredTransfer> transfers=new ArrayList<>(lookup.transfers());
        transfers.sort(Comparator.comparing((DatabaseManager.StoredTransfer t)->t.log().timestamp()).reversed());
        for(var stored:transfers){
            var log=stored.log();
            Inventory source=inventory(log.source());
            Inventory destination=inventory(log.destination());
            if(source==null||destination==null){conflicts++;continue;}
            ItemStack item;
            try{item=ItemCodec.decode(log.itemData());}catch(RuntimeException ex){conflicts++;continue;}
            item.setAmount(log.amount());
            if(!canFit(source,item)){conflicts++;continue;}
            if(!removeExact(destination,item)){conflicts++;continue;}
            if(!addExact(source,item)){addExact(destination,item);conflicts++;continue;}
            restored++;
        }
        if(restored>0&&conflicts==0){try{database.markRolledBack(lookup.transactionId());}catch(Exception ignored){}}
        requester.sendMessage("§6Pixel-Protect Rollback "+lookup.id()+" §7→ §aRESTORED §f"+restored+" §7/ §cCONFLICT §f"+conflicts);
    }

    private Inventory inventory(Endpoint endpoint){
        try{
            if(endpoint.type()==EndpointType.PLAYER){Player p=endpoint.playerId()==null?null:Bukkit.getPlayer(endpoint.playerId());return p==null?null:p.getInventory();}
            if(endpoint.type()==EndpointType.MINECART){Entity e=endpoint.entityId()==null?null:Bukkit.getEntity(endpoint.entityId());return e instanceof InventoryHolder h?h.getInventory():null;}
            if(endpoint.world()==null)return null;
            World w=Bukkit.getWorld(endpoint.world());if(w==null)return null;
            Block exact=w.getBlockAt(endpoint.x(),endpoint.y(),endpoint.z());
            if(exact.getState() instanceof InventoryHolder h)return h.getInventory();
            for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++){
                Block b=w.getBlockAt(endpoint.x()+dx,endpoint.y()+dy,endpoint.z()+dz);
                if(b.getState() instanceof InventoryHolder h)return h.getInventory();
            }
        }catch(RuntimeException ignored){}
        return null;
    }

    private boolean canFit(Inventory inv,ItemStack item){int needed=item.getAmount();for(ItemStack stack:inv.getStorageContents()){int max=Math.min(inv.getMaxStackSize(),item.getMaxStackSize());if(stack==null||stack.isEmpty())needed-=max;else if(stack.isSimilar(item))needed-=Math.max(0,max-stack.getAmount());if(needed<=0)return true;}return false;}
    private boolean removeExact(Inventory inv,ItemStack item){int left=item.getAmount();ItemStack[] contents=inv.getStorageContents();for(int i=0;i<contents.length&&left>0;i++){ItemStack stack=contents[i];if(stack==null||stack.isEmpty()||!stack.isSimilar(item))continue;int take=Math.min(left,stack.getAmount());int remain=stack.getAmount()-take;if(remain==0)contents[i]=null;else{ItemStack clone=stack.clone();clone.setAmount(remain);contents[i]=clone;}left-=take;}if(left>0)return false;inv.setStorageContents(contents);return true;}
    private boolean addExact(Inventory inv,ItemStack item){int left=item.getAmount();ItemStack[] contents=inv.getStorageContents();int max=Math.min(inv.getMaxStackSize(),item.getMaxStackSize());for(int i=0;i<contents.length&&left>0;i++){ItemStack stack=contents[i];if(stack==null||stack.isEmpty()||!stack.isSimilar(item))continue;int add=Math.min(left,max-stack.getAmount());if(add>0){ItemStack clone=stack.clone();clone.setAmount(stack.getAmount()+add);contents[i]=clone;left-=add;}}for(int i=0;i<contents.length&&left>0;i++){if(contents[i]!=null&&!contents[i].isEmpty())continue;int add=Math.min(left,max);ItemStack clone=item.clone();clone.setAmount(add);contents[i]=clone;left-=add;}if(left>0)return false;inv.setStorageContents(contents);return true;}
    private static Throwable root(Throwable t){Throwable r=t;while(r.getCause()!=null)r=r.getCause();return r;}
}
