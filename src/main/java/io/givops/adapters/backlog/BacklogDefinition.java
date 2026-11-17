package io.givops.adapters.backlog;

import io.givops.adapters.CredentialField;
import io.givops.adapters.CredentialRequest;
import io.givops.ticketing.model.TicketModels.RepositorySource;
import io.givops.ticketing.model.TicketModels.TicketProvider;
import io.givops.ticketing.port.TicketProviderDefinition;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class BacklogDefinition implements TicketProviderDefinition {
    public static final TicketProvider PROVIDER = TicketProvider.valueOf("BACKLOG_MD");
    @Override public TicketProvider provider() { return PROVIDER; }
    @Override public String displayName() { return "Backlog.md"; }
    @Override public String adapterId() { return "io.givops:adapter-backlog"; }
    @Override public String adapterVersion() { return "0.1.0-SNAPSHOT"; }
    @Override public List<String> capabilities() { return List.of("ticketing.read", "ticketing.browse", "ticketing.content", "ticketing.assets"); }
    @Override public List<String> apiProfiles() { return List.of("backlog-md-1.55.3"); }
    @Override public List<CredentialField> credentialFields() { return List.of(); }
    @Override public List<String> sourceKinds() { return List.of("REPOSITORY_SNAPSHOT"); }
    @Override public void validateRepositorySource(RepositorySource source) { new BacklogSettings(source); }
    @Override public URI normalizeBaseUrl(URI value) {
        if (value != null) throw BacklogSettings.error("Backlog sources use registered repositories, not API URLs.");
        return null;
    }
    @Override public String projectKeyPattern() { return "[A-Za-z]{1,32}"; }
    @Override public String externalIdPattern() { return "[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"; }
    @Override public void validateProject(String externalId, String projectKey) {
        if (externalId == null || !externalId.matches(externalIdPattern())) throw BacklogSettings.error("Invalid store identity.");
        validateProjectKey(projectKey);
    }
    @Override public Map<String, String> validateCredentials(CredentialRequest target, Map<String, String> fields) {
        if (!fields.isEmpty()) throw BacklogSettings.error("Git access is managed by the host repository binding.");
        return Map.of();
    }
    @Override public Optional<String> normalizeReferenceCandidate(String candidate) { return BacklogIds.candidate(candidate); }
    @Override public Optional<String> normalizeIssueKey(String token, String projectKey) {
        return BacklogIds.candidate(token).filter(key -> key.startsWith(projectKey.toUpperCase(java.util.Locale.ROOT) + "-")
                && !key.startsWith("DRAFT-"));
    }
}
