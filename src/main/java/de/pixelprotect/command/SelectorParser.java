package de.pixelprotect.command;

import de.pixelprotect.model.ActionType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** CoreProtect-compatible selector grammar with PixelProtect-specific action names. */
public final class SelectorParser {
    private SelectorParser() {}

    public static Parsed parse(List<String> tokens, int defaultRadius, int defaultHours, int maxRadius, int maxHours) {
        int radius = defaultRadius, page = 1;
        long durationMillis = Duration.ofHours(defaultHours).toMillis();
        String user = null, world = null; Integer x = null, y = null, z = null, chunkX = null, chunkZ = null;
        boolean count = false, preview = false;
        List<String> errors = new ArrayList<>();
        Set<ActionType> includeActions = new LinkedHashSet<>(), excludeActions = new LinkedHashSet<>();
        Set<String> includeBlocks = new LinkedHashSet<>(), excludeBlocks = new LinkedHashSet<>();
        for (String token : tokens) {
            if (token == null || token.isBlank()) continue;
            String value = token.trim();
            if (value.equalsIgnoreCase("#count")) { count = true; continue; }
            if (value.equalsIgnoreCase("#preview")) { preview = true; continue; }
            if (value.toLowerCase(Locale.ROOT).startsWith("#page:")) {
                try { page = Integer.parseInt(value.substring(6)); } catch (NumberFormatException e) { errors.add("Ungültige Seite: " + value); }
                continue;
            }
            int colon = value.indexOf(':');
            if (colon <= 0 || colon == value.length() - 1) { errors.add("Ungültiger Selektor: " + value); continue; }
            String key = value.substring(0, colon).toLowerCase(Locale.ROOT), raw = value.substring(colon + 1).trim();
            try {
                switch (key) {
                    case "u" -> user = raw;
                    case "t" -> durationMillis = parseDurationMillis(raw);
                    case "r" -> radius = Integer.parseInt(raw);
                    case "a" -> addActions(raw, includeActions, excludeActions);
                    case "i" -> addValues(raw, includeBlocks);
                    case "e" -> addValues(raw, excludeBlocks);
                    case "w" -> world = raw;
                    case "c" -> { int[] p = parseCoordinates(raw); x = p[0]; y = p[1]; z = p[2]; chunkX = chunkZ = null; }
                    case "ch", "chunk" -> { String[] p = raw.split(","); if (p.length != 2) throw new IllegalArgumentException("Chunk muss x,z sein."); chunkX = Integer.parseInt(p[0].trim()); chunkZ = Integer.parseInt(p[1].trim()); x = y = z = null; }
                    default -> errors.add("Unbekannter Selektor: " + key + ":");
                }
            } catch (IllegalArgumentException e) { errors.add(e.getMessage() == null ? "Ungültiger Selektor: " + value : e.getMessage()); }
        }
        long maxMillis = Duration.ofHours(maxHours).toMillis();
        if (radius < 1 || radius > maxRadius) errors.add("Radius muss zwischen 1 und " + maxRadius + " liegen.");
        if (durationMillis < 1 || durationMillis > maxMillis) errors.add("Zeitfenster muss zwischen 1 Sekunde und " + maxHours + " Stunden liegen.");
        if (page < 1) errors.add("Seite muss mindestens 1 sein.");
        if (world != null && !world.matches("[A-Za-z0-9_.-]+")) errors.add("Ungültiger Weltname: " + world);
        return new Parsed(radius, durationMillis, user, world, x, y, z, chunkX, chunkZ, includeActions, excludeActions,
                includeBlocks, excludeBlocks, count, preview, page, errors);
    }

    private static int[] parseCoordinates(String raw) {
        String[] p = raw.split(",");
        if (p.length != 3) throw new IllegalArgumentException("Koordinaten müssen x,y,z sein.");
        try { return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())}; }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Ungültige Koordinaten: " + raw); }
    }

    private static void addActions(String raw, Set<ActionType> include, Set<ActionType> exclude) {
        for (String part : raw.split(",")) {
            String value = part.trim().toLowerCase(Locale.ROOT);
            if (value.isEmpty()) continue;
            boolean negative = value.startsWith("-");
            String normalized = negative || value.startsWith("+") ? value.substring(1) : value;
            Set<ActionType> target = negative ? exclude : include;
            switch (normalized) {
                case "block" -> { target.add(ActionType.BREAK); target.add(ActionType.PLACE); }
                case "container" -> target.add(ActionType.CONTAINER);
                case "inventory" -> target.add(ActionType.CONTAINER);
                case "item" -> { target.add(ActionType.ITEM_DROP); target.add(ActionType.ITEM_PICKUP); target.add(ActionType.ITEM_DESPAWN); }
                case "kill" -> target.add(ActionType.ENTITY_DEATH);
                case "spawn" -> target.add(ActionType.ENTITY_SPAWN);
                case "session", "login", "logout" -> target.add(ActionType.SESSION);
                case "chat" -> target.add(ActionType.CHAT);
                case "click", "interact" -> { target.add(ActionType.INTERACT); target.add(ActionType.ENTITY_INTERACT); }
                case "command" -> target.add(ActionType.COMMAND);
                case "sign" -> target.add(ActionType.SIGN);
                case "craft" -> target.add(ActionType.CRAFT);
                case "trade" -> target.add(ActionType.TRADE);
                default -> {
                    try { target.add(ActionType.valueOf(normalized.toUpperCase(Locale.ROOT))); }
                    catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unbekannte Aktion: " + value); }
                }
            }
            if (value.startsWith("+")) {
                // '+' explicitly means inclusion; the target is already include unless the token was malformed.
            }
        }
    }

    private static void addValues(String raw, Set<String> target) {
        for (String part : raw.split(",")) {
            String value = part.trim().toLowerCase(Locale.ROOT);
            if (!value.matches("[a-z0-9_./:-]+")) throw new IllegalArgumentException("Ungültiger Blockfilter: " + part.trim());
            target.add(value.startsWith("minecraft:") ? value : "minecraft:" + value);
        }
    }

    private static long parseDurationMillis(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        try {
            if (value.matches("\\d+")) return Duration.ofHours(Long.parseLong(value)).toMillis();
            char suffix = value.charAt(value.length() - 1);
            long number = Long.parseLong(value.substring(0, value.length() - 1));
            return switch (suffix) { case 's' -> Duration.ofSeconds(number).toMillis(); case 'm' -> Duration.ofMinutes(number).toMillis(); case 'h' -> Duration.ofHours(number).toMillis(); case 'd' -> Duration.ofDays(number).toMillis(); case 'w' -> Duration.ofDays(Math.multiplyExact(number, 7L)).toMillis(); default -> throw new IllegalArgumentException("Zeitangabe muss z.B. 30s, 30m, 12h oder 7d sein."); };
        } catch (ArithmeticException | NumberFormatException e) { throw new IllegalArgumentException("Ungültige Zeitangabe: " + raw); }
    }

    public record Parsed(int radius, long durationMillis, String user, String world, Integer x, Integer y, Integer z,
                         Integer chunkX, Integer chunkZ, Set<ActionType> includeActions, Set<ActionType> excludeActions,
                         Set<String> includeBlocks, Set<String> excludeBlocks, boolean countOnly, boolean preview, int page,
                         List<String> errors) {
        public int hours() { return (int) Math.ceil(durationMillis / 3_600_000D); }
    }
}
