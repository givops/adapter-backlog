package io.givops.adapters.backlog;

import io.givops.ticketing.model.TicketBrowserModels.Outcome;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketModels.RepositorySource;
import java.util.Map;
import java.util.Set;

/** Explicit inert adapter policy; never interprets or runs authoring callbacks. */
final class BacklogSettings {
    private static final Set<String> KEYS = Set.of("storeId", "identityPolicy", "workflow", "includeDrafts", "format");
    private final RepositorySource source;
    BacklogSettings(RepositorySource source) {
        if (source == null) throw error("A repository source is required.");
        this.source = source;
        var values = source.settings();
        if (!values.getOrDefault("storeId", "").matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"))
            throw error("Configure a stable storeId.");
        if (!"no-reuse".equals(values.get("identityPolicy"))) throw error("The no-reuse identity policy must be explicit.");
        if (!Set.of("givops", "backlog", "custom").contains(values.getOrDefault("workflow", "")))
            throw error("Configure an explicit workflow profile.");
        if (!Set.of("true", "false").contains(values.getOrDefault("includeDrafts", "false"))) throw error("Invalid draft policy.");
        if (!"backlog-md-1.55.3".equals(values.getOrDefault("format", "backlog-md-1.55.3"))) throw error("Unsupported Backlog format profile.");
        if (values.keySet().stream().anyMatch(key -> !KEYS.contains(key) && !key.startsWith("status."))) throw error("Unknown adapter setting.");
        values.forEach((key, value) -> { if (key.startsWith("status.")) parse(value); });
    }
    String store() { return source.settings().get("storeId"); }
    boolean drafts() { return "true".equals(source.settings().get("includeDrafts")); }
    Mapping mapping(String status) {
        String explicit = source.settings().get("status." + status);
        if (explicit != null) return parse(explicit);
        String workflow = source.settings().get("workflow");
        String value = switch (workflow) {
            case "givops" -> Map.of("cancelled", "indeterminate:CANCELLED", "idea", "new:OPEN", "draft", "new:OPEN", "todo", "new:OPEN",
                    "in_progress", "indeterminate:IN_PROGRESS", "blocked", "indeterminate:IN_PROGRESS", "done", "done:SUCCEEDED").get(status);
            case "backlog" -> Map.of("To Do", "new:OPEN", "In Progress", "indeterminate:IN_PROGRESS", "Done", "done:SUCCEEDED").get(status);
            default -> null;
        };
        if (value == null) throw error("Every configured status requires an explicit mapping.");
        return parse(value);
    }
    private static Mapping parse(String value) {
        String[] parts = value.split(":", -1);
        if (parts.length != 2 || !Set.of("new", "indeterminate", "done").contains(parts[0])) throw error("Invalid status mapping.");
        try {
            Outcome outcome = Outcome.valueOf(parts[1]);
            if ((outcome == Outcome.SUCCEEDED) != parts[0].equals("done")) throw error("Only successful completion maps to done.");
            return new Mapping(parts[0], outcome);
        } catch (IllegalArgumentException invalid) { throw error("Invalid status outcome."); }
    }
    record Mapping(String category, Outcome outcome) { }
    static TicketException error(String message) { return new TicketException(TicketException.Code.INVALID_CONFIGURATION, message); }
}
