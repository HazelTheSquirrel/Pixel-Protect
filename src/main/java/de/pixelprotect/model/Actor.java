package de.pixelprotect.model;

import java.util.UUID;

public record Actor(UUID uuid, String name) {
    public static Actor environment() {
        return new Actor(null, "[Environment]");
    }
}
