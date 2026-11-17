package io.givops.adapters.backlog;

import io.givops.adapters.ContextSigner;
import io.givops.ticketing.model.TicketBrowserModels.AssetRef;
import io.givops.ticketing.model.TicketBrowserModels.LinkRef;
import io.givops.ticketing.model.TicketBrowserModels.TicketAsset;
import io.givops.ticketing.model.TicketBrowserModels.TicketRef;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketModels.RepositorySource;
import io.givops.ticketing.port.RepositoryContentHost.Blob;
import io.givops.ticketing.port.RepositoryContentHost.Entry;
import io.givops.ticketing.port.RepositoryContentHost.EntryKind;
import io.givops.ticketing.port.RepositoryContentHost.Snapshot;
import io.givops.ticketing.port.TicketRequestGate;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.commonmark.node.Node;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.parser.Parser;

/** Resolves Markdown references exclusively through host-authorized repository metadata. */
public final class BacklogAssets {
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final long MAX_MANIFEST_BYTES = 32L * 1024 * 1024;
    private static final int MAX_ENTRIES = 10_000;
    private static final int MAX_REFS = 100;

    private BacklogAssets() { }

    public record Manifest(List<AssetRef> assets, List<LinkRef> links, Map<String, String> paths) {
        public Manifest {
            assets = List.copyOf(assets);
            links = List.copyOf(links);
            paths = Map.copyOf(paths);
        }
    }

    public static Manifest manifest(Snapshot snapshot, RepositorySource source, String taskPath,
                                    String markdown, TicketRef ticket, String context,
                                    ContextSigner signer, TicketRequestGate gate) {
        return manifest(snapshot, source, taskPath, markdown, ticket, context, signer, gate, Map.of());
    }

    static Manifest manifest(Snapshot snapshot, RepositorySource source, String taskPath,
                             String markdown, TicketRef ticket, String context, ContextSigner signer,
                             TicketRequestGate gate, Map<String, TicketRef> ticketsByPath) {
        check(gate);
        if (markdown.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) throw limit();
        List<String> targets = new ArrayList<>();
        Node document = Parser.builder().build().parse(markdown);
        Node node = document;
        int depth = 0;
        int nodes = 0;
        while (node != null) {
            check(gate);
            if (++nodes > 100_000 || depth > 256) throw limit();
            String target = node instanceof Link link ? link.getDestination()
                    : node instanceof Image image ? image.getDestination() : null;
            if (target != null) {
                if (targets.size() == MAX_REFS) throw limit();
                targets.add(target);
            }
            if (node.getFirstChild() != null) {
                node = node.getFirstChild();
                depth++;
            } else {
                while (node != document && node.getNext() == null) { node = node.getParent(); depth--; }
                node = node == document ? null : node.getNext();
            }
        }
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        roots.add(join(source.root(), "assets"));
        roots.addAll(source.approvedAssetRoots());
        Map<String, Map<String, Entry>> indexes = new LinkedHashMap<>();
        Map<String, AssetRef> byPath = new LinkedHashMap<>();
        Map<String, String> paths = new LinkedHashMap<>();
        List<LinkRef> links = new ArrayList<>();
        int[] entryCount = {0};
        long totalBytes = 0;
        for (String target : targets) {
            check(gate);
            URI external = external(target);
            if (external != null) {
                links.add(new LinkRef(target, null, external, null));
                continue;
            }
            String path = resolve(source.root(), taskPath, target);
            TicketRef linkedTicket = path == null ? null : ticketsByPath.get(path);
            if (linkedTicket != null) {
                links.add(new LinkRef(target, linkedTicket, null, null));
                continue;
            }
            String root = path == null ? null : roots.stream().filter(value -> within(path, value))
                    .max(java.util.Comparator.comparingInt(String::length)).orElse(null);
            Entry entry = null;
            if (root != null) {
                Map<String, Entry> index = indexes.computeIfAbsent(root,
                        value -> index(snapshot, value, gate, entryCount));
                entry = index.get(path);
                if (entry != null && !safeFile(entry, path, root, index)) entry = null;
            }
            if (entry == null) {
                links.add(new LinkRef(target, null, null, null));
                continue;
            }
            if (!snapshot.revision().immutable()) {
                throw new TicketException(TicketException.Code.REVISION_CONFLICT, "Assets require an immutable revision.");
            }
            AssetRef asset = byPath.get(path);
            if (asset == null) {
                totalBytes += entry.size();
                if (totalBytes > MAX_MANIFEST_BYTES) throw limit();
                String digest;
                try { digest = digest(snapshot, entry, gate); }
                catch (TicketException failure) {
                    if (failure.code() == TicketException.Code.NOT_FOUND) {
                        links.add(new LinkRef(target, null, null, null));
                        continue;
                    }
                    throw safe(failure);
                }
                List<String> binding = new ArrayList<>(List.of(source.repositoryId().toString(), source.root(),
                        source.ref(), snapshot.revision().kind(), snapshot.revision().value(),
                        ticket.instanceId().toString(), ticket.externalProjectId(), ticket.externalIssueId(),
                        path, entry.objectId(), digest, context));
                binding.addAll(source.approvedAssetRoots());
                String id;
                try { id = signer.sign("BACKLOG_MD", "asset", List.copyOf(binding)); }
                catch (RuntimeException failure) {
                    throw new TicketException(TicketException.Code.INTERNAL_ERROR, "Cannot create an asset handle.");
                }
                asset = new AssetRef(id, mediaType(path), entry.size(), digest,
                        path.substring(path.lastIndexOf('/') + 1));
                if (paths.putIfAbsent(id, path) != null) {
                    throw new TicketException(TicketException.Code.INTERNAL_ERROR, "Cannot create distinct asset handles.");
                }
                byPath.put(path, asset);
            }
            links.add(new LinkRef(target, null, null, asset.id()));
            if (links.size() + byPath.size() > MAX_REFS) throw limit();
        }
        if (links.size() + byPath.size() > MAX_REFS) throw limit();
        return new Manifest(new ArrayList<>(byPath.values()), links, paths);
    }

