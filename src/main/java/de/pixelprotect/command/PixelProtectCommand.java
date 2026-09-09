package de.pixelprotect.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import de.pixelprotect.model.AuditEntry;
import de.pixelprotect.model.AuditQuery;
import de.pixelprotect.service.InspectService;
import de.pixelprotect.service.MessageService;
import de.pixelprotect.service.RollbackService;
import de.pixelprotect.storage.Database;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PixelProtectCommand {
    private static final int PAGE_SIZE = 50;
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
                .executes(c -> version(c.getSource().getSender()))
                .then(Commands.literal("version").executes(c -> version(c.getSource().getSender())))
                .then(Commands.literal("status").requires(s -> s.getSender().hasPermission("pixelprotect.status"))
                        .executes(c -> status(c.getSource().getSender())))
                .then(Commands.literal("inspect").requires(s -> s.getSender().hasPermission("pixelprotect.inspect"))
                        .executes(c -> inspect(c.getSource().getSender())))
                .then(Commands.literal("lookup").requires(s -> s.getSender().hasPermission("pixelprotect.lookup"))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, maxRadius))
                                .then(Commands.argument("hours", IntegerArgumentType.integer(1, maxHours))
                                        .executes(c -> lookup(c.getSource(), IntegerArgumentType.getInteger(c, "radius"), IntegerArgumentType.getInteger(c, "hours"), ""))
                                        .then(Commands.argument("selectors", StringArgumentType.greedyString())
                                                .executes(c -> lookup(c.getSource(), IntegerArgumentType.getInteger(c, "radius"), IntegerArgumentType.getInteger(c, "hours"), StringArgumentType.getString(c, "selectors")))))))
                .then(Commands.literal("near").requires(s -> s.getSender().hasPermission("pixelprotect.lookup"))
                        .executes(c -> lookup(c.getSource(), 5, 1, ""))
                        .then(Commands.argument("selectors", StringArgumentType.greedyString())
                                .executes(c -> lookup(c.getSource(), 5, 1, StringArgumentType.getString(c, "selectors")))))
                .then(Commands.literal("rollback").requires(s -> s.getSender().hasPermission("pixelprotect.rollback"))
                        .then(Commands.literal("status")
                                .then(Commands.argument("job", StringArgumentType.word())
                                        .executes(c -> rollbackStatus(c.getSource().getSender(), StringArgumentType.getString(c, "job")))))
                        .then(Commands.literal("cancel")
                                .then(Commands.argument("job", StringArgumentType.word())
                                        .executes(c -> rollbackCancel(c.getSource().getSender(), StringArgumentType.getString(c, "job")))))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, maxRadius))
                                .then(Commands.argument("hours", IntegerArgumentType.integer(1, maxHours))
                                        .executes(c -> rollback(c.getSource(), IntegerArgumentType.getInteger(c, "radius"), IntegerArgumentType.getInteger(c, "hours"), ""))
                                        .then(Commands.argument("selectors", StringArgumentType.greedyString())
                                                .executes(c -> rollback(c.getSource(), IntegerArgumentType.getInteger(c, "radius"), IntegerArgumentType.getInteger(c, "hours"), StringArgumentType.getString(c, "selectors")))))))
                .then(Commands.literal("restore").requires(s -> s.getSender().hasPermission("pixelprotect.rollback"))
                        .then(Commands.argument("job", StringArgumentType.word())
                                .executes(c -> restore(c.getSource().getSender(), StringArgumentType.getString(c, "job")))))
                .then(Commands.literal("undo").requires(s -> s.getSender().hasPermission("pixelprotect.rollback"))
                        .then(Commands.argument("job", StringArgumentType.word())
                                .executes(c -> restore(c.getSource().getSender(), StringArgumentType.getString(c, "job")))))
                .then(Commands.literal("purge").requires(s -> s.getSender().hasPermission("pixelprotect.purge"))
                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 3650))
                                .executes(c -> purge(c.getSource().getSender(), IntegerArgumentType.getInteger(c, "days")))))
                .then(Commands.literal("help").executes(c -> help(c.getSource().getSender())));
    }

    private int inspect(CommandSender sender) {
        if (!(sender instanceof Player player)) return message(sender, "PixelProtect: Der Inspektor ist nur für Spieler verfügbar.");
        boolean enabled = inspect.toggle(player);
        return message(player, enabled
                ? "PixelProtect: Inspektor aktiviert. Klicke einen Block an, um seine Historie anzuzeigen."
                : "PixelProtect: Inspektor deaktiviert.");
    }

    private int lookup(CommandSourceStack source, int defaultRadius, int defaultHours, String raw) {
        var parsed = SelectorParser.parse(tokens(raw), defaultRadius, defaultHours, maxRadius, maxHours);
        if (!parsed.errors().isEmpty()) return sendErrors(source.getSender(), parsed.errors());
        Location location = resolveLocation(source, parsed);
        if (location == null) return message(source.getSender(), "PixelProtect: Es konnte keine gültige Welt oder Position ermittelt werden.");
        AuditQuery query;
        try { query = query(location, parsed); }
        catch (ArithmeticException e) { return message(source.getSender(), "PixelProtect: Die Seitennummer ist zu groß."); }
        if (parsed.countOnly()) {
            database.count(query).thenAccept(n -> send(source.getSender(), "PixelProtect: " + n + " passende Protokolleinträge gefunden."))
                    .exceptionally(t -> { send(source.getSender(), "PixelProtect: Abfrage fehlgeschlagen – " + rootMessage(t)); return null; });
        } else {
            database.count(query).thenCombine(database.query(query), (n, entries) -> new PageResult(n, entries, parsed.page()))
                    .thenAccept(result -> sendLookup(source.getSender(), result))
                    .exceptionally(t -> { send(source.getSender(), "PixelProtect: Abfrage fehlgeschlagen – " + rootMessage(t)); return null; });
        }
        send(source.getSender(), "PixelProtect: Protokollhistorie wird abgefragt …");
        return Command.SINGLE_SUCCESS;
    }

    private int rollback(CommandSourceStack source, int defaultRadius, int defaultHours, String raw) {
        var parsed = SelectorParser.parse(tokens(raw), defaultRadius, defaultHours, maxRadius, maxHours);
        if (!parsed.errors().isEmpty()) return sendErrors(source.getSender(), parsed.errors());
        Location location = resolveLocation(source, parsed);
        if (location == null) return message(source.getSender(), "PixelProtect: Es konnte keine gültige Welt oder Position ermittelt werden.");
        CommandSender sender = source.getSender();
        final AuditQuery query;
        try { query = query(location, parsed); }
        catch (ArithmeticException e) { return message(sender, "PixelProtect: Die Seitennummer ist zu groß."); }
        if (parsed.countOnly()) {
            database.count(query).thenAccept(n -> send(sender, "PixelProtect: " + n + " passende Protokolleinträge gefunden."))
                    .exceptionally(t -> { send(sender, "PixelProtect: Abfrage fehlgeschlagen – " + rootMessage(t)); return null; });
            return Command.SINGLE_SUCCESS;
        }
        final AuditQuery rollbackQuery = new AuditQuery(query.world(), query.centerX(), query.centerY(), query.centerZ(), query.radius(),
                query.since(), query.until(), query.actorName(), query.includeActions(), query.excludeActions(),
                query.includeBlocks(), query.excludeBlocks(), PAGE_SIZE, 0);
        queryAllForRollback(rollbackQuery, maxRecords).thenCompose(entries -> {
            if (entries.isEmpty()) {
                send(sender, parsed.preview() ? "PixelProtect: Die Vorschau enthält keine passenden Einträge." : "PixelProtect: Es gibt keine passenden Einträge zurückzusetzen.");
                return CompletableFuture.<String>completedFuture(null);
            }
            if (parsed.preview()) return rollback.preview(entries).thenApply(r -> "preview:" + r.applied() + ":" + r.skipped());
            return rollback.start(entries).thenApply(j -> "job:" + j.id());
        }).thenAccept(result -> {
            if (result == null) return;
            if (result.startsWith("preview:")) {
                String[] values = result.split(":");
                send(sender, "PixelProtect: Vorschau abgeschlossen. " + values[1] + " würden angewendet, " + values[2] + " würden übersprungen.");
            } else {
                send(sender, "PixelProtect: Rücksetzauftrag gestartet: " + result.substring(4) + ". Mit /pixelprotect rollback status <Auftrag> kannst du den Fortschritt prüfen.");
            }
        }).exceptionally(t -> { send(sender, "PixelProtect: Vorgang fehlgeschlagen – " + rootMessage(t)); return null; });
        send(sender, "PixelProtect: Rücksetzung wird vorbereitet …");
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
                since, now, parsed.user(), parsed.includeActions(), parsed.excludeActions(), parsed.includeBlocks(), parsed.excludeBlocks(),
                Math.min(PAGE_SIZE, maxRecords), offset);
    }

    private int restore(CommandSender sender, String raw) {
        try {
            UUID id = UUID.fromString(raw);
            rollback.restore(id).thenAccept(r -> send(sender, "PixelProtect: Wiederherstellung abgeschlossen. " + r.applied() + " angewendet, " + r.skipped() + " übersprungen."))
                    .exceptionally(t -> { send(sender, "PixelProtect: Wiederherstellung fehlgeschlagen – " + rootMessage(t)); return null; });
            return message(sender, "PixelProtect: Wiederherstellung gestartet …");
        } catch (IllegalArgumentException e) { return message(sender, "PixelProtect: Die Auftrags-ID ist ungültig."); }
    }

    private int rollbackStatus(CommandSender sender, String raw) {
        try {
            UUID id = UUID.fromString(raw);
            rollback.statusAsync(id).thenAccept(job -> {
                if (job == null) send(sender, "PixelProtect: Rücksetzauftrag nicht gefunden.");
                else send(sender, "PixelProtect: Auftrag " + job.id() + " – Status: " + MessageService.rollbackStatus(job.status())
                        + " – verarbeitet: " + job.processed() + "/" + job.total() + ", angewendet: " + job.applied()
                        + ", übersprungen: " + job.skipped() + (job.error() == null ? "" : " – Fehler: " + job.error()));
            }).exceptionally(t -> { send(sender, "PixelProtect: Statusabfrage fehlgeschlagen – " + rootMessage(t)); return null; });
            return message(sender, "PixelProtect: Status des Rücksetzauftrags wird geladen …");
        } catch (IllegalArgumentException e) { return message(sender, "PixelProtect: Die Auftrags-ID ist ungültig."); }
    }

    private int rollbackCancel(CommandSender sender, String raw) {
        try {
            UUID id = UUID.fromString(raw);
            rollback.cancelAsync(id).thenAccept(cancelled -> send(sender, cancelled
                    ? "PixelProtect: Abbruch des Rücksetzauftrags wurde angefordert."
                    : "PixelProtect: Rücksetzauftrag nicht gefunden oder bereits abgeschlossen."))
                    .exceptionally(t -> { send(sender, "PixelProtect: Abbruch fehlgeschlagen – " + rootMessage(t)); return null; });
            return Command.SINGLE_SUCCESS;
        } catch (IllegalArgumentException e) { return message(sender, "PixelProtect: Die Auftrags-ID ist ungültig."); }
    }

    private int purge(CommandSender sender, int days) {
        database.purgeBefore(System.currentTimeMillis() - days * 86_400_000L)
                .thenAccept(n -> send(sender, "PixelProtect: " + n + " Protokolleinträge wurden gelöscht."))
                .exceptionally(t -> { send(sender, "PixelProtect: Bereinigung fehlgeschlagen – " + rootMessage(t)); return null; });
        return message(sender, "PixelProtect: Bereinigung gestartet …");
    }

    private int status(CommandSender sender) {
        database.count().thenAccept(n -> send(sender, "PixelProtect: Betriebsbereit. Schema " + database.schemaVersion() + ", Warteschlange " + database.queueSize() + ", gespeicherte Protokolleinträge: " + n + "."))
                .exceptionally(t -> { send(sender, "PixelProtect: Speicherstatus konnte nicht ermittelt werden – " + rootMessage(t)); return null; });
        return message(sender, "PixelProtect: Speicherstatus wird geprüft …");
    }

    private int help(CommandSender sender) {
        send(sender, "PixelProtect – Befehlsübersicht");
        send(sender, "/pixelprotect inspect – Inspektor ein-/ausschalten");
        send(sender, "/pixelprotect lookup <Radius> <Stunden> [u:Spieler] [t:Dauer] [a:Aktionen] [i:Blöcke] [e:Blöcke] [w:Welt] [c:x,y,z|ch:x,z] [#page:n] [#count]");
        send(sender, "/pixelprotect rollback <Radius> <Stunden> [Filter] [#preview] – Änderungen zurücksetzen");
        send(sender, "/pixelprotect rollback status <Auftrag> – Rücksetzstatus anzeigen");
        send(sender, "/pixelprotect rollback cancel <Auftrag> – Rücksetzung abbrechen");
        send(sender, "/pixelprotect restore <Auftrag> – abgeschlossene Rücksetzung wiederherstellen");
        send(sender, "/pixelprotect undo <Auftrag> – letzte Rücksetzung umkehren");
        send(sender, "/pixelprotect purge <Tage> – alte Protokolle löschen");
        return Command.SINGLE_SUCCESS;
    }

    private int version(CommandSender sender) { return message(sender, "PixelProtect 1.0.0 – eigenständiger Protokoll- und Rücksetzungskern für Paper 26.2"); }

    private void sendLookup(CommandSender sender, PageResult result) {
        long pages = Math.max(1, (result.total() + PAGE_SIZE - 1) / PAGE_SIZE);
        send(sender, "PixelProtect: Seite " + result.page() + "/" + pages + " – " + result.entries().size() + " von insgesamt " + result.total() + " Einträgen.");
        result.entries().forEach(e -> send(sender, "#" + e.id() + " – " + actor(e) + " – " + MessageService.action(e.action()) + " – " + MessageService.coordinates(e) + " – " + MessageService.time(e.time())));
        if (result.page() < pages) send(sender, "PixelProtect: Nächste Seite: #page:" + (result.page() + 1));
    }

    private static String actor(AuditEntry entry) { return entry.actorName() == null || entry.actorName().isBlank() ? "Unbekannt" : entry.actorName(); }
    private static List<String> tokens(String raw) { return raw == null || raw.isBlank() ? List.of() : Arrays.asList(raw.trim().split("\\s+")); }
    private int sendErrors(CommandSender sender, List<String> errors) { errors.forEach(error -> send(sender, "PixelProtect: " + error)); return 0; }
    private int message(CommandSender sender, String message) { send(sender, message); return Command.SINGLE_SUCCESS; }
    private void send(CommandSender sender, String message) { Bukkit.getGlobalRegionScheduler().run(plugin, task -> sender.sendPlainMessage(message)); }
    private static String rootMessage(Throwable throwable) { Throwable current = throwable; while (current.getCause() != null) current = current.getCause(); return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage(); }
    private record PageResult(long total, List<AuditEntry> entries, int page) {}
}
