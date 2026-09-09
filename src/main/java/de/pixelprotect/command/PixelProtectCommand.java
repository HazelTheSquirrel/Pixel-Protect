package de.pixelprotect.command;

import de.pixelprotect.service.AsyncLogQueue;
import de.pixelprotect.service.InspectorService;
import de.pixelprotect.service.RollbackService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class PixelProtectCommand implements CommandExecutor {
    private final InspectorService inspector;
    private final RollbackService rollback;
    private final AsyncLogQueue queue;
    public PixelProtectCommand(InspectorService inspector, RollbackService rollback, AsyncLogQueue queue){this.inspector=inspector;this.rollback=rollback;this.queue=queue;}

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args){
        if(!sender.hasPermission("pixelprotect.admin")){sender.sendMessage("§cKeine Berechtigung.");return true;}
        if(args.length==0){sender.sendMessage("§6/pp inspector §7- Inspector umschalten");sender.sendMessage("§6/pp rollback <ID> §7- Forensik-Eintrag zurückrollen");sender.sendMessage("§6/pp status §7- Loggerstatus");return true;}
        switch(args[0].toLowerCase()){case "inspector","inspect" -> {
            if(!(sender instanceof Player p)){sender.sendMessage("§cDieser Befehl benötigt einen Spieler.");return true;}
            boolean enabled=inspector.toggle(p);p.sendMessage(enabled?"§aPixel-Protect Inspector aktiviert.":"§ePixel-Protect Inspector deaktiviert.");
        } case "rollback" -> {
            if(!(sender instanceof Player p)){sender.sendMessage("§cDieser Befehl benötigt einen Spieler.");return true;}
            if(args.length!=2){sender.sendMessage("§cVerwendung: /pp rollback <ID>");return true;}
            rollback.rollback(p,args[1]);
        } case "status" -> sender.sendMessage("§6Pixel-Protect §7Logger queue: §f"+queue.pending());
        default -> sender.sendMessage("§cUnbekannter Unterbefehl.");}
        return true;
    }
}
