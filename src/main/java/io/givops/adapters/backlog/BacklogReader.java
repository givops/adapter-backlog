package io.givops.adapters.backlog;

import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketModels.RepositorySource;
import io.givops.ticketing.port.RepositoryContentHost;
import io.givops.ticketing.port.TicketRequestGate;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Complete index per immutable snapshot; body bytes are discarded unless explicitly requested. */
final class BacklogReader {
    static final int MAX_ENTRIES = 10_000;
    static final int MAX_FILES = 5_000;
    static final int MAX_MARKDOWN = 1024 * 1024;
    static final long MAX_SCAN_BYTES = 32L * 1024 * 1024;
    record Index(BacklogFormat.Config config, List<BacklogFormat.Task> tasks) { }
    static Index read(RepositoryContentHost.Snapshot snapshot, RepositorySource source, BacklogSettings settings,
                      String contentId, TicketRequestGate gate) {
        if (!snapshot.revision().immutable() || !"git-commit".equals(snapshot.revision().kind())) throw invalid();
        BacklogFormat.Config config;
        try { config = BacklogFormat.config(text(snapshot, source.configurationPath(), 65536, gate)); }
        catch (IllegalArgumentException failure) { throw invalid(); }
        config.statuses().forEach(settings::mapping);
        var entries = snapshot.entries(source.root(), MAX_ENTRIES, gate);
        if (entries.size() > MAX_ENTRIES) throw limit();
        var paths = new HashSet<String>();
        var identities = new HashSet<String>();
        var tasks = new ArrayList<BacklogFormat.Task>();
        Map<String, String> folders = settings.drafts()
                ? Map.of("tasks", "ACTIVE", "archive/tasks", "ARCHIVED", "completed", "ARCHIVED", "drafts", "DRAFT", "archive/drafts", "ARCHIVED")
                : Map.of("tasks", "ACTIVE", "archive/tasks", "ARCHIVED", "completed", "ARCHIVED");
        long bytes = 0;
        for (var entry : entries) {
            gate.check();
            if (entry == null || entry.kind() == null || !canonicalPath(entry.path())
                    || !paths.add(entry.path()) || !within(entry.path(), source.root())) throw invalid();
            String relative = source.root().isEmpty() ? entry.path() : entry.path().substring(source.root().length() + 1);
            for (var folder : folders.entrySet()) {
                if ((relative.equals(folder.getKey()) || folder.getKey().startsWith(relative + "/"))
                        && entry.kind() != RepositoryContentHost.EntryKind.DIRECTORY) throw invalid();
                if (!relative.startsWith(folder.getKey() + "/")) continue;
                if (entry.kind() == RepositoryContentHost.EntryKind.DIRECTORY) continue;
                // All Markdown in selected task directories is expected; never skip a malformed filename.
                if (!relative.endsWith(".md")) {
                    String basename = relative.substring(relative.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
                    if (entry.kind() != RepositoryContentHost.EntryKind.FILE
                            || basename.startsWith(config.prefix().toLowerCase(java.util.Locale.ROOT) + "-")
                            || basename.startsWith("draft-")) throw invalid();
                    continue;
                }
                String name = relative.substring(folder.getKey().length() + 1);
                if (name.contains("/") || entry.kind() != RepositoryContentHost.EntryKind.FILE || entry.size() < 0) throw invalid();
                bytes += entry.size();
                if (tasks.size() >= MAX_FILES || bytes > MAX_SCAN_BYTES || entry.size() > MAX_MARKDOWN) throw limit();
                BacklogFormat.Task task;
                String markdown = text(snapshot, entry.path(), MAX_MARKDOWN, gate);
                try { task = BacklogFormat.task(entry.path(), markdown, config, folder.getValue()); }
                catch (IllegalArgumentException failure) { throw invalid(); }
                settings.mapping(task.status());
                String id = task.entityKind() + ":" + task.id();
                if (!identities.add(id)) throw invalid();
                task = withBody(task, id.equals(contentId) ? markdown : "");
                tasks.add(task);
                gate.acceptedObjects(1);
            }
        }
        return new Index(config, List.copyOf(tasks));
    }
    private static boolean canonicalPath(String path) {
        return path != null && !path.isEmpty() && path.length() <= 2048 && !path.startsWith("/")
                && !path.contains("\\") && path.codePoints().noneMatch(Character::isISOControl)
                && java.util.Arrays.stream(path.split("/", -1)).noneMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."));
    }
    static boolean within(String path, String root) { return root.isEmpty() || path.startsWith(root + "/"); }
    static String text(RepositoryContentHost.Snapshot snapshot, String path, int maximum, TicketRequestGate gate) {
        gate.check();
        try (var blob = snapshot.open(path, maximum, gate)) {
            if (blob.size() < 0 || blob.size() > maximum) throw limit();
            var output = new java.io.ByteArrayOutputStream();
            var input = blob.stream();
            byte[] buffer = new byte[8192];
            while (true) {
                gate.check();
                int count = input.read(buffer, 0, Math.min(buffer.length, maximum + 1 - output.size()));
                gate.check();
                if (count < 0) break;
                if (count == 0) throw invalid();
                output.write(buffer, 0, count);
                if (output.size() > maximum) throw limit();
            }
            var bytes = output.toByteArray();
            if (bytes.length > maximum) throw limit();
            if (bytes.length != blob.size()) throw invalid();
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) { throw invalid(); }
        catch (IOException failure) { throw new TicketException(TicketException.Code.PROVIDER_ERROR, "The repository file could not be read."); }
    }
    private static BacklogFormat.Task withBody(BacklogFormat.Task task, String body) {
        return new BacklogFormat.Task(task.id(), task.title(), task.status(), task.priority(), task.type(), task.labels(),
                task.createdAt(), task.updatedAt(), body, task.path(), task.entityKind(), task.lifecycle());
    }
    static TicketException invalid() { return new TicketException(TicketException.Code.INVALID_RESPONSE, "The Backlog snapshot is invalid or incomplete."); }
    static TicketException limit() { return new TicketException(TicketException.Code.LIMIT_EXCEEDED, "The Backlog operation exceeds its configured bounds."); }
}
