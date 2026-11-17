package io.givops.adapters.backlog;

import io.givops.adapters.ContextSigner;
import io.givops.ticketing.model.TicketBrowserModels.*;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketModels.*;
import io.givops.ticketing.port.RepositoryContentHost;
import io.givops.ticketing.port.TicketAssetAdapter;
import io.givops.ticketing.port.TicketBrowseAdapter;
import io.givops.ticketing.port.TicketContentAdapter;
import io.givops.ticketing.port.TicketProviderAdapter;
import io.givops.ticketing.port.TicketRequestGate;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/** Read-only provider. Full documents exist only in transient content operations. */
public final class BacklogAdapter implements TicketProviderAdapter, TicketBrowseAdapter, TicketContentAdapter, TicketAssetAdapter {
    private final RepositoryContentHost host;
    private final ContextSigner signer;
    public BacklogAdapter(RepositoryContentHost host, ContextSigner signer) { this.host = host; this.signer = signer; }
    @Override public TicketProvider provider() { return BacklogDefinition.PROVIDER; }
    private record Captured(InstanceSource source, RepositoryContentHost.Access lease) implements Access {
        @Override public String context() { return lease.context(); }
        @Override public String toString() { return "BacklogAccess[redacted]"; }
    }
    @Override public String authenticationContext(InstanceSource source) { return captureAccess(source).context(); }
    @Override public Access captureAccess(InstanceSource source) {
        if (source == null || !provider().equals(source.provider())) throw BacklogSettings.error("Invalid provider binding.");
        new BacklogSettings(source.repositorySource());
        return new Captured(source, host.captureAccess(source));
    }
    @Override public boolean current(InstanceSource source, Access captured, boolean locked) {
        return captured instanceof Captured access && access.source().equals(source) && access.lease().current(locked);
    }
    private Captured checked(InstanceSource source, Access access, TicketRequestGate gate) {
        gate.check();
        if (!(access instanceof Captured captured) || !captured.source().equals(source) || !captured.lease().current(false))
            throw new TicketException(TicketException.Code.SUPERSEDED, "Repository access changed.");
        return captured;
    }
    private RepositoryContentHost.Snapshot resolve(InstanceSource source, Access access, SourceRevision revision, TicketRequestGate gate) {
        var captured = checked(source, access, gate);
        if (revision != null && (!revision.immutable() || !"git-commit".equals(revision.kind()))) throw BacklogReader.invalid();
        var snapshot = captured.lease().resolve(revision == null ? null : revision.value(), gate);
        if (!snapshot.revision().immutable() || !"git-commit".equals(snapshot.revision().kind())
                || revision != null && !revision.equals(snapshot.revision())) {
            snapshot.close();
            throw new TicketException(TicketException.Code.STALE_CONTEXT, "Requested repository revision is unavailable.");
        }
        return snapshot;
    }
    @Override public ProviderSnapshot read(ProviderInput input, TicketRequestGate gate) { return read(input, gate, captureAccess(input.source())); }
    @Override public ProviderSnapshot read(ProviderInput input, TicketRequestGate gate, Access access) {
        var settings = new BacklogSettings(input.source().repositorySource());
        try (var snapshot = resolve(input.source(), access, null, gate)) {
            var index = BacklogReader.read(snapshot, input.source().repositorySource(), settings, null, gate);
            var results = new ArrayList<ProjectResult>();
            for (var project : input.projects()) {
                binding(settings, index, project.externalProjectId(), project.projectKey());
                var unresolved = new LinkedHashSet<>(project.issueKeys());
                var issues = new ArrayList<IssueObservation>();
                for (var task : index.tasks()) {
                    if (!task.entityKind().equals("task")) continue;
                    boolean requested = project.issueKeys().stream().anyMatch(key -> BacklogIds.candidate(key).filter(task.id()::equals).isPresent());
                    if (!requested) continue;
                    unresolved.removeIf(key -> BacklogIds.candidate(key).filter(task.id()::equals).isPresent());
                    var mapping = settings.mapping(task.status());
                    issues.add(new IssueObservation(new IssueIdentity(settings.store(), externalId(input.source(), settings, task)),
                            task.id(), task.title(), task.type(), task.status(), mapping.category(), task.priority(), null,
                            task.createdAt(), task.updatedAt(), null, List.of(), null, input.capturedAt(), null, null,
                            task.labels(), snapshot.revision()));
                }
                var membership = new ReleaseMembership(project.releaseVersionName() == null ? ReleaseMappingState.NOT_REQUESTED
                        : ReleaseMappingState.UNSUPPORTED, null, List.of(), input.capturedAt());
                results.add(new ProjectResult(project.ticketProjectId(), new ProjectObservation(settings.store(), index.config().prefix(),
                        index.config().project(), null, input.capturedAt()), List.of(), issues, unresolved, membership));
            }
            checked(input.source(), access, gate);
            return new ProviderSnapshot(results);
        }
    }
    @Override public BrowseDescriptor describe(BrowserRequest request, Access access, TicketRequestGate gate) {
        checked(request.source(), access, gate);
        var settings = new BacklogSettings(request.source().repositorySource());
        if (!settings.store().equals(request.externalProjectId())) throw BacklogSettings.error("Ticket project does not match the configured store.");
        return new BrowseDescriptor(List.of("text", "lifecycle", "statuses", "categories", "labels"),
                settings.drafts() ? List.of(Lifecycle.ACTIVE, Lifecycle.ARCHIVED, Lifecycle.DRAFT) : List.of(Lifecycle.ACTIVE, Lifecycle.ARCHIVED),
                List.of("key-asc", "title-asc"), true, true, true);
    }
    @Override public TicketPage list(BrowserRequest request, BrowseQuery query, Access access, TicketRequestGate gate) {
        var settings = new BacklogSettings(request.source().repositorySource());
        if (!Set.of("key-asc", "title-asc").contains(query.sort()) || query.lifecycle() == Lifecycle.DRAFT && !settings.drafts())
            throw BacklogSettings.error("Unsupported browse query.");
        if (query.continuation() != null && request.revision() == null) throw stale();
        try (var snapshot = resolve(request.source(), access, request.revision(), gate)) {
            var index = BacklogReader.read(snapshot, request.source().repositorySource(), settings, null, gate);
            binding(settings, index, request.externalProjectId(), request.projectKey());
            String search = query.text().toLowerCase(Locale.ROOT);
            var ordered = index.tasks().stream().map(task -> summary(request, settings, task, snapshot.revision()))
                    .filter(item -> item.lifecycle() == query.lifecycle())
                    .filter(item -> query.statuses().isEmpty() || query.statuses().contains(item.nativeStatus()))
                    .filter(item -> query.categories().isEmpty() || query.categories().contains(item.category()))
                    .filter(item -> query.labels().isEmpty() || item.labels().stream().anyMatch(query.labels()::contains))
                    .filter(item -> Normalizer.normalize(item.displayKey() + " " + item.title(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT).contains(search))
                    .sorted(query.sort().equals("title-asc") ? Comparator.comparing(TicketSummary::title).thenComparing(item -> item.ref().externalIssueId())
                            : Comparator.comparing(TicketSummary::displayKey).thenComparing(item -> item.ref().externalIssueId())).toList();
            int start = position(query, request, snapshot.revision(), access.context());
            if (start > ordered.size()) throw stale();
            int end = Math.min(ordered.size(), start + query.pageSize());
            String next = end < ordered.size() ? cursor(end, query, request, snapshot.revision(), access.context()) : null;
            checked(request.source(), access, gate);
            return new TicketPage(ordered.subList(start, end), next, (long) ordered.size(), snapshot.revision(), request.capturedAt(), query);
        }
    }
    @Override public TicketDetail detail(BrowserRequest request, TicketRef ticket, Access access, TicketRequestGate gate) {
        try (var snapshot = resolve(request.source(), access, request.revision(), gate)) {
            var settings = new BacklogSettings(request.source().repositorySource());
            var task = find(request, ticket, snapshot, settings, gate, false).task();
            checked(request.source(), access, gate);
            return new TicketDetail(summary(request, settings, task, snapshot.revision()), task.createdAt(), null, List.of(), new Navigation(null, null));
        }
    }
    @Override public TicketContent content(BrowserRequest request, TicketRef ticket, Access access, TicketRequestGate gate) {
        requireRevision(request);
        try (var snapshot = resolve(request.source(), access, request.revision(), gate)) {
            var settings = new BacklogSettings(request.source().repositorySource());
            var found = find(request, ticket, snapshot, settings, gate, true);
            var task = found.task();
            var manifest = BacklogAssets.manifest(snapshot, request.source().repositorySource(), task.path(), task.body(), ticket, access.context(), signer, gate, ticketPaths(request, settings, found.index()));
            checked(request.source(), access, gate);
            return new TicketContent(ticket, snapshot.revision(), ContentFormat.MARKDOWN, task.body(), manifest.assets(), manifest.links());
        }
    }
    @Override public TicketAsset asset(BrowserRequest request, TicketRef ticket, String assetId, Access access, TicketRequestGate gate) {
        requireRevision(request);
        var snapshot = resolve(request.source(), access, request.revision(), gate);
        boolean handedOff = false;
        try {
            var settings = new BacklogSettings(request.source().repositorySource());
            var found = find(request, ticket, snapshot, settings, gate, true);
            var task = found.task();
            var manifest = BacklogAssets.manifest(snapshot, request.source().repositorySource(), task.path(), task.body(), ticket, access.context(), signer, gate, ticketPaths(request, settings, found.index()));
            var metadata = manifest.assets().stream().filter(value -> value.id().equals(assetId)).findFirst().orElseThrow(BacklogAdapter::notFound);
            var delegate = BacklogAssets.open(snapshot, ticket, metadata, manifest.paths().get(assetId), gate);
            try { checked(request.source(), access, gate); }
            catch (RuntimeException failure) {
                try { delegate.close(); } catch (IOException ignored) { /* Preserve the access failure. */ }
                throw failure;
            }
            handedOff = true;
            return new TicketAsset() {
                private boolean closed;
                private final InputStream stream = new FilterInputStream(delegate.stream()) {
                    private void check() throws IOException {
                        if (closed) throw new IOException("Asset stream is closed.");
                        checked(request.source(), access, gate);
                    }
                    @Override public int read() throws IOException { check(); int result = super.read(); check(); return result; }
                    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                        check(); int result = in.read(bytes, offset, length); check(); return result;
                    }
                    @Override public void close() throws IOException { closeAsset(); }
                };
                private void closeAsset() throws IOException { if (!closed) { closed = true; try { delegate.close(); } finally { snapshot.close(); } } }
                @Override public TicketRef ref() { return delegate.ref(); }
                @Override public SourceRevision revision() { return delegate.revision(); }
                @Override public AssetRef metadata() { return delegate.metadata(); }
                @Override public InputStream stream() { return stream; }
                @Override public void close() throws IOException { closeAsset(); }
            };
        } finally { if (!handedOff) snapshot.close(); }
    }
    private record Found(BacklogFormat.Task task, BacklogReader.Index index) { }
    private Found find(BrowserRequest request, TicketRef ticket, RepositoryContentHost.Snapshot snapshot,
                                   BacklogSettings settings, TicketRequestGate gate, boolean content) {
        if (ticket == null || !request.source().instanceId().equals(ticket.instanceId()) || !settings.store().equals(ticket.externalProjectId())) throw notFound();
        String namespace = namespace(request.source(), settings);
        if (ticket.externalIssueId() == null || !ticket.externalIssueId().startsWith(namespace)) throw notFound();
        String contentId = content ? ticket.externalIssueId().substring(namespace.length()) : null;
        var index = BacklogReader.read(snapshot, request.source().repositorySource(), settings, contentId, gate);
        binding(settings, index, request.externalProjectId(), request.projectKey());
        var task = index.tasks().stream().filter(value -> externalId(request.source(), settings, value).equals(ticket.externalIssueId()))
                .findFirst().orElseThrow(BacklogAdapter::notFound);
        return new Found(task, index);
    }
    private Map<String, TicketRef> ticketPaths(BrowserRequest request, BacklogSettings settings, BacklogReader.Index index) {
        return index.tasks().stream().collect(java.util.stream.Collectors.toMap(BacklogFormat.Task::path,
                task -> new TicketRef(request.source().instanceId(), settings.store(), externalId(request.source(), settings, task))));
    }
    private TicketSummary summary(BrowserRequest request, BacklogSettings settings, BacklogFormat.Task task, SourceRevision revision) {
        var mapping = settings.mapping(task.status());
        return new TicketSummary(new TicketRef(request.source().instanceId(), settings.store(), externalId(request.source(), settings, task)),
                task.id(), task.title(), task.status(), mapping.category(), Lifecycle.valueOf(task.lifecycle()), mapping.outcome(), task.labels(),
                null, task.updatedAt(), true, revision);
    }
    private static void binding(BacklogSettings settings, BacklogReader.Index index, String project, String key) {
        if (!settings.store().equals(project) || key == null || !index.config().prefix().equalsIgnoreCase(key))
            throw BacklogSettings.error("Ticket project does not match the configured store and prefix.");
    }
    private static String namespace(InstanceSource source, BacklogSettings settings) { return source.repositorySource().repositoryId() + ":" + settings.store() + ":"; }
    private static String externalId(InstanceSource source, BacklogSettings settings, BacklogFormat.Task task) { return namespace(source, settings) + task.entityKind() + ":" + task.id(); }
    private String cursor(int offset, BrowseQuery query, BrowserRequest request, SourceRevision revision, String context) {
        var parts = new ArrayList<String>(List.of(request.source().instanceId().toString(), request.ticketProjectId().toString(), request.externalProjectId(),
                revision.kind(), revision.value(), context, Long.toString(request.source().revision()), query.text(), query.lifecycle().name(), query.sort(),
                Integer.toString(query.pageSize()), Integer.toString(offset)));
        parts.add("statuses"); parts.add(Integer.toString(query.statuses().size())); parts.addAll(query.statuses());
        parts.add("categories"); parts.add(Integer.toString(query.categories().size())); parts.addAll(query.categories());
        parts.add("labels"); parts.add(Integer.toString(query.labels().size())); parts.addAll(query.labels());
        return offset + "." + signer.sign(provider().name(), "backlog-page-v1", parts);
    }
    private int position(BrowseQuery query, BrowserRequest request, SourceRevision revision, String context) {
        if (query.continuation() == null) return 0;
        try {
            String value = query.continuation();
            int dot = value.indexOf('.');
            if (dot < 1 || dot > 8) throw stale();
            int offset = Integer.parseInt(value.substring(0, dot));
            if (offset < 0 || !MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8), cursor(offset, query, request, revision, context).getBytes(StandardCharsets.UTF_8))) throw stale();
            return offset;
        } catch (NumberFormatException failure) { throw stale(); }
    }
    private static void requireRevision(BrowserRequest request) { if (request.revision() == null) throw stale(); }
    private static TicketException stale() { return new TicketException(TicketException.Code.STALE_CONTEXT, "Restart browsing with an explicit source revision."); }
    private static TicketException notFound() { return new TicketException(TicketException.Code.NOT_FOUND, "Ticket or asset is unavailable at this revision."); }
}
