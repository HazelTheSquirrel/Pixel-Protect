package de.pixelprotect.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import de.pixelprotect.model.AuditEntry;
import de.pixelprotect.model.AuditQuery;
import de.pixelprotect.service.InspectService;
import de.pixelprotect.service.InventoryDiffService;
import de.pixelprotect.service.MessageService;
import de.pixelprotect.service.RollbackService;
import de.pixelprotect.storage.Database;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PixelProtectCommand {
    private static final int PAGE_SIZE = 25;
    private static final int MAX_ITEMS_PER_ENTRY = 4;
    private final Plugin plugin;
    private final Database database;
    private final RollbackService rollback;
    private final InspectService inspect;
    private final int maxHours;
    private final int maxRadius;
    private final int maxRecords;

    public PixelProtectCommand(Plugin plugin, Database database, RollbackService rollback, InspectService inspect,
                               int maxHours, int maxRadius, int maxRecords) {
        this.plugin = plugin;
        this.database = database;
        this.rollback = rollback;
        this.inspect = inspect;
        this.maxHours = maxHours;
        this.maxRadius = maxRadius;
        this.maxRecords = maxRecords;
    }

    public LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("pixelprotect")
                .executes(c -> help(c.getSource().getSender()))
                .then(Commands.literal("help").executes(c -> help(c.getSource().getSender())))
                .then(Commands.literal("version").executes(c -> version(c.getSource().getSender())))
                .then(Commands.literal("status").requires(s -> s.getSender().hasPermission("pixelprotect.status"))
                        .executes(c -> status(c.getSource().getSender())))
                .then(Commands.literal("inspect").requires(s -> s.getSender().hasPermission("pixelprotect.inspect"))
                        .executes(c -> inspect(c.getSource().getSender())))
                .then(Commands.literal("check").requires(s -> s.getSender().hasPermission("pixelprotect.inspect"))
                        .executes(c -> inspect(c.getSource().getSender())))
                .then(lookupCommand("lookup"))
                .then(lookupCommand("log"))
                .then(nearCommand())
                .then(rollbackCommand())
                .then(restoreCommand("restore"))
                .then(restoreCommand("undo"))
                .then(Commands.literal("purge").requires(s -> s.getSender().hasPermission("pixelprotect.purge"))
                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 3650))
                                .executes(c -> purge(c.getSource().getSender(), IntegerArgumentType.getInteger(c, "days")))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> lookupCommand(String name) {
        return Commands.literal(name).requires(s -> s.getSender().hasPermission("pixelprotect.lookup"))
                .executes(c -> lookup(c.getSource(), 5, 1, ""))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, maxRadius))
                        .executes(c -> lookup(c.getSource(), IntegerArgumentType.getInteger(c, "radius"), 1, ""))
                        .then(Commands.argument("hours", IntegerArgumentType.integer(1, maxHours))
                                .executes(c -> lookup(c.getSource(), IntegerArgumentType.getInteger(c, "radius"), IntegerArgumentType.getInteger(c, "hours"), ""))
                                .then(Commands.argument("filter", StringArgumentType.greedyString())
                                        .executes(c -> lookup(c.getSource(), IntegerArgumentType.getInteger(c, "radius"), IntegerArgumentType.getInteger(c, "hours"), StringArgumentType.getString(c, "filter"))))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> nearCommand() {
        return Commands.literal("near").requires(s -> s.getSender().hasPermission("pixelprotect.lookup"))
                .executes(c -> lookup(c.getSource(), 5, 1, ""))
                .then(Commands.argument("filter", StringArgumentType.greedyString())
                        .executes(c -> lookup(c.getSource(), 5, 1, StringArgumentType.getString(c, "filter"))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> rollbackCommand() {
        return Commands.literal("rollback").requires(s -> s.getSender().hasPermission("pixelprotect.rollback"))
                .then(Commands.literal("status").then(Commands.argument("job", StringArgumentType.word())
                        .executes(c -> rollbackStatus(c.getSource().getSender(), StringArgumentType.getString(c, "job")))))
                .then(Commands.literal("cancel").then(Commands.argument("job", StringArgumentType.word())
                        .executes(c -> rollbackCancel(c.getSource().getSender(), StringArgumentType.getString(c, "job")))))
                .executes(c -> rollback(c.getSource(), 5, 1, ""))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, maxRadius))
                        .executes(c -> rollback(c.getSource(), IntegerArgumentType.getInteger(c, "radius"), 1, ""))
                        .then(Commands.argument("hours", IntegerArgumentType.integer(1, maxHours))
                                .executes(c -> rollback(c.getSource(), IntegerArgumentType.getInteger(c, "radius"), IntegerArgumentType.getInteger(c, "hours"), ""))
                                .then(Commands.argument("filter", StringArgumentType.greedyString())
                                        .executes(c -> rollback(c.getSource(), IntegerArgumentType.getInteger(c, "radius"), IntegerArgumentType.getInteger(c, "hours"), StringArgumentType.getString(c, "filter"))))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> restoreCommand(String name) {
        return Commands.literal(name).requires(s -> s.getSender().hasPermission("pixelprotect.rollback"))
                .then(Commands.argument("job", StringArgumentType.word())
                        .executes(c -> restore(c.getSource().getSender(), StringArgumentType.getString(c, "job"))));
    }

    private int inspect(CommandSender sender) {
        if (!(sender instanceof Player player)) return message(sender, "PixelProtect: /pp inspect kann nur ein Spieler benutzen.");
        boolean enabled = inspect.toggle(player);
        return message(player, enabled ? "PixelProtect: Inspektor an. Rechtsklick auf einen Block." : "PixelProtect: Inspektor aus.");
    }

    private int lookup(CommandSourceStack source, int defaultRadius, int defaultHours, String raw) {
        var parsed = SelectorParser.parse(tokens(raw), defaultRadius, defaultHours, maxRadius, maxHours);
        if (!parsed.errors().isEmpty()) return sendErrors(source.getSender(), parsed.errors());
        Location location = resolveLocation(source, parsed);
        if (location == null) return message(source.getSender(), "PixelProtect: Standort oder Welt konnte nicht ermittelt werden.");
        final AuditQuery query;
        try { query = query(location, parsed); }
        catch (ArithmeticException e) { return message(source.getSender(), "PixelProtect: Diese Seitennummer ist zu groß."); }
        CommandSender sender = source.getSender();
        if (parsed.countOnly()) {
            database.count(query).thenAccept(n -> send(sender, "PixelProtect: " + n + " Treffer."))
                    .exceptionally(t -> { send(sender, "PixelProtect: Abfrage fehlgeschlagen – " + rootMessage(t)); return null; });
            return Command.SINGLE_SUCCESS;
        }
        database.count(query).thenCombine(database.query(query), (total, entries) -> new PageResult(total, entries, parsed.radius(), parsed.hours(), parsed.page()))
                .thenAccept(result -> sendLookup(sender, result))
                .exceptionally(t -> { send(sender, "PixelProtect: Abfrage fehlgeschlagen – " + rootMessage(t)); return null; });
        return Command.SINGLE_SUCCESS;
    }

    private int rollback(CommandSourceStack source, int defaultRadius, int defaultHours, String raw) {
        var parsed = SelectorParser.parse(tokens(raw), defaultRadius, defaultHours, maxRadius, maxHours);
        if (!parsed.errors().isEmpty()) return sendErrors(source.getSender(), parsed.errors());
        Location location = resolveLocation(source, parsed);
        if (location == null) return message(source.getSender(), "PixelProtect: Standort oder Welt konnte nicht ermittelt werden.");
        CommandSender sender = source.getSender();
        final AuditQuery query;
        try { query = query(location, parsed); }
        catch (ArithmeticException e) { return message(sender, "PixelProtect: Diese Seitennummer ist zu groß."); }
        if (parsed.countOnly()) {
            database.count(query).thenAccept(n -> send(sender, "PixelProtect: " + n + " Treffer."))
                    .exceptionally(t -> { send(sender, "PixelProtect: Abfrage fehlgeschlagen – " + rootMessage(t)); return null; });
            return Command.SINGLE_SUCCESS;
        }
        AuditQuery rollbackQuery = new AuditQuery(query.world(), query.centerX(), query.centerY(), query.centerZ(), query.radius(),
                query.since(), query.until(), query.actorName(), query.includeActions(), query.excludeActions(),
                query.includeBlocks(), query.excludeBlocks(), PAGE_SIZE, 0);
        queryAllForRollback(rollbackQuery, maxRecords).thenCompose(entries -> {
            if (entries.isEmpty()) return CompletableFuture.completedFuture("empty");
            if (parsed.preview()) return rollback.preview(entries).thenApply(r -> "preview:" + r.applied() + ":" + r.skipped());
            return rollback.start(entries).thenApply(j -> "job:" + j.id());
        }).thenAccept(result -> {
            if (result.equals("empty")) send(sender, parsed.preview() ? "PixelProtect: Nichts zum Zurücksetzen gefunden." : "PixelProtect: Keine passenden Änderungen gefunden.");
            else if (result.startsWith("preview:")) {
                String[] values = result.split(":");
                send(sender, "PixelProtect: Vorschau – " + values[1] + " würden geändert, " + values[2] + " übersprungen.");
            } else send(sender, "PixelProtect: Rücksetzung gestartet. Auftrag: " + result.substring(4));
        }).exceptionally(t -> { send(sender, "PixelProtect: Rücksetzung fehlgeschlagen – " + rootMessage(t)); return null; });
        return Command.SINGLE_SUCCESS;
    }

    private CompletableFuture<List<AuditEntry>> queryAllForRollback(AuditQuery base, int maximum) {
        return queryRollbackPage(base, 0, Math.max(1, maximum), new ArrayList<>());
    }

    private CompletableFuture<List<AuditEntry>> queryRollbackPage(AuditQuery base, int offset, int maximum, List<AuditEntry> collected) {
        int remaining = maximum - collected.size();
        if (remaining <= 0) return CompletableFuture.completedFuture(List.copyOf(collected));
        int limit = Math.min(PAGE_SIZE, remaining);
        AuditQuery page = new AuditQuery(base.world(), base.centerX(), base.centerY(), base.centerZ(), base.radius(), base.since(), base.until(),
                base.actorName(), base.includeActions(), base.excludeActions(), base.includeBlocks(), base.excludeBlocks(), limit, offset);
        return database.query(page).thenCompose(entries -> {
            collected.addAll(entries);
            if (entries.size() < limit) return CompletableFuture.completedFuture(List.copyOf(collected));
            return queryRollbackPage(base, offset + entries.size(), maximum, collected);
        });
    }

    private Location resolveLocation(CommandSourceStack source, SelectorParser.Parsed parsed) {
        World world = parsed.world() == null ? source.getLocation().getWorld() : Bukkit.getWorld(parsed.world());
        if (world == null) return null;
        if (parsed.x() != null) return new Location(world, parsed.x(), parsed.y(), parsed.z());
        if (parsed.chunkX() != null) return new Location(world, parsed.chunkX() * 16 + 8, source.getLocation().getBlockY(), parsed.chunkZ() * 16 + 8);
        return source.getLocation();
    }

    private AuditQuery query(Location location, SelectorParser.Parsed parsed) {
        long now = System.currentTimeMillis();
        long duration = parsed.durationMillis();
        long since = duration >= now ? 0L : now - duration;
        int offset = Math.multiplyExact(parsed.page() - 1, PAGE_SIZE);
        return new AuditQuery(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), parsed.radius(),
                since, now, parsed.user(), parsed.includeActions(), parsed.excludeActions(), parsed.includeBlocks(), parsed.excludeBlocks(), PAGE_SIZE, offset);
    }

    private int restore(CommandSender sender, String raw) {
        try {
            UUID id = UUID.fromString(raw);
            rollback.restore(id).thenAccept(r -> send(sender, "PixelProtect: Wiederherstellung abgeschlossen – " + r.applied() + " geändert, " + r.skipped() + " übersprungen."))
                    .exceptionally(t -> { send(sender, "PixelProtect: Wiederherstellung fehlgeschlagen – " + rootMessage(t)); return null; });
            return Command.SINGLE_SUCCESS;
        } catch (IllegalArgumentException e) { return message(sender, "PixelProtect: Ungültige Auftrags-ID."); }
    }

    private int rollbackStatus(CommandSender sender, String raw) {
        try {
            UUID id = UUID.fromString(raw);
            rollback.statusAsync(id).thenAccept(job -> {
                if (job == null) { send(sender, "PixelProtect: Auftrag nicht gefunden."); return; }
                String text = "PixelProtect: " + MessageService.rollbackStatus(job.status()) + " • " + job.processed() + "/" + job.total() + " verarbeitet • " + job.applied() + " geändert • " + job.skipped() + " übersprungen";
                if (job.error() != null && !job.error().isBlank()) text += " • Fehler: " + job.error();
                send(sender, text);
            }).exceptionally(t -> { send(sender, "PixelProtect: Status konnte nicht gelesen werden – " + rootMessage(t)); return null; });
            return Command.SINGLE_SUCCESS;
        } catch (IllegalArgumentException e) { return message(sender, "PixelProtect: Ungültige Auftrags-ID."); }
    }

    private int rollbackCancel(CommandSender sender, String raw) {
        try {
            UUID id = UUID.fromString(raw);
            rollback.cancelAsync(id).thenAccept(cancelled -> send(sender, cancelled ? "PixelProtect: Abbruch angefordert." : "PixelProtect: Auftrag nicht gefunden oder bereits abgeschlossen."))
                    .exceptionally(t -> { send(sender, "PixelProtect: Abbruch fehlgeschlagen – " + rootMessage(t)); return null; });
            return Command.SINGLE_SUCCESS;
        } catch (IllegalArgumentException e) { return message(sender, "PixelProtect: Ungültige Auftrags-ID."); }
    }

    private int purge(CommandSender sender, int days) {
        database.purgeBefore(System.currentTimeMillis() - days * 86_400_000L)
                .thenAccept(n -> send(sender, "PixelProtect: " + n + " alte Protokolleinträge gelöscht."))
                .exceptionally(t -> { send(sender, "PixelProtect: Bereinigung fehlgeschlagen – " + rootMessage(t)); return null; });
        return Command.SINGLE_SUCCESS;
    }

    private int status(CommandSender sender) {
        database.count().thenAccept(n -> send(sender, "PixelProtect: Betriebsbereit • " + n + " Protokolleinträge • Warteschlange " + database.queueSize() + " • Schema " + database.schemaVersion()))
                .exceptionally(t -> { send(sender, "PixelProtect: Speicherstatus konnte nicht gelesen werden – " + rootMessage(t)); return null; });
        return Command.SINGLE_SUCCESS;
    }

    private int help(CommandSender sender) {
        send(sender, "PixelProtect: /pp inspect = prüfen • /pp lookup [Radius] [Stunden] = nachsehen • /pp rollback [Radius] [Stunden] = zurücksetzen • /pp rollback status <Auftrag> • /pp rollback cancel <Auftrag> • /pp restore <Auftrag> • /pp purge <Tage> • /pp status");
        return Command.SINGLE_SUCCESS;
    }

    private int version(CommandSender sender) { return message(sender, "PixelProtect 1.0.0"); }

    private void sendLookup(CommandSender sender, PageResult result) {
        long pages = Math.max(1, (result.total() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (result.entries().isEmpty()) {
            send(sender, "PixelProtect: Keine Änderungen im gewählten Bereich und Zeitraum gefunden.");
            return;
        }

        Component message = Component.text("----- PixelProtect Lookup Results -----", NamedTextColor.GOLD)
                .append(Component.newline())
                .append(Component.text(result.total() + " Treffer • Seite " + result.page() + "/" + pages, NamedTextColor.GRAY));

        for (AuditEntry entry : result.entries()) {
            message = message.append(Component.newline())
                    .append(Component.text(timeAgo(entry.time()) + " – ", NamedTextColor.GRAY))
                    .append(Component.text(actor(entry), NamedTextColor.AQUA))
                    .append(Component.text(" ", NamedTextColor.GRAY))
                    .append(actionLine(entry))
                    .append(Component.newline())
                    .append(Component.text("    ↳ ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(MessageService.coordinates(entry), NamedTextColor.GRAY))
                    .append(Component.text(" (", NamedTextColor.DARK_GRAY))
                    .append(Component.text(worldName(entry), NamedTextColor.GRAY))
                    .append(Component.text(")", NamedTextColor.DARK_GRAY));
        }

        if (result.page() < pages) {
            message = message.append(Component.newline())
                    .append(Component.text("Nächste Seite: /pp lookup " + result.radius() + " " + result.hours() + " #page:" + (result.page() + 1), NamedTextColor.YELLOW));
        }
        send(sender, message);
    }

    private Component actionLine(AuditEntry entry) {
        if (entry.action() == de.pixelprotect.model.ActionType.CONTAINER || entry.action() == de.pixelprotect.model.ActionType.INVENTORY
                || entry.action() == de.pixelprotect.model.ActionType.CRAFT || entry.action() == de.pixelprotect.model.ActionType.TRADE) {
            List<InventoryDiffService.ItemChange> changes = InventoryDiffService.itemChanges(entry.beforeInventory(), entry.afterInventory());
            if (!changes.isEmpty()) return inventoryAction(changes);
        }

        if (entry.action() == de.pixelprotect.model.ActionType.BREAK || entry.action() == de.pixelprotect.model.ActionType.BLOCK_BREAK) {
            return Component.text("removed x1 ", NamedTextColor.RED)
                    .append(Component.text(humanBlock(entry.beforeData()), NamedTextColor.AQUA));
        }
        if (entry.action() == de.pixelprotect.model.ActionType.PLACE) {
            return Component.text("placed x1 ", NamedTextColor.GREEN)
                    .append(Component.text(humanBlock(entry.afterData()), NamedTextColor.AQUA));
        }

        Component result = Component.text(MessageService.action(entry.action()), NamedTextColor.WHITE);
        if (entry.details() != null && !entry.details().isBlank()) {
            result = result.append(Component.text(" – " + compactDetails(entry.details()), NamedTextColor.GRAY));
        }
        return result;
    }

    private Component inventoryAction(List<InventoryDiffService.ItemChange> changes) {
        Component result = Component.empty();
        int shown = 0;
        for (InventoryDiffService.ItemChange change : changes) {
            if (shown > 0) result = result.append(Component.text(", ", NamedTextColor.DARK_GRAY));
            String verb = change.removed() ? "removed " : "added ";
            NamedTextColor verbColor = change.removed() ? NamedTextColor.RED : NamedTextColor.GREEN;
            result = result.append(Component.text(verb + "x" + Math.abs(change.amount()) + " ", verbColor))
                    .append(change.displayName());
            shown++;
            if (shown >= MAX_ITEMS_PER_ENTRY) {
                if (changes.size() > shown) result = result.append(Component.text(" …", NamedTextColor.DARK_GRAY));
                break;
            }
        }
        return result;
    }

    private static String worldName(AuditEntry entry) {
        World world = Bukkit.getWorld(entry.world());
        return world == null ? entry.world().toString().substring(0, 8) : world.getName();
    }

    private static String humanBlock(String value) {
        if (value == null || value.isBlank()) return "unknown";
        int separator = value.indexOf('[');
        String name = separator >= 0 ? value.substring(0, separator) : value;
        if (name.startsWith("minecraft:")) name = name.substring("minecraft:".length());
        return name.replace('_', ' ');
    }

    private static String compactDetails(String details) {
        String value = details.replace('\n', ' ').replace('\r', ' ').trim();
        return value.length() > 100 ? value.substring(0, 97) + "..." : value;
    }

    private static String timeAgo(long timestamp) {
        long seconds = Math.max(0L, Duration.ofMillis(Math.max(0L, System.currentTimeMillis() - timestamp)).toSeconds());
        if (seconds < 60) return seconds + "s ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        long days = hours / 24;
        if (days < 7) return days + "d ago";
        long weeks = days / 7;
        if (weeks < 5) return weeks + "w ago";
        long months = days / 30;
        if (months < 12) return months + "mo ago";
        return (days / 365) + "y ago";
    }

    private static String actor(AuditEntry entry) { return entry.actorName() == null || entry.actorName().isBlank() ? "Unbekannt" : entry.actorName(); }
    private static List<String> tokens(String raw) { return raw == null || raw.isBlank() ? List.of() : Arrays.asList(raw.trim().split("\\s+")); }
    private int sendErrors(CommandSender sender, List<String> errors) { send(sender, "PixelProtect: " + String.join(" | ", errors)); return 0; }
    private int message(CommandSender sender, String message) { send(sender, message); return Command.SINGLE_SUCCESS; }

    private void send(CommandSender sender, String message) {
        if (sender instanceof Player player) player.getScheduler().run(plugin, task -> { if (player.isOnline()) player.sendPlainMessage(message); }, null);
        else Bukkit.getGlobalRegionScheduler().run(plugin, task -> sender.sendPlainMessage(message));
    }

    private void send(CommandSender sender, Component message) {
        if (sender instanceof Player player) {
            player.getScheduler().run(plugin, task -> { if (player.isOnline()) player.sendMessage(message); }, null);
        } else {
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> sender.sendMessage(message));
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record PageResult(long total, List<AuditEntry> entries, int radius, int hours, int page) {}
}