    private static Map<String, Entry> index(Snapshot snapshot, String root, TicketRequestGate gate, int[] count) {
        check(gate);
        List<Entry> entries;
        try { entries = snapshot.entries(root, MAX_ENTRIES, gate); }
        catch (TicketException failure) {
            if (failure.code() == TicketException.Code.NOT_FOUND) return Map.of();
            throw safe(failure);
        } catch (RuntimeException failure) {
            throw new TicketException(TicketException.Code.INVALID_RESPONSE, "Cannot read repository asset metadata.");
        }
        if (entries == null) throw invalid();
        count[0] += entries.size();
        if (count[0] > MAX_ENTRIES) throw limit();
        Map<String, Entry> result = new LinkedHashMap<>();
        for (Entry entry : entries) {
            check(gate);
            if (entry == null || entry.path() == null || !canonical(entry.path()) || !within(entry.path(), root)
                    || result.putIfAbsent(entry.path(), entry) != null) throw invalid();
        }
        return result;
    }

    private static boolean safeFile(Entry entry, String path, String root, Map<String, Entry> index) {
        if (entry.kind() != EntryKind.FILE || entry.size() < 0 || entry.size() > MAX_BYTES
                || entry.objectId() == null || entry.objectId().isBlank() || entry.objectId().length() > 1024
                || controls(entry.objectId())) return false;
        String parent = path;
        while (parent.contains("/")) {
            parent = parent.substring(0, parent.lastIndexOf('/'));
            Entry ancestor = index.get(parent);
            if (ancestor != null && ancestor.kind() != EntryKind.DIRECTORY) return false;
            if (parent.equals(root)) break;
        }
        return true;
    }

    private static URI external(String target) {
        try {
            URI uri = URI.create(target);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null && !controls(target)
                    && uri.getPort() <= 65535 && !controls(uri.getSchemeSpecificPart())
                    && !uri.getSchemeSpecificPart().contains("\\") && !target.contains("\\") ? uri : null;
        } catch (IllegalArgumentException invalid) { return null; }
    }

