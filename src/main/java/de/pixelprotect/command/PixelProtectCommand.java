package de.pixelprotect.command;

import de.pixelprotect.database.DatabaseManager;
import de.pixelprotect.service.InspectorService;
import de.pixelprotect.service.RollbackService;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class PixelProtectCommand implements BasicCommand {
    private final DatabaseManager database;
    private final InspectorService inspector;
    private final RollbackService rollback;

    public PixelProtectCommand(DatabaseManager database, InspectorService inspector, RollbackService rollback) {
        this.database = database;
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
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "inspector", "inspect" -> player.sendMessage(Component.text("Pixel-Protect Inspector: " + (inspector.toggle(player) ? "AKTIVIERT" : "DEAKTIVIERT")));
                case "rollback" -> {
                    if (args.length < 3) {
                        player.sendMessage(Component.text("Syntax: /pp rollback <radius> <zeit>"));
                        return;
                    }
                    rollback.rollback(player, Double.parseDouble(args[1]), args[2]);
                }
                case "lookup" -> lookup(player, args.length > 1 ? Double.parseDouble(args[1]) : 5, args.length > 2 ? args[2] : "1h");
                case "status" -> player.sendMessage(Component.text("Pixel-Protect: Datenbank aktiv; Forensik-Logging laeuft."));
                default -> help(player);
            }
        } catch (Exception exception) {
            player.sendMessage(Component.text("Pixel-Protect: " + exception.getMessage()));
        }
    }

    private void lookup(Player player, double radius, String rawTime) {
        Duration duration = RollbackService.parseDuration(rawTime);
        Instant since = Instant.now().minus(duration);
        String world = player.getWorld().getName();
        int x = player.getLocation().getBlockX(), y = player.getLocation().getBlockY(), z = player.getLocation().getBlockZ();
        player.sendMessage(Component.text("________________________________________________________________________________"));
        player.sendMessage(Component.text("Pixel-Protect Lookup wird asynchron ausgefuehrt..."));
        CompletableFuture.supplyAsync(() -> {
            try {
                return new Result(database.findTransfers(world, x, y, z, radius, since, 200), database.findBlocks(world, x, y, z, radius, since, 200));
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }).thenAccept(result -> Bukkit.getScheduler().runTask(org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(getClass()), () -> {
            player.sendMessage(Component.text("Ergebnisse: " + result.transfers().size() + " Transfers, " + result.blocks().size() + " Blockaenderungen."));
            var grouped = new java.util.LinkedHashMap<java.util.UUID, java.util.List<DatabaseManager.StoredTransfer>>();
            for (DatabaseManager.StoredTransfer transfer : result.transfers()) {
                grouped.computeIfAbsent(transfer.transactionId(), ignored -> new java.util.ArrayList<>()).add(transfer);
            }
            for (var transfers : grouped.values()) {
                player.sendMessage(inspector.formatTransferGroup(transfers));
                player.sendMessage(Component.text("________________________________________________________________________________"));
            }
            for (DatabaseManager.StoredBlock block : result.blocks()) {
                player.sendMessage(inspector.formatBlock(block));
                player.sendMessage(Component.text("________________________________________________________________________________"));
            }
        })).exceptionally(exception -> {
            player.sendMessage(Component.text("Lookup fehlgeschlagen: " + (exception.getCause() == null ? exception.getMessage() : exception.getCause().getMessage())));
            return null;
        });
    }

    private void help(Player player) {
        player.sendMessage(Component.text("/pp inspector | /pp lookup [radius] [zeit] | /pp rollback <radius> <zeit> | /pp status"));
    }

    @Override
    public String permission() {
        return "pixelprotect.admin";
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        return args.length <= 1 ? List.of("inspector", "lookup", "rollback", "status") : List.of();
    }

    private record Result(List<DatabaseManager.StoredTransfer> transfers, List<DatabaseManager.StoredBlock> blocks) { }
}
