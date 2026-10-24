package com.alexastudillo.companyregistry.infrastructure.outbox;

import java.nio.charset.StandardCharsets;
import java.util.Set;

public final class OutboxDestination {
    public static final Set<String> EVENT_TYPES = Set.of("company.created.v1", "company.updated.v1",
            "company.activated.v1", "company.deactivated.v1", "company.site.created.v1",
            "company.site.updated.v1", "company.site.closed.v1");

    private OutboxDestination() {}

    public static void validate(String exchange, String template) {
        destination(exchange);
        if (template == null || !template.contains("%s") || template.replace("%s", "").contains("%")
                || template.indexOf("%s") != template.lastIndexOf("%s")) invalid();
        for (var type : EVENT_TYPES) destination(route(template, type));
    }

    public static String route(String template, String type) {
        return template.replace("%s", type);
    }

    private static void destination(String value) {
        if (value == null || value.isBlank() || value.codePoints().anyMatch(Character::isISOControl)
                || value.getBytes(StandardCharsets.UTF_8).length > 255) invalid();
    }

    private static void invalid() { throw new IllegalArgumentException("outbox-destination-invalid"); }
}
