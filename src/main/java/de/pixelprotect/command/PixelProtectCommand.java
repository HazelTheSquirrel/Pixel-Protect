package de.pixelprotect.command;

import de.pixelprotect.service.AsyncLogQueue;
import de.pixelprotect.service.InspectorService;
import de.pixelprotect.service.RollbackService;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public final class PixelProtectCommand implements BasicCommand {
    private final InspectorService inspector;
    private final RollbackService rollback;
    private final AsyncLogQueue queue;
    public PixelProtectCommand(InspectorService inspector, RollbackService rollback, AsyncLogQueue queue){this.inspector=inspector;this.rollback=rollback;this.queue=queue;}

    @Override public String permission(){return "pixelprotect.admin";}

    @Override public void execute(CommandSourceStack source,String[] args){
        CommandSender sender=source.getSender();
        if(args.length==0){help(sender);return;}
        switch(args[0].toLowerCase()){
            case "inspector","inspect" -> {
                if(!(sender instanceof Player p)){sender.sendMessage("§cDieser Befehl benötigt einen Spieler.");return;}
                boolean enabled=inspector.toggle(p);p.sendMessage(enabled?"§aPixel-Protect Inspector aktiviert.":"§ePixel-Protect Inspector deaktiviert.");
            }
            case "rollback" -> {
                if(!(sender instanceof Player p)){sender.sendMessage("§cDieser Befehl benötigt einen Spieler.");return;}
                if(args.length!=2){sender.sendMessage("§cVerwendung: /pp rollback <ID>");return;}
                rollback.rollback(p,args[1]);
            }
            case "status" -> sender.sendMessage("§6Pixel-Protect §7Logger queue: §f"+queue.pending());
            default -> help(sender);
        }
    }

    @Override public List<String> suggest(CommandSourceStack source,String[] args){
        if(args.length==1)return List.of("inspector","rollback","status");
        if(args.length==2&&args[0].equalsIgnoreCase("rollback"))return List.of("#ID");
        return List.of();
    }

    private static void help(CommandSender sender){sender.sendMessage("§6/pp inspector §7- Inspector umschalten");sender.sendMessage("§6/pp rollback <ID> §7- Forensik-Eintrag zurückrollen");sender.sendMessage("§6/pp status §7- Loggerstatus");}
}
