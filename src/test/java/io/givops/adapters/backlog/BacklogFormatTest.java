package io.givops.adapters.backlog;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BacklogFormatTest {
    private static final String CONFIG = "project_name: Example\nstatuses: [todo, in_progress, done, draft]\n";
    private static final BacklogFormat.Config DEFAULT = BacklogFormat.config(CONFIG);

    @Test
    void draftNamedAncestorDoesNotChangeTaskEntityKind() {
        var task = BacklogFormat.task("drafts/backlog/tasks/task-1.md",
                "---\nid: TASK-1\ntitle: Root relocation\nstatus: todo\n---\nbody",
                BacklogFormat.config(CONFIG), "ACTIVE");
        assertEquals("task", task.entityKind());
    }

    @Test
    void readsUpstreamTaskPrefixAndCompatibilityPrefixWithoutExecutingHooks() {
        assertEquals("TASK", DEFAULT.prefix());
        var modern = BacklogFormat.config(CONFIG + "task_prefix: bug\nonStatusChange: 'touch /never-executed'\n");
        assertEquals("BUG", modern.prefix());
        assertEquals("BUG", BacklogFormat.config(CONFIG + "prefixes: {task: bug}\n").prefix());
        assertEquals("BUG", BacklogFormat.config(CONFIG + "task_prefix: BUG\nprefixes: {task: bug}\n").prefix());
        assertThrows(IllegalArgumentException.class, () -> BacklogFormat.config(CONFIG + "task_prefix: A\nprefixes: {task: B}\n"));
        for (String prefix : List.of("draft", "DOC", "decision", "task1", "' ta '", "''", "[]", "null")) {
            assertThrows(IllegalArgumentException.class, () -> BacklogFormat.config(CONFIG + "task_prefix: " + prefix));
        }
    }

    @Test
    void requiresWellTypedProjectAndUniqueBoundedStatuses() {
        for (String yaml : List.of("", "project_name: Example", "statuses: [todo]", "project_name: 123\nstatuses: [todo]",
                "project_name: Example\nstatuses: todo", "project_name: Example\nstatuses: []",
                "project_name: Example\nstatuses: [todo, todo]", "project_name: Example\nstatuses: [true]")) {
            assertThrows(IllegalArgumentException.class, () -> BacklogFormat.config(yaml));
        }
        assertThrows(UnsupportedOperationException.class, () -> DEFAULT.statuses().add("new"));
        assertThrows(IllegalArgumentException.class, () -> new BacklogFormat.Config("task", "Example", List.of(" ")));
    }

    @Test
    void rejectsMalformedYamlDuplicatesAtAnyDepthAndMultipleDocuments() {
        for (String extra : List.of("broken: [", "project_name: Other", "extension: {nested: 1, nested: 2}",
                "---\nproject_name: Other\nstatuses: [todo]", "? [complex, key]\n: value")) {
            assertSafeFailure(() -> BacklogFormat.config(CONFIG + extra));
        }
    }

    @Test
    void rejectsExecutableTagsAliasesMergeKeysAndNonCoreTypes() {
        for (String extra : List.of("extension: !!java/object:java.lang.ProcessBuilder {}", "extension: !private value",
                "extension: !!binary SGVsbG8=", "extension: !!set {a: null}",
                "extension: &x [a]\nother: *x", "extension: &x value\nother: *x", "extension: &x [*x]",
                "extension: {<<: {a: value}}")) {
            assertSafeFailure(() -> BacklogFormat.config(CONFIG + extra));
        }
    }

    @Test
    void boundsYamlBytesDepthUnknownScalarsAndCollections() {
        assertSafeFailure(() -> BacklogFormat.config(CONFIG + "#".repeat(BacklogFormat.MAX_YAML_LENGTH)));
        assertSafeFailure(() -> BacklogFormat.config(CONFIG + "extension: '" + "a".repeat(16_385) + "'"));
        assertSafeFailure(() -> BacklogFormat.config(CONFIG + "extension: " + "[".repeat(30) + "0" + "]".repeat(30)));
        assertSafeFailure(() -> BacklogFormat.config(CONFIG + "extension: [" + "a,".repeat(257) + "]"));
        assertSafeFailure(() -> BacklogFormat.config("project_name: Example\nstatuses: [" + "todo,".repeat(65) + "]"));
        StringBuilder manyNodes = new StringBuilder(CONFIG);
        for (int index = 0; index < 20; index++) manyNodes.append("extension_").append(index).append(": [").append("a,".repeat(255)).append("]\n");
        assertSafeFailure(() -> BacklogFormat.config(manyNodes.toString()));
    }

    @Test
    void retainsAllMarkdownAndCrLfExactlyAfterFrontmatter() {
        String body = "\r\nPreamble Ω\r\n\r\n## Description\r\nStory\r\n\r\n## Acceptance Criteria\r\n- [ ] Test\r\n\r\n## Comments\r\nPrivate comment marker\r\n\r\n";
        String source = "---\r\nid: task-001.02\r\ntitle: Unicode Ω\r\nstatus: todo\r\nlabels: [alpha, beta]\r\n"
                + "type: feature\r\npriority: high\r\nunknown: {nested: [1, true, null]}\r\n---\r\n" + body;
        var task = BacklogFormat.task("backlog/tasks/TASK-1.002 - Title.md", source, DEFAULT, "ACTIVE");
        assertEquals("TASK-1.2", task.id());
        assertEquals(body, task.body());
        assertEquals(List.of("alpha", "beta"), task.labels());
        assertEquals("feature", task.type());
        assertEquals("high", task.priority());
        assertEquals("task", task.entityKind());
        assertEquals("ACTIVE", task.lifecycle());
        assertFalse(task.toString().contains("Private comment marker"));
        assertThrows(UnsupportedOperationException.class, () -> task.labels().add("new"));
    }

    @Test
    void acceptsIdentityPaddingSuffixesAndLegacyFilenamesButRejectsConflicts() {
        for (String filename : List.of("task-01.002.md", "task-01.002 - Title.md", "task-01.002-title.md")) {
            assertEquals("TASK-1.2", parse(filename, "id: TASK-001.02\n").id());
        }
        assertEquals("TASK-ALPHA_02.BETA", parse("task-alpha_02.beta - Title.md", "id: task-alpha_02.beta\n").id());
        for (String filename : List.of("task-1.md", "task-1.20 - Title.md", "task-1.2oops - Title.md", "task-1.2. - Title.md", "task-1.2..3.md")) {
            assertSafeFailure(() -> parse(filename, "id: TASK-1.2\n"));
        }
        assertSafeFailure(() -> parse("bug-1 - Title.md", "id: BUG-1\n"));
    }

    @Test
    void requiresKnownStatusesAndStrictMetadataTypes() {
        for (String fields : List.of("title: Example\nstatus: todo\n", "id: TASK-1\nstatus: todo\n", "id: TASK-1\ntitle: Example\n",
                "id: TASK-1\ntitle: 42\nstatus: todo\n", "id: TASK-1\ntitle: Example\nstatus: unknown\n",
                "id: TASK-1\ntitle: Example\nstatus: todo\nlabels: alpha\n", "id: TASK-1\ntitle: Example\nstatus: todo\nlabels: [42]\n",
                "id: TASK-1\ntitle: Example\nstatus: todo\npriority: true\n", "id: TASK-1\ntitle: Example\nstatus: todo\ntype: {}\n")) {
            assertSafeFailure(() -> BacklogFormat.task("tasks/task-1 - Title.md", "---\n" + fields + "---\nbody", DEFAULT, "ACTIVE"));
        }
        assertEquals("draft", parse("task-1.md", "id: TASK-1\n", "draft").status());
        assertEquals("task", parse("task-1.md", "id: TASK-1\n", "draft").entityKind());
    }

    @Test
    void convertsExplicitOffsetsOnlyWithoutInventingDatePrecision() {
        var task = parse("task-1.md", "id: TASK-1\ncreated_date: 2026-11-17T10:11:12.123456789+02:00\nupdated_date: '2026-11-17 09:00:00Z'\n");
        assertEquals(Instant.parse("2026-11-17T08:11:12.123456789Z"), task.createdAt());
        assertEquals(Instant.parse("2026-11-17T09:00:00Z"), task.updatedAt());
        for (String local : List.of("2026-11-17", "'2026-11-17 10:11'", "'2026-11-17T10:11:12'", "'not a date'")) {
            assertNull(parse("task-1.md", "id: TASK-1\ncreated_date: " + local + "\n").createdAt());
        }
        assertSafeFailure(() -> parse("task-1.md", "id: TASK-1\ncreated_date: 42\n"));
    }

    @Test
    void draftsUseFilenameIdentityAndArchivesPreserveStatus() {
        var draft = BacklogFormat.task("backlog/drafts/draft-003 - Plan.md", markdown("", "draft"), DEFAULT, "DRAFT");
        assertEquals("DRAFT-3", draft.id());
        assertEquals("draft", draft.entityKind());
        assertSafeFailure(() -> BacklogFormat.task("drafts/draft-3.md", markdown("id: TASK-3\n", "draft"), DEFAULT, "DRAFT"));
        var archivedDraft = BacklogFormat.task("archive/drafts/draft-3.md", markdown("id: DRAFT-003\n", "draft"), DEFAULT, "ARCHIVED");
        assertEquals("draft", archivedDraft.entityKind());
        assertEquals("ARCHIVED", archivedDraft.lifecycle());
        var archived = BacklogFormat.task("completed/task-1.md", markdown("id: TASK-1\n", "todo"), DEFAULT, "ARCHIVED");
        assertEquals("todo", archived.status());
    }

    @Test
    void requiresExactHeaderBoundaryAndSafeBoundedPathsAndContent() {
        for (String source : List.of("body", "---\nid: TASK-1\n", "---\nid: TASK-1\ntitle: Example\nstatus: todo\n---junk\nbody")) {
            assertSafeFailure(() -> BacklogFormat.task("tasks/task-1.md", source, DEFAULT, "ACTIVE"));
        }
        for (String path : List.of("../task-1.md", "/tasks/task-1.md", "tasks//task-1.md", "tasks\\task-1.md", "tasks/task-1.txt")) {
            assertSafeFailure(() -> BacklogFormat.task(path, markdown("id: TASK-1\n", "todo"), DEFAULT, "ACTIVE"));
        }
        assertSafeFailure(() -> BacklogFormat.task("task-1.md", "a".repeat(BacklogFormat.MAX_MARKDOWN_LENGTH + 1), DEFAULT, "ACTIVE"));
        assertSafeFailure(() -> BacklogFormat.task("task-1.md", "\uFEFF" + markdown("id: TASK-1\n", "todo"), DEFAULT, "ACTIVE"));
        assertSafeFailure(() -> BacklogFormat.config("\uFEFF" + CONFIG));
    }

    private static BacklogFormat.Task parse(String filename, String fields) {
        return parse(filename, fields, "todo");
    }

    private static BacklogFormat.Task parse(String filename, String fields, String status) {
        return BacklogFormat.task("tasks/" + filename, markdown(fields, status), DEFAULT, "ACTIVE");
    }

    private static String markdown(String fields, String status) {
        return "---\n" + fields + "title: Example\nstatus: " + status + "\n---\nbody";
    }

    private static void assertSafeFailure(org.junit.jupiter.api.function.Executable action) {
        var error = assertThrows(IllegalArgumentException.class, action);
        assertTrue(List.of("Invalid or unsupported Backlog format", "Invalid Backlog identity").contains(error.getMessage()));
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
    }
}
