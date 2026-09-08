package de.pixelprotect.command;

import de.pixelprotect.model.ActionType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** PixelProtect's own query grammar; it is intentionally independent of other plugins. */
public final class SelectorParser {
    private SelectorParser() {}

    public static Parsed parse(List<String> tokens, int defaultRadius, int defaultHours,
                               int maxRadius, int maxHours) {
        int radius = defaultRadius;
        int hours = defaultHours;
        String user = null;
        final Set<ActionType> includeActions = new LinkedHashSet<>();
        final Set<ActionType> excludeActions = new LinkedHashSet<>();
        final Set<String> includeBlocks = new LinkedHashSet<>();
        final Set<String> excludeBlocks = new LinkedHashSet<>();
        boolean countOnly = false;
        boolean preview = false;
        final List<String> errors = new ArrayList<>();

        for (String token : tokens) {
            if (token == null || token.isBlank()) continue;
            final String value = token.trim();
            if (value.equalsIgnoreCase("#count")) { countOnly = true; continue; }
            if (value.equalsIgnoreCase("#preview")) { preview = true; continue; }

            final int colon = value.indexOf(':');
            if (colon <= 0 || colon == value.length() - 1) {
                errors.add("Ungültiger Selektor: " + value);
                continue;
            }
            final String key = value.substring(0, colon).toLowerCase(Locale.ROOT);
            final String raw = value.substring(colon + 1).trim();
            try {
                switch (key) {
                    case "u" -> user = raw;
                    case "t" -> hours = parseDurationHours(raw);
                    case "r" -> radius = Integer.parseInt(raw);
                    case "a" -> addActions(raw, includeActions, excludeActions);
                    case "i" -> addValues(raw, includeBlocks);
                    case "e" -> addValues(raw, excludeBlocks);
                    default -> errors.add("Unbekannter Selektor: " + key + ":");
                }
            } catch (IllegalArgumentException ex) {
                errors.add(ex.getMessage() == null ? "Ungültiger Selektor: " + value : ex.getMessage());
            }
        }

        if (radius < 1 || radius > maxRadius) errors.add("Radius muss zwischen 1 und " + maxRadius + " liegen.");
        if (hours < 1 || hours > maxHours) errors.add("Zeitfenster muss zwischen 1 und " + maxHours + " Stunden liegen.");
        return new Parsed(radius, hours, user, includeActions, excludeActions, includeBlocks, excludeBlocks,
                countOnly, preview, errors);
    }

    private static void addActions(String raw, Set<ActionType> include, Set<ActionType> exclude) {
        for (String part : raw.split(",")) {
            final String value = part.trim();
            if (value.isEmpty()) continue;
            final boolean negative = value.startsWith("-");
            final String normalized = (negative ? value.substring(1) : value).toUpperCase(Locale.ROOT);
            try {
                (negative ? exclude : include).add(ActionType.valueOf(normalized));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Unbekannte Aktion: " + value);
            }
        }
    }

    private static void addValues(String raw, Set<String> target) {
        for (String part : raw.split(",")) {
            final String normalized = part.trim().toLowerCase(Locale.ROOT);
            if (!normalized.matches("[a-z0-9_./:-]+")) {
                throw new IllegalArgumentException("Ungültiger Blockfilter: " + part.trim());
            }
            target.add(normalized.startsWith("minecraft:") ? normalized : "minecraft:" + normalized);
        }
    }

    private static int parseDurationHours(String raw) {
        final String value = raw.trim().toLowerCase(Locale.ROOT);
        try {
            if (value.matches("\\d+")) return Integer.parseInt(value);
            final char suffix = value.charAt(value.length() - 1);
            final long amount = Long.parseLong(value.substring(0, value.length() - 1));
            final Duration duration = switch (suffix) {
                case 'm' -> Duration.ofMinutes(amount);
                case 'h' -> Duration.ofHours(amount);
                case 'd' -> Duration.ofDays(amount);
                default -> throw new IllegalArgumentException("Zeitangabe muss z.B. 30m, 12h oder 7d sein.");
            };
            return Math.toIntExact((duration.toMinutes() + 59) / 60);
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new IllegalArgumentException("Ungültige Zeitangabe: " + raw);
        }
    }

    public record Parsed(int radius, int hours, String user,
                         Set<ActionType> includeActions, Set<ActionType> excludeActions,
                         Set<String> includeBlocks, Set<String> excludeBlocks,
                         boolean countOnly, boolean preview, List<String> errors) {}
}
