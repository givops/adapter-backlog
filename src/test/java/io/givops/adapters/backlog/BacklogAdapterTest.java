package io.givops.adapters.backlog;

import io.givops.adapters.testkit.AdapterTestkit;
import io.givops.ticketing.model.TicketBrowserModels.*;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketModels.*;
import io.givops.ticketing.port.TicketProviderAdapter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BacklogAdapterTest {
    final MemoryRepository host = new MemoryRepository();
    final BacklogAdapter adapter = new BacklogAdapter(host, AdapterTestkit.signer("test-signing-key"));
    final UUID instance = UUID.randomUUID();
    final UUID repository = UUID.randomUUID();
    final UUID project = UUID.randomUUID();
    final Instant now = Instant.parse("2026-11-17T10:00:00Z");
    InstanceSource source;
    TicketProviderAdapter.Access access;
    @BeforeEach void setup() {
        source = source(Map.of("storeId", "docs", "identityPolicy", "no-reuse", "workflow", "givops"));
        access = adapter.captureAccess(source);
        host.put("backlog/config.yml", "project_name: givops\ntask_prefix: task\nstatuses: [cancelled, draft, todo, in_progress, blocked, done]\n");
        task("tasks", "TASK-1", "todo", "# Narrative\nSECRET-BODY-ONE\n");
        task("tasks", "TASK-1.02", "cancelled", "# Cancelled\n");
        task("archive/tasks", "TASK-2", "done", "# Full notes\n");
    }
    InstanceSource source(Map<String,String> settings) {
        return new InstanceSource(instance, "backlog", BacklogDefinition.PROVIDER, null, 1,
                new RepositorySource(repository, "refs/heads/main", "backlog", "backlog/config.yml", List.of("backlog/assets"), settings));
    }
    void task(String folder, String id, String status, String body) {
        host.put("backlog/" + folder + "/" + id.toLowerCase(java.util.Locale.ROOT) + " - Test.md",
                "---\nid: " + id + "\ntitle: Task " + id + "\nstatus: " + status + "\nlabels: [java]\ncreated_date: '2026-11-17'\n---\n" + body);
    }
    BrowserRequest request(SourceRevision revision) { return new BrowserRequest(source, project, "docs", "TASK", revision, now); }
    BrowseQuery query(Lifecycle lifecycle,int size,String continuation) { return new BrowseQuery("", lifecycle, List.of(),List.of(),List.of(),"key-asc",size,continuation); }
    TicketPage page() { return adapter.list(request(null), query(null,100,null), access, MemoryRepository.gate()); }
    @Test void ticketLinksTakePriorityOverOverlappingAssetRoots() {
        var repositorySource = source.repositorySource();
        source = new InstanceSource(instance, "backlog", BacklogDefinition.PROVIDER, null, 1,
                new RepositorySource(repository, repositorySource.ref(), "backlog", "backlog/config.yml", List.of("backlog"), repositorySource.settings()));
        access = adapter.captureAccess(source);
        task("tasks", "TASK-1", "todo", "[archived](<../archive/tasks/task-2 - Test.md>)");
        var page = page();
        var ticket = page.items().stream().filter(t -> t.displayKey().equals("TASK-1")).findFirst().orElseThrow().ref();
        var content = adapter.content(request(page.revision()), ticket, access, MemoryRepository.gate());
        assertThat(content.assets()).isEmpty();
        assertThat(content.links()).singleElement().satisfies(link -> {
            assertThat(link.assetId()).isNull();
            assertThat(link.ticket().externalIssueId()).endsWith(":task:TASK-2");
        });
    }

    @Test void metadataOnlyObservationsIncludeArchivesAndHonestReleaseSupport() {
        var result = adapter.read(new ProviderInput(source, List.of(new ProjectQuery(project, "docs", "TASK", Set.of("TASK-1", "task-01.2", "TASK-2", "TASK-99"), "v1")), now), MemoryRepository.gate(), access).projects().getFirst();
        assertThat(result.issues()).hasSize(3).allSatisfy(issue -> { assertThat(issue.description()).isNull(); assertThat(issue.sourceRevision().value()).isEqualTo(host.head); assertThat(issue.createdAt()).isNull(); });
        assertThat(result.unresolvedKeys()).containsExactly("TASK-99");
        assertThat(result.releaseMembership().state()).isEqualTo(ReleaseMappingState.UNSUPPORTED);
        assertThat(result.issues()).filteredOn(i -> i.issueKey().equals("TASK-1.2")).singleElement().satisfies(i -> assertThat(i.statusCategory()).isEqualTo("indeterminate"));
        assertThat(adapter.current(source, access, true)).isTrue(); assertThat(host.lastLocked).isTrue();
    }
    @Test void completeIndexRejectsMalformedUnrequestedAndDuplicateArchivedRecords() {
        task("archive/tasks", "TASK-01", "todo", "duplicate");
        assertThatThrownBy(this::page).isInstanceOf(TicketException.class);
        host.revisions.get(host.head).remove("backlog/archive/tasks/task-01 - Test.md");
        host.put("backlog/tasks/task-99 - Invalid.md", "---\nid: TASK-99\nstatus: todo\n---\nSECRET-MALFORMED");
        assertThatThrownBy(this::page).isInstanceOf(TicketException.class).hasMessageNotContaining("SECRET");
    }
    @Test void dottedRoutingAndCancelledOutcomeAreNotParentOrSuccess() {
        var definition = new BacklogDefinition();
        assertThat(definition.normalizeReferenceCandidate("task-001.02")).contains("TASK-1.2");
        assertThat(definition.normalizeReferenceCandidate("TASK-1..2")).isEmpty();
        assertThat(definition.normalizeIssueKey("TASK-1.2", "OTHER")).isEmpty();
        assertThat(page().items()).filteredOn(t -> t.displayKey().equals("TASK-1.2")).singleElement().satisfies(t -> assertThat(t.outcome()).isEqualTo(Outcome.CANCELLED));
    }
    @Test void paginationBindsRevisionQueryContextAndReturnsAllInventory() {
        var first = adapter.list(request(null),query(null,1,null),access,MemoryRepository.gate());
        var next = adapter.list(request(first.revision()),query(null,1,first.nextContinuation()),access,MemoryRepository.gate());
        assertThat(next.items().getFirst().displayKey()).isNotEqualTo(first.items().getFirst().displayKey());
        assertThat(next.nextContinuation()).isNull();
        assertThatThrownBy(() -> adapter.list(request(null), query(null,1,first.nextContinuation()),access,MemoryRepository.gate())).isInstanceOf(TicketException.class);
        assertThatThrownBy(() -> adapter.list(request(first.revision()),query(Lifecycle.ARCHIVED,1,first.nextContinuation()),access,MemoryRepository.gate())).isInstanceOf(TicketException.class);
    }
    @Test void freshDetailResolvesMovedRefOnceWhilePinnedDetailKeepsOriginalRevision() {
        var first = page();
        var ticket = first.items().stream().filter(item -> item.displayKey().equals("TASK-1")).findFirst().orElseThrow().ref();
        host.move();
        task("tasks", "TASK-1", "done", "NEW-HEAD-BODY");
        int closedBefore = host.snapshotsClosed;
        var fresh = adapter.detail(request(null), ticket, access, MemoryRepository.gate());
        assertThat(host.snapshotsClosed).isEqualTo(closedBefore + 1);
        assertThat(fresh.ticket().revision().value()).isEqualTo(host.head).isNotEqualTo(first.revision().value());
        assertThat(fresh.ticket().nativeStatus()).isEqualTo("done");
        var pinned = adapter.detail(request(first.revision()), ticket, access, MemoryRepository.gate());
        assertThat(pinned.ticket().revision()).isEqualTo(first.revision());
        assertThat(pinned.ticket().nativeStatus()).isEqualTo("todo");
        var content = adapter.content(request(fresh.ticket().revision()), ticket, access, MemoryRepository.gate());
        assertThat(content.text()).contains("NEW-HEAD-BODY").doesNotContain("SECRET-BODY-ONE");
    }

    @Test void contentReadsPinnedRevisionRetainsCompleteSourceAndNotHead() {
        var first = page(); var ticket = first.items().getFirst().ref();
        host.move(); task("tasks", "TASK-1", "done", "NEW-HEAD-BODY");
        var content = adapter.content(request(first.revision()),ticket,access,MemoryRepository.gate());
        assertThat(content.text()).contains("SECRET-BODY-ONE", "created_date: '2026-11-17'").doesNotContain("NEW-HEAD-BODY");
        assertThat(content.toString()).doesNotContain("SECRET");
        assertThatThrownBy(() -> adapter.content(request(null),ticket,access,MemoryRepository.gate())).isInstanceOf(TicketException.class);
        assertThatThrownBy(() -> adapter.content(request(first.revision()),new TicketRef(UUID.randomUUID(),"docs",ticket.externalIssueId()),access,MemoryRepository.gate())).isInstanceOf(TicketException.class);
    }
    @Test void accessRevocationDuringScanFailsAndClosesSnapshot() {
        host.afterOpen = () -> host.current = false;
        assertThatThrownBy(this::page).isInstanceOf(TicketException.class);
        assertThat(host.snapshotsClosed).isEqualTo(1);
        assertThat(host.blobsClosed).isGreaterThan(0);
    }
    @Test void assetsAreRevisionAndTicketScopedAndStreamsRecheckAccess() throws Exception {
        task("tasks", "TASK-1", "todo", "![proof](../assets/proof.txt)\n");
        host.put("backlog/assets/proof.txt", "PINNED-ASSET");
        var first = page(); var ticket = first.items().getFirst().ref(); var req = request(first.revision());
        var content = adapter.content(req,ticket,access,MemoryRepository.gate());
        assertThat(content.assets()).hasSize(1);
        String assetId = content.assets().getFirst().id();
        host.move(); host.put("backlog/assets/proof.txt", "NEW-ASSET");
        try (var asset = adapter.asset(req,ticket,assetId,access,MemoryRepository.gate())) {
            assertThat(new String(asset.stream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("PINNED-ASSET");
        }
        try (var asset = adapter.asset(req,ticket,assetId,access,MemoryRepository.gate())) {
            host.current = false;
            assertThatThrownBy(() -> asset.stream().read()).isInstanceOf(TicketException.class);
        }
        host.current = true; host.context = "rotated";
        var replacement = adapter.captureAccess(source);
        assertThatThrownBy(() -> adapter.asset(req,ticket,assetId,replacement,MemoryRepository.gate())).isInstanceOf(TicketException.class);
    }
    @Test void managedIdeaStatusPreservesTaskIdentityAndHistoricalDraftReadability() {
        task("tasks", "TASK-1", "draft", "Historical task body");
        task("drafts", "DRAFT-3", "draft", "Separate draft remains excluded");
        var before = page();
        var original = before.items().stream().filter(item -> item.displayKey().equals("TASK-1")).findFirst().orElseThrow();
        host.move();
        host.put("backlog/config.yml", "project_name: givops\ntask_prefix: task\nstatuses: [cancelled, idea, todo, in_progress, blocked, done]\ndefault_status: todo\n");
        task("tasks", "TASK-1", "idea", "Managed idea task body");
        var after = page();
        assertThat(after.items()).noneMatch(item -> item.displayKey().startsWith("DRAFT-"));
        var idea = after.items().stream().filter(item -> item.displayKey().equals("TASK-1")).findFirst().orElseThrow();
        assertThat(idea.ref()).isEqualTo(original.ref());
        assertThat(idea.nativeStatus()).isEqualTo("idea");
        assertThat(idea.lifecycle()).isEqualTo(Lifecycle.ACTIVE);
        assertThat(idea.category()).isEqualTo("new");
        assertThat(idea.outcome()).isEqualTo(Outcome.OPEN);
        var historical = adapter.detail(request(before.revision()), original.ref(), access, MemoryRepository.gate());
        assertThat(historical.ticket().nativeStatus()).isEqualTo("draft");
        assertThat(historical.ticket().outcome()).isEqualTo(Outcome.OPEN);
        var observations = adapter.read(new ProviderInput(source, List.of(new ProjectQuery(project, "docs", "TASK", Set.of("TASK-1"), null)), now), MemoryRepository.gate(), access);
        assertThat(observations.projects().getFirst().issues()).singleElement().satisfies(issue -> {
            assertThat(issue.statusCategory()).isEqualTo("new");
            assertThat(issue.description()).isNull();
        });
    }

    @Test void draftsAreOptInAndNotOrdinaryTaskObservations() {
        task("drafts", "DRAFT-3", "draft", "Draft notes");
        assertThat(page().items()).hasSize(2);
        source = source(Map.of("storeId","docs","identityPolicy","no-reuse","workflow","givops","includeDrafts","true"));
        access = adapter.captureAccess(source);
        var drafts = adapter.list(request(null),query(Lifecycle.DRAFT,100,null),access,MemoryRepository.gate());
        assertThat(drafts.items()).singleElement().satisfies(t -> assertThat(t.displayKey()).isEqualTo("DRAFT-3"));
    }
    @Test void settingsRequireExplicitIdentityAndCompleteStatusMapping() {
        assertThatThrownBy(() -> new BacklogSettings(source(Map.of("storeId","docs","workflow","givops")).repositorySource())).isInstanceOf(TicketException.class);
        host.put("backlog/config.yml", "project_name: givops\nstatuses: [todo, mystery]\n");
        assertThatThrownBy(this::page).isInstanceOf(TicketException.class);
    }
    @Test void suspiciousTaskFilesAndObstructedArchiveCannotLookEmpty() {
        host.put("backlog/tasks/task-77.MD", "malformed extension");
        assertThatThrownBy(this::page).isInstanceOf(TicketException.class);
        host.revisions.get(host.head).remove("backlog/tasks/task-77.MD");
        host.entriesTransform = entries -> {
            var changed = new java.util.ArrayList<>(entries);
            changed.add(new io.givops.ticketing.port.RepositoryContentHost.Entry("backlog/archive",
                    io.givops.ticketing.port.RepositoryContentHost.EntryKind.SYMLINK, 1, "fake"));
            return changed;
        };
        assertThatThrownBy(this::page).isInstanceOf(TicketException.class);
    }
    @Test void returnedPathsAreValidatedBeforeScopeFiltering() {
        host.entriesTransform = entries -> {
            var changed = new java.util.ArrayList<>(entries);
            changed.add(new io.givops.ticketing.port.RepositoryContentHost.Entry("backlog/../tasks/task-9.md",
                    io.givops.ticketing.port.RepositoryContentHost.EntryKind.FILE, 1, "fake"));
            return changed;
        };
        assertThatThrownBy(this::page).isInstanceOf(TicketException.class);
    }
    @Test void completeLargeInventoryExceedsOnePageAndEveryBlobCloses() {
        for (int n = 10; n < 150; n++) task("tasks", "TASK-" + n, "todo", "note");
        var first = page();
        assertThat(first.exactTotal()).isEqualTo(142L);
        assertThat(first.items()).hasSize(100);
        var next = adapter.list(request(first.revision()),query(null,100,first.nextContinuation()),access,MemoryRepository.gate());
        assertThat(next.items()).hasSize(42);
        assertThat(next.nextContinuation()).isNull();
        assertThat(host.blobsClosed).isEqualTo(288);
    }
    @Test void changedStoreRejectsOldBindingsAndTicketLinksResolveOnlyWithinSnapshot() {
        task("tasks", "TASK-1", "todo", "[archive](../archive/tasks/task-2%20-%20Test.md)\n");
        var first = page(); var ticket = first.items().getFirst().ref();
        var body = adapter.content(request(first.revision()),ticket,access,MemoryRepository.gate());
        assertThat(body.links()).singleElement().satisfies(link -> assertThat(link.ticket().externalIssueId()).endsWith(":task:TASK-2"));
        source = source(Map.of("storeId","other-store","identityPolicy","no-reuse","workflow","givops"));
        access = adapter.captureAccess(source);
        assertThatThrownBy(this::page).isInstanceOf(TicketException.class);
        assertThatThrownBy(() -> adapter.content(request(first.revision()),ticket,access,MemoryRepository.gate())).isInstanceOf(TicketException.class);
    }
    @Test void missingRevisionNeverFallsBackAndOtherStoreCannotResolve() {
        var first = page(); var ticket = first.items().getFirst().ref();
        assertThatThrownBy(() -> adapter.detail(request(new SourceRevision("git-commit","f".repeat(40),true)),ticket,access,MemoryRepository.gate())).isInstanceOf(TicketException.class);
        assertThatThrownBy(() -> adapter.detail(request(first.revision()),new TicketRef(instance,"different",ticket.externalIssueId()),access,MemoryRepository.gate())).isInstanceOf(TicketException.class);
    }
}