    static String resolve(String storeRoot, String taskPath, String target) {
        if (target.isEmpty() || target.startsWith("#") || target.startsWith("?") || target.startsWith("//")) return null;
        int end = target.length();
        for (char delimiter : new char[]{'?', '#'}) {
            int position = target.indexOf(delimiter);
            if (position >= 0) end = Math.min(end, position);
        }
        String raw = target.substring(0, end);
        String path = decode(raw);
        if (path == null || path.isEmpty() || path.contains("%") || path.contains("\\") || path.contains("\uFFFD") || controls(path)
                || path.contains(":")) return null;
        // A URI must not introduce separators or traversal through percent decoding.
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("%2f") || lower.contains("%5c")) return null;
        if (lower.contains("%2e") && java.util.Arrays.stream(path.split("/", -1))
                .anyMatch(part -> part.equals(".") || part.equals(".."))) return null;
        String combined;
        if (path.startsWith("/assets/")) combined = join(storeRoot, path.substring(1));
        else if (path.startsWith("/")) return null;
        else {
            if (!canonical(taskPath) || !within(taskPath, storeRoot)) return null;
            int slash = taskPath.lastIndexOf('/');
            combined = join(slash < 0 ? "" : taskPath.substring(0, slash), path);
        }
        List<String> segments = new ArrayList<>();
        for (String segment : combined.split("/", -1)) {
            if (segment.isEmpty()) return null;
            if (segment.equals(".")) continue;
            if (segment.equals("..")) {
                if (segments.isEmpty()) return null;
                segments.removeLast();
            } else segments.add(segment);
        }
        String resolved = String.join("/", segments);
        if (path.startsWith("/assets/") && !within(resolved, join(storeRoot, "assets"))) return null;
        return resolved;
    }

    private static String decode(String raw) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int i = 0; i < raw.length();) {
                if (raw.charAt(i) == '%') {
                    if (i + 2 >= raw.length()) return null;
                    int high = asciiHex(raw.charAt(i + 1));
                    int low = asciiHex(raw.charAt(i + 2));
                    if (high < 0 || low < 0) return null;
                    bytes.write(high * 16 + low);
                    i += 3;
                } else {
                    int start = i;
                    while (i < raw.length() && raw.charAt(i) != '%') i++;
                    ByteBuffer literal = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(raw, start, i));
                    while (literal.hasRemaining()) bytes.write(literal.get());
                }
            }
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException invalid) { return null; }
    }

    private static int asciiHex(char value) {
        if (value >= '0' && value <= '9') return value - '0';
        if (value >= 'a' && value <= 'f') return value - 'a' + 10;
        if (value >= 'A' && value <= 'F') return value - 'A' + 10;
        return -1;
    }
    private static boolean canonical(String path) {
        return !path.isEmpty() && !path.startsWith("/") && !path.contains("\\") && !path.contains("%")
                && !path.contains(":") && !controls(path) && java.util.Arrays.stream(path.split("/", -1))
                .noneMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."));
    }
    private static boolean controls(String value) { return value.codePoints().anyMatch(Character::isISOControl); }
    private static String join(String first, String second) { return first.isEmpty() ? second : first + "/" + second; }
    private static boolean within(String path, String root) { return root.isEmpty() || path.equals(root) || path.startsWith(root + "/"); }

    private static String mediaType(String path) {
        int dot = path.lastIndexOf('.');
        String extension = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "avif" -> "image/avif";
            case "pdf" -> "application/pdf";
            case "txt" -> "text/plain";
            case "md" -> "text/markdown";
            default -> "application/octet-stream";
        };
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is required."); }
    }

    private static String finish(MessageDigest hash) {
        return "sha256:" + java.util.HexFormat.of().formatHex(hash.digest());
    }

    private static String digest(Snapshot snapshot, Entry entry, TicketRequestGate gate) {
        check(gate);
        try (Blob blob = snapshot.open(entry.path(), MAX_BYTES, gate)) {
            if (blob == null || blob.size() != entry.size() || !Objects.equals(blob.objectId(), entry.objectId())) {
                throw new TicketException(TicketException.Code.REVISION_CONFLICT, "Asset changed within its revision.");
            }
            try (InputStream stream = blob.stream()) {
                if (stream == null) throw invalid();
                MessageDigest hash = sha256();
                byte[] buffer = new byte[8192];
                long count = 0;
                while (true) {
                    check(gate);
                    int read = stream.read(buffer, 0, (int) Math.min(buffer.length, entry.size() - count + 1));
                    if (read < 0) break;
                    if (read == 0) {
                        int single = stream.read();
                        if (single < 0) break;
                        buffer[0] = (byte) single;
                        read = 1;
                    }
                    count += read;
                    if (count > entry.size() || count > MAX_BYTES) throw limit();
                    hash.update(buffer, 0, read);
                }
                if (count != entry.size()) throw invalid();
                return finish(hash);
            }
        } catch (TicketException failure) { throw safe(failure); }
        catch (IOException | RuntimeException failure) { throw invalid(); }
    }

    /** The caller retains snapshot ownership and closes it after closing the returned asset. */
    public static TicketAsset open(Snapshot snapshot, TicketRef ticket, AssetRef metadata, String path,
                                   TicketRequestGate gate) {
        check(gate);
        if (!snapshot.revision().immutable() || !canonical(path) || metadata.size() > MAX_BYTES || metadata.digest() == null
                || !metadata.digest().matches("sha256:[0-9a-f]{64}")) throw invalid();
        Blob blob;
        try { blob = snapshot.open(path, MAX_BYTES, gate); }
        catch (TicketException failure) { throw safe(failure); }
        catch (RuntimeException failure) { throw invalid(); }
        InputStream stream = null;
        try {
            if (blob == null || blob.size() != metadata.size() || blob.objectId() == null || blob.objectId().isBlank()) {
                throw new TicketException(TicketException.Code.REVISION_CONFLICT, "Asset metadata changed within its revision.");
            }
            stream = blob.stream();
            if (stream == null) throw invalid();
            return new StreamingAsset(snapshot, ticket, metadata, blob, stream, gate);
        } catch (RuntimeException failure) {
            closeQuietly(stream, blob);
            if (failure instanceof TicketException ticketFailure) throw safe(ticketFailure);
            throw invalid();
        }
    }

    private static final class StreamingAsset implements TicketAsset {
        private final Snapshot snapshot;
        private final TicketRef ticket;
        private final AssetRef metadata;
        private final Blob blob;
        private final InputStream raw;
        private final TicketRequestGate gate;
        private long count;
        private boolean closed;
        private boolean eof;
        private final MessageDigest hash = sha256();
        private final InputStream stream = new InputStream() {
            @Override public int read() throws IOException {
                byte[] single = new byte[1];
                return read(single, 0, 1) < 0 ? -1 : single[0] & 255;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, bytes.length);
                if (length == 0) return 0;
                if (closed) throw new IOException("Asset stream is closed.");
                try {
                    check(gate);
                    if (eof) return -1;
                    int read = raw.read(bytes, offset, (int) Math.min(length, metadata.size() - count + 1));
                    if (read < 0) {
                        if (count != metadata.size()) throw new IOException("Asset byte count does not match its metadata.");
                        verifyDigest();
                        eof = true;
                        return -1;
                    }
                    if (read == 0) {
                        int single = raw.read();
                        if (single < 0) {
                            if (count != metadata.size()) throw new IOException("Asset byte count does not match its metadata.");
                            verifyDigest();
                            eof = true;
                            return -1;
                        }
                        bytes[offset] = (byte) single;
                        read = 1;
                    }
                    count += read;
                    if (count > metadata.size() || count > MAX_BYTES) throw new IOException("Asset exceeds its byte bound.");
                    hash.update(bytes, offset, read);
                    if (count == metadata.size()) {
                        check(gate);
                        if (raw.read() != -1) throw new IOException("Asset exceeds its byte bound.");
                        verifyDigest();
                        eof = true;
                    }
                    return read;
                } catch (IOException failure) {
                    closeQuietly(StreamingAsset.this);
                    throw new IOException("Cannot read bounded repository asset.");
                } catch (RuntimeException failure) {
                    closeQuietly(StreamingAsset.this);
                    if (failure instanceof TicketException ticketFailure) throw safe(ticketFailure);
                    throw new IOException("Cannot read bounded repository asset.");
                }
            }
            @Override public void close() throws IOException { StreamingAsset.this.close(); }
        };
        private StreamingAsset(Snapshot snapshot, TicketRef ticket, AssetRef metadata, Blob blob,
                               InputStream raw, TicketRequestGate gate) {
            this.snapshot = snapshot; this.ticket = ticket; this.metadata = metadata;
            this.blob = blob; this.raw = raw; this.gate = gate;
        }
        @Override public TicketRef ref() { return ticket; }
        @Override public io.givops.ticketing.model.TicketBrowserModels.SourceRevision revision() { return snapshot.revision(); }
        @Override public AssetRef metadata() { return metadata; }
        @Override public InputStream stream() { return stream; }
        private void verifyDigest() throws IOException {
            if (!Objects.equals(finish(hash), metadata.digest())) throw new IOException("Asset digest does not match its metadata.");
        }
        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            boolean failed = false;
            try { raw.close(); } catch (IOException | RuntimeException failure) { failed = true; }
            try { blob.close(); } catch (IOException | RuntimeException failure) { failed = true; }
            if (failed) throw new IOException("Cannot close repository asset.");
        }
    }

    private static void closeQuietly(AutoCloseable... resources) {
        for (AutoCloseable resource : resources) {
            if (resource != null) try { resource.close(); } catch (Exception ignored) { }
        }
    }
    private static TicketException invalid() {
        return new TicketException(TicketException.Code.INVALID_RESPONSE, "Invalid repository asset metadata.");
    }
    private static TicketException limit() {
        return new TicketException(TicketException.Code.LIMIT_EXCEEDED, "Repository content exceeds its bounds.");
    }
    private static TicketException safe(TicketException failure) {
        return new TicketException(failure.code(), "Repository asset request could not be completed.", failure.retryAt());
    }
    private static void check(TicketRequestGate gate) {
        try { gate.check(); }
        catch (TicketException failure) { throw safe(failure); }
        catch (RuntimeException failure) {
            throw new TicketException(TicketException.Code.INTERNAL_ERROR, "Cannot validate repository request budget.");
        }
    }
}
