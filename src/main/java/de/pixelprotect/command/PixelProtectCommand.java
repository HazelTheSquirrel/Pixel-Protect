package de.pixelprotect.command;

import de.pixelprotect.database.DatabaseManager;
import de.pixelprotect.service.AsyncLogQueue;
import de.pixelprotect.service.InspectorService;
import de.pixelprotect.service.RollbackService;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;

public final class PixelProtectCommand implements BasicCommand {
    private final DatabaseManager database;private final InspectorService inspector;private final RollbackService rollback;private final AsyncLogQueue queue;
    public PixelProtectCommand(DatabaseManager database,InspectorService inspector,RollbackService rollback,AsyncLogQueue queue){this.database=database;this.inspector=inspector;this.rollback=rollback;this.queue=queue;}
    @Override public void execute(CommandSourceStack source,String[]args){if(!(source.getSender() instanceof Player p)){source.getSender().sendMessage(Component.text("Pixel-Protect: Dieser Befehl ist für Spieler vorgesehen.",NamedTextColor.RED));return;}if(args.length==0){help(p);return;}switch(args[0].toLowerCase(java.util.Locale.ROOT)){case "inspector","inspect"->p.sendMessage(Component.text("Pixel-Protect Inspector: "+(inspector.toggle(p)?"AKTIVIERT":"DEAKTIVIERT"),NamedTextColor.GOLD));case "rollback"->{if(args.length!=2){p.sendMessage(Component.text("Syntax: /pp rollback <Rollback-ID>",NamedTextColor.RED));return;}rollback.rollback(p,args[1]);}case "status"->p.sendMessage(Component.text("Pixel-Protect: Speicher aktiv; Queue="+queue.pending()+"; Worker="+(queue.healthy()?"OK":"FEHLER"),queue.healthy()?NamedTextColor.GREEN:NamedTextColor.RED));default->help(p);}}
    private void help(Player p){p.sendMessage(Component.text("/pp inspector | /pp rollback <Rollback-ID> | /pp status",NamedTextColor.GRAY));}
    @Override public String permission(){return "pixelprotect.admin";}
    @Override public Collection<String>suggest(CommandSourceStack source,String[]args){if(args.length<=1)return List.of("inspector","rollback","status");return List.of();}
}
