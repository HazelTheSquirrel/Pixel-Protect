package de.pixelprotect.command;

import de.pixelprotect.database.DatabaseManager;
import de.pixelprotect.service.InspectorService;
import de.pixelprotect.service.RollbackService;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;

public final class PixelProtectCommand implements BasicCommand {
    private final InspectorService inspector;
    private final RollbackService rollback;

    public PixelProtectCommand(DatabaseManager database, InspectorService inspector, RollbackService rollback) {
        this.inspector = inspector;
        this.rollback = rollback;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage(Component.text("Pixel-Protect: Dieser Befehl ist fuer Spieler vorgesehen."));
            return;
        }
        if (args.length == 0) {
            help(player);
            return;
        }
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "inspector", "inspect" -> player.sendMessage(Component.text(
                    "Pixel-Protect Inspector: " + (inspector.toggle(player) ? "AKTIVIERT" : "DEAKTIVIERT")));
            case "rollback" -> {
                if (args.length != 2) {
                    player.sendMessage(Component.text("Syntax: /pp rollback <Rollback-ID>"));
                    return;
                }
                rollback.rollback(player, args[1]);
            }
            case "status" -> player.sendMessage(Component.text("Pixel-Protect: Datenbank aktiv; Forensik-Logging laeuft."));
            default -> help(player);
        }
    }

    private void help(Player player) {
        player.sendMessage(Component.text("/pp inspector | /pp rollback <Rollback-ID> | /pp status"));
    }

    @Override
    public String permission() {
        return "pixelprotect.admin";
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) return List.of("inspector", "rollback", "status");
        return List.of();
    }
}
