package io.givops.adapters.backlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.givops.adapters.ContextSigner;
import io.givops.ticketing.model.TicketBrowserModels.AssetRef;
import io.givops.ticketing.model.TicketBrowserModels.SourceRevision;
import io.givops.ticketing.model.TicketBrowserModels.TicketAsset;
import io.givops.ticketing.model.TicketBrowserModels.TicketRef;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketModels.RepositorySource;
import io.givops.ticketing.port.RepositoryContentHost.Blob;
import io.givops.ticketing.port.RepositoryContentHost.Entry;
import io.givops.ticketing.port.RepositoryContentHost.EntryKind;
import io.givops.ticketing.port.RepositoryContentHost.Snapshot;
import io.givops.ticketing.port.TicketRequestGate;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BacklogAssetsTest {
    private static final UUID REPOSITORY = UUID.fromString("d2cf6465-af2e-420f-90ba-f5b031b77f13");
    private static final TicketRef TICKET = new TicketRef(UUID.fromString("cabf35ea-24e8-45bf-aa97-a19d0573290b"), "project", "issue-1");
    private static final RepositorySource SOURCE = source(List.of());
    private static final ContextSigner SIGNER = (provider, namespace, parts) -> {
        assertThat(provider).isEqualTo("BACKLOG_MD");
        assertThat(namespace).isEqualTo("asset");
        return hash(String.join("\u0000", parts).getBytes(StandardCharsets.UTF_8));
    };

    @Test void deeplyNestedMarkdownFailsWithBoundedError() {
        assertThatThrownBy(() -> manifest(new TestSnapshot(), "> ".repeat(4000) + "nested"))
                .isInstanceOfSatisfying(TicketException.class, e -> assertThat(e.code()).isEqualTo(TicketException.Code.LIMIT_EXCEEDED));
    }

    @Test void parsesRealInlineAndReferenceLinksAndIgnoresCodeAndRawHtml() {
        TestSnapshot snapshot = new TestSnapshot();
        snapshot.file("backlog/assets/diagram one.png", "image");
        String markdown = """
                ![inline](</assets/diagram one.png>)
                [reference][diagram]

                [diagram]: ../assets/diagram%20one.png

                `[ignored](/assets/hidden.png)`

                ```md
                [ignored](/assets/hidden.png)
                ```
                <img src="/assets/hidden.png">
                """;
        BacklogAssets.Manifest manifest = manifest(snapshot, markdown);
        assertThat(manifest.assets()).hasSize(1);
        assertThat(manifest.links()).hasSize(2).allSatisfy(link -> assertThat(link.assetId()).isEqualTo(manifest.assets().getFirst().id()));
        assertThat(manifest.assets().getFirst().digest()).isEqualTo(hash("image".getBytes(StandardCharsets.UTF_8)));
        assertThat(manifest.paths()).containsOnlyKeys(manifest.assets().getFirst().id());
        assertThat(snapshot.opened).containsExactly("backlog/assets/diagram one.png");
        assertThat(snapshot.blobs).allSatisfy(blob -> {
            assertThat(blob.closeCount).isEqualTo(1);
            assertThat(blob.streamCloseCount).isEqualTo(1);
        });
    }

    @Test void confinesPathsToDefaultAndExplicitlyApprovedRootsIncludingOutsideTheStore() {
        TestSnapshot snapshot = new TestSnapshot();
        snapshot.file("backlog/assets/a.png", "a");
        snapshot.file("shared/public/a.pdf", "a");
        snapshot.file("backlog/tasks/private.txt", "secret");
        snapshot.file("backlog/tasks/images/a.png", "local");
        RepositorySource source = source(List.of("shared/public", "backlog/tasks/images"));
        BacklogAssets.Manifest manifest = manifest(snapshot, source,
                "[a](../assets/a.png) [b](../../shared/public/a.pdf) [c](private.txt) [d](images/a.png) [e](/assets/../../shared/public/a.pdf)", "context", TICKET);
        assertThat(manifest.paths().values()).containsExactlyInAnyOrder("backlog/assets/a.png", "shared/public/a.pdf", "backlog/tasks/images/a.png");
        assertThat(manifest.links().get(2).assetId()).isNull();
        assertThat(manifest.links().get(4).assetId()).isNull();
        assertThat(snapshot.opened).doesNotContain("backlog/tasks/private.txt");
        assertThat(snapshot.listed).containsExactlyInAnyOrder("backlog/assets", "shared/public", "backlog/tasks/images");
    }

    @Test void handlesStrictUtf8AndEncodedSpacesWithoutTreatingPlusAsSpace() {
        TestSnapshot snapshot = new TestSnapshot();
        snapshot.file("backlog/assets/résumé one.txt", "unicode");
        snapshot.file("backlog/assets/a+b.txt", "plus");
        BacklogAssets.Manifest manifest = manifest(snapshot,
                "[a](/assets/r%C3%A9sum%C3%A9%20one.txt) [b](/assets/a+b.txt)");
        assertThat(manifest.assets()).hasSize(2);
        assertThat(manifest.paths().values()).containsExactlyInAnyOrder("backlog/assets/résumé one.txt", "backlog/assets/a+b.txt");
    }

    @Test void rejectsEncodedTraversalSeparatorsDoubleEncodingAndUnsafeUriForms() {
        TestSnapshot snapshot = new TestSnapshot();
        snapshot.file("backlog/assets/a.txt", "a");
        snapshot.file("backlog/assets/sub/secret.png", "must not read");
        List<String> unsafe = List.of("/assets/%2e%2e/tasks/private.txt", "/assets/a%2Ffile.txt", "/assets/a%5cfile.txt",
                "/assets/%252e%252e/a.txt", "/assets/sub%２Ｆsecret.png", "/assets/%２e%２e/tasks/private.txt", "/assets/a%252ftxt", "/assets/%C0%AFa.txt", "/assets/%FF.txt",
                "/assets/a%00.txt", "/assets/a%0A.txt", "/assets/%xx.txt", "../../../assets/a.txt", "/other/a.txt",
                "//example.test/a.txt", "C:/assets/a.txt", "javascript:alert(1)", "file:///assets/a.txt",
                "\\\\server\\share\\a.txt", "/assets/a\uFFFD.txt", "https://example.test/a%0a.txt",
                "https://user:password@example.test/a.txt", "https://example.test@evil.test/a.txt");
        String markdown = unsafe.stream().map(path -> "[a](<" + path + ">)").collect(java.util.stream.Collectors.joining("\n"));
        BacklogAssets.Manifest manifest = manifest(snapshot, markdown);
        assertThat(manifest.assets()).isEmpty();
        assertThat(manifest.links()).hasSize(unsafe.size()).allSatisfy(link -> {
            assertThat(link.assetId()).isNull();
            assertThat(link.externalUrl()).isNull();
            assertThat(link.ticket()).isNull();
        });
        assertThat(snapshot.opened).isEmpty();
    }

    @Test void exportsSafeExternalLinksAndImagesWithoutFetchingThem() {
        TestSnapshot snapshot = new TestSnapshot();
        BacklogAssets.Manifest manifest = manifest(snapshot,
                "[site](https://example.test/a?q=one#part) ![remote](http://images.example.test/a.png)");
        assertThat(manifest.assets()).isEmpty();
        assertThat(manifest.links()).hasSize(2).allSatisfy(link -> assertThat(link.externalUrl()).isNotNull());
        assertThat(snapshot.listed).isEmpty();
        assertThat(snapshot.opened).isEmpty();
    }

    @Test void missingFilesAndRootsRemainExplicitUnresolvedLinks() {
        TestSnapshot snapshot = new TestSnapshot();
        snapshot.entriesFailure = new TicketException(TicketException.Code.NOT_FOUND, "private upstream details");
        BacklogAssets.Manifest manifest = manifest(snapshot, "[missing](/assets/missing.png)");
        assertThat(manifest.assets()).isEmpty();
        assertThat(manifest.links()).singleElement().satisfies(link -> {
            assertThat(link.target()).isEqualTo("/assets/missing.png");
            assertThat(link.assetId()).isNull();
        });
        snapshot.entriesFailure = null;
        assertThat(manifest(snapshot, "[missing](/assets/missing.png)").assets()).isEmpty();
    }

    @Test void refusesSymlinksSubmodulesLfsAndTheirDescendants() {
        TestSnapshot snapshot = new TestSnapshot();
        for (EntryKind kind : List.of(EntryKind.SYMLINK, EntryKind.SUBMODULE, EntryKind.LFS_POINTER)) {
            String path = "backlog/assets/" + kind.name().toLowerCase();
            snapshot.entries.put(path, new Entry(path, kind, 1, "object"));
            snapshot.file(path + "/hidden.png", "a");
        }
        String markdown = snapshot.entries.keySet().stream().map(path -> "[a](/" + path.substring("backlog/".length()) + ")")
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(manifest(snapshot, markdown).assets()).isEmpty();
        assertThat(snapshot.opened).isEmpty();
    }

    @Test void bindsHandlesToContextSourceRevisionTicketAndRawObjectIdentity() {
        TestSnapshot snapshot = new TestSnapshot();
        snapshot.file("backlog/assets/a.png", "a");
        String markdown = "[a](/assets/a.png)";
        String original = manifest(snapshot, markdown).assets().getFirst().id();
        assertThat(manifest(snapshot, SOURCE, markdown, "new access", TICKET).assets().getFirst().id()).isNotEqualTo(original);
        TicketRef other = new TicketRef(TICKET.instanceId(), "project", "issue-2");
        assertThat(manifest(snapshot, SOURCE, markdown, "context", other).assets().getFirst().id()).isNotEqualTo(original);
        RepositorySource otherSource = new RepositorySource(UUID.randomUUID(), "main", "backlog", "backlog/config.yml", List.of(), Map.of());
        assertThat(manifest(snapshot, otherSource, markdown, "context", TICKET).assets().getFirst().id()).isNotEqualTo(original);
        RepositorySource otherRef = new RepositorySource(REPOSITORY, "release", "backlog", "backlog/config.yml", List.of(), Map.of());
        assertThat(manifest(snapshot, otherRef, markdown, "context", TICKET).assets().getFirst().id()).isNotEqualTo(original);
        snapshot.file("backlog/assets/b.png", "a");
        assertThat(manifest(snapshot, "[b](/assets/b.png)").assets().getFirst().id()).isNotEqualTo(original);
        snapshot.revision = new SourceRevision("git", "revision-b", true);
        assertThat(manifest(snapshot, markdown).assets().getFirst().id()).isNotEqualTo(original);
        snapshot.revision = new SourceRevision("git", "revision-a", true);
        snapshot.entries.put("backlog/assets/a.png", new Entry("backlog/assets/a.png", EntryKind.FILE, 1, "new-object"));
        assertThat(manifest(snapshot, markdown).assets().getFirst().id()).isNotEqualTo(original);
    }

    @Test void forcesActiveFormatsToDownloadAndUsesAllowlistedMediaTypes() {
        TestSnapshot snapshot = new TestSnapshot();
        List<String> extensions = List.of("svg", "html", "js", "png", "JPG", "gif", "webp", "avif", "pdf", "txt", "md");
        for (String extension : extensions) snapshot.file("backlog/assets/a." + extension, "a");
        String markdown = extensions.stream().map(extension -> "[a](/assets/a." + extension + ")")
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(manifest(snapshot, markdown).assets()).extracting(AssetRef::mediaType).containsExactly(
                "application/octet-stream", "application/octet-stream", "application/octet-stream", "image/png", "image/jpeg",
                "image/gif", "image/webp", "image/avif", "application/pdf", "text/plain", "text/markdown");
    }

    @Test void rejectsTooManyReferencesAndCombinedManifestObjects() {
        TestSnapshot snapshot = new TestSnapshot();
        assertThatThrownBy(() -> manifest(snapshot, "[a](https://example.test)\n".repeat(101)))
                .isInstanceOfSatisfying(TicketException.class, failure -> assertThat(failure.code()).isEqualTo(TicketException.Code.LIMIT_EXCEEDED));
        for (int i = 0; i < 51; i++) snapshot.file("backlog/assets/" + i + ".png", "a");
        String markdown = java.util.stream.IntStream.range(0, 51).mapToObj(i -> "[a](/assets/" + i + ".png)")
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThatThrownBy(() -> manifest(snapshot, markdown)).isInstanceOfSatisfying(TicketException.class,
                failure -> assertThat(failure.code()).isEqualTo(TicketException.Code.LIMIT_EXCEEDED));
        assertThat(snapshot.blobs).allSatisfy(blob -> assertThat(blob.closeCount).isEqualTo(1));
    }

    @Test void rejectsIncompleteOrOversizedMetadataListsAndSanitizesHostFailures() {
        TestSnapshot snapshot = new TestSnapshot();
        for (int i = 0; i <= 10_000; i++) snapshot.entries.put("backlog/assets/" + i, new Entry("backlog/assets/" + i, EntryKind.FILE, 1, "object"));
        assertThatThrownBy(() -> manifest(snapshot, "[a](/assets/0)"))
                .isInstanceOfSatisfying(TicketException.class, failure -> assertThat(failure.code()).isEqualTo(TicketException.Code.LIMIT_EXCEEDED));
        snapshot.entriesFailure = new TicketException(TicketException.Code.AUTHORIZATION_FAILED, "credential SECRET document");
        assertThatThrownBy(() -> manifest(snapshot, "[a](/assets/0)"))
                .isInstanceOfSatisfying(TicketException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(TicketException.Code.AUTHORIZATION_FAILED);
                    assertThat(failure.getMessage()).doesNotContain("SECRET", "credential", "document");
                    assertThat(failure.getCause()).isNull();
                });
    }

    @Test void verifiesManifestBytesAndClosesOnTruncationOverflowOrObjectChange() {
        TestSnapshot snapshot = new TestSnapshot();
        snapshot.file("backlog/assets/a.txt", "hello");
        snapshot.bytes.put("backlog/assets/a.txt", "shorter-than-declared".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> manifest(snapshot, "[a](/assets/a.txt)"))
                .isInstanceOfSatisfying(TicketException.class, failure -> assertThat(failure.code()).isEqualTo(TicketException.Code.LIMIT_EXCEEDED));
        assertThat(snapshot.blobs.getLast().closeCount).isEqualTo(1);
        snapshot.bytes.put("backlog/assets/a.txt", new byte[1]);
        assertThatThrownBy(() -> manifest(snapshot, "[a](/assets/a.txt)"))
                .isInstanceOfSatisfying(TicketException.class, failure -> assertThat(failure.code()).isEqualTo(TicketException.Code.INVALID_RESPONSE));
        assertThat(snapshot.blobs.getLast().closeCount).isEqualTo(1);
        snapshot.objectOverride = "changed-object";
        assertThatThrownBy(() -> manifest(snapshot, "[a](/assets/a.txt)"))
                .isInstanceOfSatisfying(TicketException.class, failure -> assertThat(failure.code()).isEqualTo(TicketException.Code.REVISION_CONFLICT));
        assertThat(snapshot.blobs.getLast().closeCount).isEqualTo(1);
    }

    @Test void boundsAggregateManifestHashingBeforeOpeningAnotherBlob() {
        TestSnapshot snapshot = new TestSnapshot();
        byte[] data = new byte[10 * 1024 * 1024];
        for (int i = 0; i < 4; i++) {
            String path = "backlog/assets/" + i + ".pdf";
            snapshot.entries.put(path, new Entry(path, EntryKind.FILE, data.length, "object-" + i));
            snapshot.bytes.put(path, data);
        }
        String markdown = java.util.stream.IntStream.range(0, 4).mapToObj(i -> "[a](/assets/" + i + ".pdf)")
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThatThrownBy(() -> manifest(snapshot, markdown)).isInstanceOfSatisfying(TicketException.class,
                failure -> assertThat(failure.code()).isEqualTo(TicketException.Code.LIMIT_EXCEEDED));
        assertThat(snapshot.opened).hasSize(3);
        assertThat(snapshot.blobs).allSatisfy(blob -> assertThat(blob.closeCount).isEqualTo(1));
    }

    @Test void verifiesEmptyAssetAndRejectsOpenMetadataSizeChanges() throws Exception {
        TestSnapshot snapshot = new TestSnapshot();
        snapshot.file("backlog/assets/empty.txt", "");
        AssetRef metadata = manifest(snapshot, "[empty](/assets/empty.txt)").assets().getFirst();
        try (TicketAsset asset = BacklogAssets.open(snapshot, TICKET, metadata, "backlog/assets/empty.txt", new TestGate())) {
            assertThat(asset.stream().read()).isEqualTo(-1);
            assertThat(asset.stream().read()).isEqualTo(-1);
        }
        snapshot.entries.put("backlog/assets/empty.txt", new Entry("backlog/assets/empty.txt", EntryKind.FILE, 1, "object"));
        assertThatThrownBy(() -> BacklogAssets.open(snapshot, TICKET, metadata, "backlog/assets/empty.txt", new TestGate()))
                .isInstanceOfSatisfying(TicketException.class, failure -> assertThat(failure.code()).isEqualTo(TicketException.Code.REVISION_CONFLICT));
        assertThat(snapshot.blobs.getLast().closeCount).isEqualTo(1);
    }

    @Test void streamsVerifiedBytesAndClosesBlobOnceWithoutTakingSnapshotOwnership() throws Exception {
        TestSnapshot snapshot = new TestSnapshot();
        snapshot.file("backlog/assets/a.txt", "hello");
        AssetRef metadata = manifest(snapshot, "[a](/assets/a.txt)").assets().getFirst();
        TicketAsset asset = BacklogAssets.open(snapshot, TICKET, metadata, "backlog/assets/a.txt", new TestGate());
        assertThat(asset.stream().readAllBytes()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        assertThat(asset.ref()).isEqualTo(TICKET);
        assertThat(asset.revision()).isEqualTo(snapshot.revision());
        assertThat(asset.metadata()).isEqualTo(metadata);
        asset.stream().close();
        asset.close();
        assertThat(snapshot.blobs.getLast().closeCount).isEqualTo(1);
        assertThat(snapshot.blobs.getLast().streamCloseCount).isEqualTo(1);
        assertThat(snapshot.closed).isFalse();
    }

    @Test void refusesSameRevisionSameSizeChangedBytesByDigestAndClosesOnFailure() throws Exception {
        TestSnapshot snapshot = new TestSnapshot();
        snapshot.file("backlog/assets/a.txt", "hello");
        AssetRef metadata = manifest(snapshot, "[a](/assets/a.txt)").assets().getFirst();
        snapshot.bytes.put("backlog/assets/a.txt", "other".getBytes(StandardCharsets.UTF_8));
        TicketAsset asset = BacklogAssets.open(snapshot, TICKET, metadata, "backlog/assets/a.txt", new TestGate());
        assertThatThrownBy(() -> asset.stream().readAllBytes()).isInstanceOf(IOException.class).hasMessage("Cannot read bounded repository asset.");
        assertThat(snapshot.blobs.getLast().closeCount).isEqualTo(1);
        assertThat(snapshot.blobs.getLast().streamCloseCount).isEqualTo(1);
    }

    @Test void enforcesStreamingBoundsEvenWhenBlobMetadataClaimsTheExpectedSize() {
        for (String actual : List.of("a", "hello and unexpected extra bytes")) {
            TestSnapshot snapshot = new TestSnapshot();
            snapshot.file("backlog/assets/a.txt", "hello");
            AssetRef metadata = manifest(snapshot, "[a](/assets/a.txt)").assets().getFirst();
            snapshot.bytes.put("backlog/assets/a.txt", actual.getBytes(StandardCharsets.UTF_8));
            TicketAsset asset = BacklogAssets.open(snapshot, TICKET, metadata, "backlog/assets/a.txt", new TestGate());
            assertThatThrownBy(() -> asset.stream().readAllBytes()).isInstanceOf(IOException.class);
            assertThat(snapshot.blobs.getLast().closeCount).isEqualTo(1);
            assertThat(snapshot.blobs.getLast().streamCloseCount).isEqualTo(1);
        }
    }

    @Test void closesAssetWhenTheRequestBudgetExpiresDuringRead() {
        TestSnapshot snapshot = new TestSnapshot();
        snapshot.file("backlog/assets/a.txt", "hello");
        AssetRef metadata = manifest(snapshot, "[a](/assets/a.txt)").assets().getFirst();
        TestGate gate = new TestGate();
        TicketAsset asset = BacklogAssets.open(snapshot, TICKET, metadata, "backlog/assets/a.txt", gate);
        gate.expired = true;
        assertThatThrownBy(() -> asset.stream().read()).isInstanceOfSatisfying(TicketException.class,
                failure -> assertThat(failure.code()).isEqualTo(TicketException.Code.TIMEOUT));
        assertThat(snapshot.blobs.getLast().closeCount).isEqualTo(1);
        assertThat(snapshot.blobs.getLast().streamCloseCount).isEqualTo(1);
    }

    private static RepositorySource source(List<String> roots) {
        return new RepositorySource(REPOSITORY, "main", "backlog", "backlog/config.yml", roots, Map.of());
    }
    private static BacklogAssets.Manifest manifest(TestSnapshot snapshot, String markdown) {
        return manifest(snapshot, SOURCE, markdown, "context", TICKET);
    }
    private static BacklogAssets.Manifest manifest(TestSnapshot snapshot, RepositorySource source, String markdown,
                                                  String context, TicketRef ticket) {
        return BacklogAssets.manifest(snapshot, source, "backlog/tasks/issue-1.md", markdown, ticket, context, SIGNER, new TestGate());
    }
    private static String hash(byte[] bytes) {
        try { return "sha256:" + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
    }

    private static final class TestGate implements TicketRequestGate {
        boolean expired;
        @Override public Duration beforeRequest() { check(); return Duration.ZERO; }
        @Override public void receivedBytes(long count) { }
        @Override public void acceptedObjects(int count) { }
        @Override public void check() {
            if (expired) throw new TicketException(TicketException.Code.TIMEOUT, "SECRET timeout context");
        }
        @Override public Duration remaining() { return Duration.ofSeconds(30); }
    }

    private static final class TestSnapshot implements Snapshot {
        SourceRevision revision = new SourceRevision("git", "revision-a", true);
        final Map<String, Entry> entries = new LinkedHashMap<>();
        final Map<String, byte[]> bytes = new LinkedHashMap<>();
        final List<String> opened = new ArrayList<>();
        final List<String> listed = new ArrayList<>();
        final List<TestBlob> blobs = new ArrayList<>();
        TicketException entriesFailure;
        String objectOverride;
        boolean closed;
        void file(String path, String contents) {
            byte[] data = contents.getBytes(StandardCharsets.UTF_8);
            entries.put(path, new Entry(path, EntryKind.FILE, data.length, "git-object-" + path));
            bytes.put(path, data);
        }
        @Override public SourceRevision revision() { return revision; }
        @Override public List<Entry> entries(String root, int max, TicketRequestGate gate) {
            assertThat(max).isEqualTo(10_000);
            listed.add(root);
            if (entriesFailure != null) throw entriesFailure;
            return entries.values().stream().filter(entry -> entry.path().equals(root) || entry.path().startsWith(root + "/")).toList();
        }
        @Override public Blob open(String path, long max, TicketRequestGate gate) {
            assertThat(max).isEqualTo(10L * 1024 * 1024);
            opened.add(path);
            Entry entry = entries.get(path);
            if (entry == null) throw new TicketException(TicketException.Code.NOT_FOUND, "Missing fixture");
            TestBlob blob = new TestBlob(bytes.get(path), entry.size(), objectOverride == null ? entry.objectId() : objectOverride);
            blobs.add(blob);
            return blob;
        }
        @Override public void close() { closed = true; }
    }

    private static final class TestBlob implements Blob {
        final long declaredSize;
        final String objectId;
        final InputStream stream;
        int closeCount;
        int streamCloseCount;
        TestBlob(byte[] bytes, long size, String objectId) {
            this.declaredSize = size;
            this.objectId = objectId;
            this.stream = new ByteArrayInputStream(bytes) {
                @Override public void close() { streamCloseCount++; }
            };
        }
        @Override public InputStream stream() { return stream; }
        @Override public long size() { return declaredSize; }
        @Override public String objectId() { return objectId; }
        @Override public void close() { closeCount++; }
    }
}
