package io.givops.adapters.backlog;

import io.givops.ticketing.model.TicketBrowserModels.SourceRevision;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketModels.InstanceSource;
import io.givops.ticketing.port.RepositoryContentHost;
import io.givops.ticketing.port.TicketRequestGate;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Synthetic public-only snapshot host. It never opens a checkout or network connection. */
final class MemoryRepository implements RepositoryContentHost {
    final Map<String, Map<String, byte[]>> revisions = new LinkedHashMap<>();
    String head = "a".repeat(40);
    String context = "access-one";
    boolean current = true;
    boolean lastLocked;
    int snapshotsClosed;
    int blobsClosed;
    java.util.function.UnaryOperator<List<Entry>> entriesTransform = entries -> entries;
    Runnable afterOpen = () -> { };
    MemoryRepository() { revisions.put(head, new LinkedHashMap<>()); }
    void put(String path, String value) { revisions.get(head).put(path, value.getBytes(StandardCharsets.UTF_8)); }
    void move() { var previous = revisions.get(head); head = "b".repeat(40); revisions.put(head, new LinkedHashMap<>(previous)); }
    @Override public Access captureAccess(InstanceSource source) {
        String captured = context;
        return new Access() {
            @Override public String context() { return captured; }
            @Override public boolean current(boolean locked) { lastLocked = locked; return current && captured.equals(context); }
            @Override public Snapshot resolve(String revision, TicketRequestGate gate) {
                String sha = revision == null ? head : revision;
                var files = revisions.get(sha);
                if (files == null) throw new TicketException(TicketException.Code.NOT_FOUND, "Missing revision.");
                return new Snapshot() {
                    @Override public SourceRevision revision() { return new SourceRevision("git-commit", sha, true); }
                    @Override public List<Entry> entries(String root, int maximum, TicketRequestGate requestGate) {
                        if (maximum > 10_000) throw BacklogReader.limit();
                        var result = files.entrySet().stream().filter(e -> root.isEmpty() || e.getKey().startsWith(root + "/"))
                                .map(e -> new Entry(e.getKey(), EntryKind.FILE, e.getValue().length, identity(e.getValue()))).toList();
                        if (result.size() > maximum) throw BacklogReader.limit();
                        return entriesTransform.apply(result);
                    }
                    @Override public Blob open(String path, long maximum, TicketRequestGate requestGate) {
                        byte[] value = files.get(path);
                        if (value == null) throw new TicketException(TicketException.Code.NOT_FOUND, "Missing file.");
                        if (value.length > maximum) throw BacklogReader.limit();
                        afterOpen.run();
                        return new Blob() {
                            final InputStream stream = new ByteArrayInputStream(value);
                            @Override public InputStream stream() { return stream; }
                            @Override public long size() { return value.length; }
                            @Override public String objectId() { return identity(value); }
                            @Override public void close() throws java.io.IOException { blobsClosed++; stream.close(); }
                        };
                    }
                    @Override public void close() { snapshotsClosed++; }
                };
            }
        };
    }
    private static String identity(byte[] bytes) { return java.util.HexFormat.of().formatHex(digest(bytes)); }
    private static byte[] digest(byte[] bytes) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static TicketRequestGate gate() {
        return new TicketRequestGate() {
            @Override public Duration beforeRequest() { return Duration.ofSeconds(5); }
            @Override public void receivedBytes(long count) { }
            @Override public void acceptedObjects(int count) { }
            @Override public void check() { }
            @Override public Duration remaining() { return Duration.ofSeconds(5); }
        };
    }
}
