package de.pixelprotect.service;

import de.pixelprotect.model.Endpoint;
import de.pixelprotect.model.Owner;
import de.pixelprotect.model.TransferLog;
import de.pixelprotect.util.EndpointResolver;
import de.pixelprotect.util.ItemCodec;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.DragType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class TransferService {
    private final AsyncLogQueue queue; private final OwnershipService ownership;
    public TransferService(org.bukkit.plugin.java.JavaPlugin plugin,AsyncLogQueue queue,OwnershipService ownership){this.queue=queue;this.ownership=ownership;}

    public void playerClick(InventoryClickEvent event){
        if(event.isCancelled())return;Player player=(Player)event.getWhoClicked();Inventory clicked=event.getClickedInventory();if(clicked==null)return;
        Endpoint playerEndpoint=Endpoint.player(player.getUniqueId(),player.getName()),clickedEndpoint=EndpointResolver.resolve(clicked);if(clickedEndpoint==null)return;
        ItemStack current=event.getCurrentItem(),cursor=event.getCursor();InventoryAction action=event.getAction();ItemStack candidate=current!=null&&!current.isEmpty()?current.clone():cursor!=null&&!cursor.isEmpty()?cursor.clone():null;if(candidate==null)return;
        int amount=movedAmount(action,current,cursor,clicked);if(amount<=0)return;Endpoint source,destination;
        if(action==InventoryAction.MOVE_TO_OTHER_INVENTORY){if(clickedEndpoint.type()==de.pixelprotect.model.EndpointType.PLAYER){source=playerEndpoint;destination=EndpointResolver.resolve(event.getView().getTopInventory());}else{source=clickedEndpoint;destination=playerEndpoint;}if(destination==null)return;}
        else if(isPickup(action)){if(clickedEndpoint.type()==de.pixelprotect.model.EndpointType.PLAYER)return;source=clickedEndpoint;destination=playerEndpoint;}
        else if(isPlace(action)){if(clickedEndpoint.type()==de.pixelprotect.model.EndpointType.PLAYER)return;source=playerEndpoint;destination=clickedEndpoint;}
        else if(action==InventoryAction.SWAP_WITH_CURSOR){if(clickedEndpoint.type()==de.pixelprotect.model.EndpointType.PLAYER)return;source=playerEndpoint;destination=clickedEndpoint;}
        else return;
        submitPlayerTransfer(player,source,destination,candidate,amount,"PLAYER_TRANSFER");
    }

    public void playerDrag(InventoryDragEvent event){
        if(event.isCancelled())return;Player player=(Player)event.getWhoClicked();ItemStack oldCursor=event.getOldCursor();if(oldCursor==null||oldCursor.isEmpty()||event.getNewItems().isEmpty())return;
        int amount=event.getNewItems().values().stream().filter(s->s!=null&&!s.isEmpty()).mapToInt(ItemStack::getAmount).sum();if(amount<=0)return;Inventory target=null;
        for(Integer raw:event.getRawSlots()){Inventory inv=event.getView().getInventory(raw);if(inv!=null&&inv!=event.getView().getBottomInventory()){target=inv;break;}}
        if(target==null)return;Endpoint destination=EndpointResolver.resolve(target);if(destination==null||destination.type()==de.pixelprotect.model.EndpointType.PLAYER)return;
        submitPlayerTransfer(player,Endpoint.player(player.getUniqueId(),player.getName()),destination,oldCursor.clone(),amount,event.getType()==DragType.EVEN?"PLAYER_DRAG":"PLAYER_DRAG_SINGLE");
    }

    public void automatedMove(InventoryMoveItemEvent event){
        if(event.isCancelled())return;ItemStack item=event.getItemStack();if(item==null||item.isEmpty())return;Endpoint source=EndpointResolver.resolve(event.getSource()),destination=EndpointResolver.resolve(event.getDestination()),initiator=EndpointResolver.resolve(event.getInitiator());if(source==null||destination==null)return;
        UUID tx=UUID.randomUUID();Instant now=Instant.now();CompletableFuture.runAsync(()->{Owner owner=initiator==null?null:ownership.load(initiator);if(owner==null)owner=ownership.load(source);if(owner==null)owner=ownership.load(destination);submitAutomated(tx,now,owner,source,destination,item);});
    }

    public void groundToInventory(InventoryPickupItemEvent event){
        if(event.isCancelled())return;Item item=event.getItem();if(item==null||item.isDead())return;ItemStack stack=item.getItemStack();Endpoint destination=EndpointResolver.resolve(event.getInventory());if(destination==null)return;Endpoint source=ground(item);Instant now=Instant.now();UUID tx=UUID.randomUUID();
        CompletableFuture.runAsync(()->{Owner owner=ownership.load(destination);submitAutomated(tx,now,owner,source,destination,stack);});
    }

    public void groundToPlayer(EntityPickupItemEvent event){
        if(event.isCancelled()||!(event.getEntity() instanceof Player player))return;Item item=event.getItem();if(item==null||item.isDead())return;ItemStack stack=item.getItemStack();Endpoint source=ground(item),destination=Endpoint.player(player.getUniqueId(),player.getName());submitPlayerTransfer(player,source,destination,stack.clone(),stack.getAmount(),"GROUND_PICKUP");
    }

    private void submitAutomated(UUID tx,Instant now,Owner owner,Endpoint source,Endpoint destination,ItemStack item){UUID actorUuid=owner==null?null:owner.uuid();String actorName=owner==null?"UNKNOWN":owner.name();queue.submitTransfer(new TransferLog(tx,now,actorUuid,actorName,actorUuid,actorName,source,destination,ItemCodec.key(item),ItemCodec.encode(item),item.getAmount(),"AUTOMATED_TRANSFER"));}
    private static Endpoint ground(Item item){var l=item.getLocation();return Endpoint.block(de.pixelprotect.model.EndpointType.GROUND,l.getWorld().getName(),l.getBlockX(),l.getBlockY(),l.getBlockZ(),"ground");}
    private void submitPlayerTransfer(Player player,Endpoint source,Endpoint destination,ItemStack item,int amount,String action){if(amount<=0)return;item.setAmount(Math.min(amount,item.getMaxStackSize()));queue.submitTransfer(new TransferLog(UUID.randomUUID(),Instant.now(),player.getUniqueId(),player.getName(),player.getUniqueId(),player.getName(),source,destination,ItemCodec.key(item),ItemCodec.encode(item),amount,action));}
    private static boolean isPickup(InventoryAction action){return switch(action){case PICKUP_ALL,PICKUP_HALF,PICKUP_ONE,PICKUP_SOME,COLLECT_TO_CURSOR->true;default->false;};}
    private static boolean isPlace(InventoryAction action){return switch(action){case PLACE_ALL,PLACE_ONE,PLACE_SOME->true;default->false;};}
    private static int movedAmount(InventoryAction action,ItemStack current,ItemStack cursor,Inventory clicked){int currentAmount=current==null||current.isEmpty()?0:current.getAmount(),cursorAmount=cursor==null||cursor.isEmpty()?0:cursor.getAmount();return switch(action){case PICKUP_ALL->currentAmount;case PICKUP_HALF->(currentAmount+1)/2;case PICKUP_ONE->Math.min(1,currentAmount);case PICKUP_SOME->Math.max(0,Math.min(currentAmount,Math.max(0,current.getMaxStackSize()-cursorAmount)));case PLACE_ALL->cursorAmount;case PLACE_ONE->Math.min(1,cursorAmount);case PLACE_SOME->Math.max(0,Math.min(cursorAmount,Math.max(0,clicked.getMaxStackSize()-currentAmount)));case MOVE_TO_OTHER_INVENTORY->currentAmount;case COLLECT_TO_CURSOR->currentAmount;case SWAP_WITH_CURSOR->cursorAmount;default->0;};}
}
