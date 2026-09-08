package de.pixelprotect.service;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.AuditEntry;
import de.pixelprotect.service.RollbackService.Status;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

public final class MessageService {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale.GERMANY).withZone(ZoneId.systemDefault());
    private MessageService() {}
    public static String action(ActionType action) {
        return switch (action) {
            case BREAK -> "Abgebaut"; case PLACE -> "Platziert"; case BURN -> "Verbrannt"; case EXPLOSION -> "Explosion";
            case PISTON -> "Kolbenbewegung"; case FLUID -> "Flüssigkeitsänderung"; case GROW -> "Wachstum"; case FORM -> "Bildung";
            case SPREAD -> "Ausbreitung"; case ENTITY_CHANGE -> "Entitätsänderung"; case BUCKET -> "Eimeraktion"; case CONTAINER -> "Container geändert";
            case ITEM_DROP -> "Gegenstand fallengelassen"; case ITEM_PICKUP -> "Gegenstand aufgehoben"; case ITEM_DESPAWN -> "Gegenstand verschwunden";
            case ENTITY_SPAWN -> "Entität gespawnt"; case ENTITY_DEATH -> "Entität gestorben"; case ENTITY_REMOVE -> "Entität entfernt";
            case ENTITY_DAMAGE -> "Entität beschädigt"; case PROJECTILE -> "Projektil"; case INTERACT -> "Interaktion";
            case ENTITY_INTERACT -> "Entitätsinteraktion"; case SIGN -> "Schild geändert"; case CHAT -> "Chat"; case COMMAND -> "Befehl";
            case SESSION -> "Sitzung"; case CRAFT -> "Herstellung"; case TRADE -> "Handel"; case DECAY -> "Verfall";
            case MOISTURE -> "Feuchtigkeit"; case SCULK -> "Sculk"; case CAULDRON -> "Kessel"; case DISPENSE -> "Ausgabe";
            case COMPOST -> "Kompostierung"; case SHEAR -> "Scherenaktion"; case TNT_PRIME -> "TNT gezündet"; case BLOCK_BREAK -> "Block durch Mechanik abgebaut";
            case VAULT -> "Vault-Zustand"; case PORTAL -> "Portal"; case STRUCTURE -> "Struktur"; case BOOKSHELF -> "Bücherregal";
            case FLOWER_POT -> "Blumentopf"; case CAMPFIRE -> "Lagerfeuer"; case LECTERN -> "Lesepult";
        };
    }
    public static String rollbackStatus(Status status) {
        if (status == null) return "Unbekannt";
        return switch (status) { case RUNNING -> "Läuft"; case COMPLETED -> "Abgeschlossen"; case FAILED -> "Fehlgeschlagen"; case CANCELLED -> "Abgebrochen"; };
    }
    public static String rollbackStatus(String status) {
        if (status == null) return "Unbekannt";
        return switch (status.toUpperCase(Locale.ROOT)) { case "PENDING" -> "Wartet"; case "RUNNING" -> "Läuft"; case "COMPLETED" -> "Abgeschlossen"; case "FAILED" -> "Fehlgeschlagen"; case "CANCELLED", "CANCELED" -> "Abgebrochen"; default -> status; };
    }
    public static String time(long epochMillis) { return TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis)); }
    public static String coordinates(AuditEntry entry) { return coordinates(entry.x(), entry.y(), entry.z()); }
    public static String coordinates(int x, int y, int z) { return "X: " + x + "  Y: " + y + "  Z: " + z; }
}
