package de.pixelprotect.model;

import org.bukkit.Location;
import java.util.UUID;

public record Endpoint(EndpointType type,UUID entityId,UUID playerId,String world,int x,int y,int z,String label){
    public static Endpoint player(UUID uuid,String name,Location l){return new Endpoint(EndpointType.PLAYER,null,uuid,l.getWorld().getName(),l.getBlockX(),l.getBlockY(),l.getBlockZ(),name);}
    public static Endpoint block(Location l,String label){return new Endpoint(EndpointType.BLOCK_CONTAINER,null,null,l.getWorld().getName(),l.getBlockX(),l.getBlockY(),l.getBlockZ(),label);}
    public static Endpoint entity(UUID uuid,Location l,String label){return new Endpoint(EndpointType.MINECART,uuid,null,l.getWorld().getName(),l.getBlockX(),l.getBlockY(),l.getBlockZ(),label);}
    public static Endpoint ground(Location l){return ground(null,l);}
    public static Endpoint ground(UUID entity,Location l){return new Endpoint(EndpointType.GROUND,entity,null,l.getWorld().getName(),l.getBlockX(),l.getBlockY(),l.getBlockZ(),"Boden");}
    public static Endpoint system(){return new Endpoint(EndpointType.SYSTEM,null,null,null,0,0,0,"System");}
    public String identity(){return switch(type){case PLAYER->"player:"+playerId;case BLOCK_CONTAINER->"block:"+world+":"+x+":"+y+":"+z;case MINECART->"entity:"+entityId;case GROUND->entityId==null?"ground:"+world+":"+x+":"+y+":"+z:"+"none":"ground:"+entityId;case SYSTEM->"system";};}
}
