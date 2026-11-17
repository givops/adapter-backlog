package io.givops.adapters.backlog;

import java.io.StringReader;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/**
 * Read-only Backlog.md parsing, bounded in decoded UTF-16 characters and YAML nodes.
 * Input must already be decoded as strict UTF-8. Leading BOMs are rejected; body text
 * is retained verbatim. No YAML object construction or hook execution is performed.
 */
public final class BacklogFormat {
    public static final int MAX_YAML_LENGTH = 131_072;
    public static final int MAX_MARKDOWN_LENGTH = 2_097_152;
    private static final int MAX_SCALAR_LENGTH = 16_384;
    private static final int MAX_NODES = 4_096;
    private static final int MAX_DEPTH = 24;
    private static final int MAX_ITEMS = 256;
    private static final Set<Tag> SCALAR_TAGS = Set.of(Tag.STR, Tag.NULL, Tag.BOOL, Tag.INT, Tag.FLOAT, Tag.TIMESTAMP);
    private static final Pattern DELIMITER = Pattern.compile("(?m)^---(?:\\r?\\n|\\z)");
    private static final Pattern NUMERIC_FILENAME = Pattern.compile("^([A-Za-z]+-[0-9]+(?:\\.[0-9]+)*)(?=$| -|-)");

    private BacklogFormat() {}

    public record Config(String prefix, String project, List<String> statuses) {
        public Config {
            prefix = checkedPrefix(prefix);
            project = checkedText(project, 256);
            if (statuses == null || statuses.isEmpty() || statuses.size() > 64) throw invalid();
            statuses = List.copyOf(statuses.stream().map(value -> checkedText(value, 128)).toList());
            if (new HashSet<>(statuses).size() != statuses.size()) throw invalid();
        }
    }

    public record Task(String id, String title, String status, String priority, String type,
                       List<String> labels, Instant createdAt, Instant updatedAt, String body,
                       String path, String entityKind, String lifecycle) {
        public Task {
            labels = List.copyOf(labels);
        }

        @Override
        public String toString() {
            return "BacklogTask[content=redacted]";
        }
    }

    public static Config config(String yaml) {
        Map<String, Node> fields = mapping(document(yaml));
        String direct = fields.containsKey("task_prefix") ? text(fields.get("task_prefix"), 32) : null;
        String nested = null;
        if (fields.containsKey("prefixes")) {
            Map<String, Node> prefixes = mapping(fields.get("prefixes"));
            if (prefixes.containsKey("task")) nested = text(prefixes.get("task"), 32);
        }
        if (direct != null && nested != null && !direct.equalsIgnoreCase(nested)) throw invalid();
        String prefix = direct != null ? direct : nested != null ? nested : "TASK";
        return new Config(prefix, text(fields.get("project_name"), 256), strings(fields.get("statuses"), 64, 128));
    }

    public static Task task(String path, String markdown, Config config, String lifecycle) {
        if (config == null || !Set.of("ACTIVE", "ARCHIVED", "DRAFT").contains(lifecycle == null ? "" : lifecycle)) throw invalid();
        checkedText(path, 4096);
        if (path.indexOf('\\') >= 0 || path.startsWith("/") || path.indexOf('\0') >= 0) throw invalid();
        for (String segment : path.split("/", -1)) if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) throw invalid();
        if (markdown == null || markdown.length() > MAX_MARKDOWN_LENGTH) throw invalid();
        Matcher delimiter = DELIMITER.matcher(markdown);
        if (!delimiter.find() || delimiter.start() != 0 || delimiter.end() == markdown.length()) throw invalid();
        int headerStart = delimiter.end();
        if (!delimiter.find() || delimiter.start() - headerStart > MAX_YAML_LENGTH) throw invalid();
        Map<String, Node> fields = mapping(document(markdown.substring(headerStart, delimiter.start())));
        String body = markdown.substring(delimiter.end());
        String parent = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
        boolean draft = "DRAFT".equals(lifecycle) || parent.equals("drafts") || parent.endsWith("/drafts");
        String filename = path.substring(path.lastIndexOf('/') + 1);
        if (!filename.endsWith(".md")) throw invalid();
        String stem = filename.substring(0, filename.length() - 3);
        String id;
        if (draft) {
            Matcher match = NUMERIC_FILENAME.matcher(stem);
            if (!match.find()) throw invalid();
            id = BacklogIds.canonical(match.group(1));
            if (!id.startsWith("DRAFT-")) throw invalid();
            String claimed = optionalText(fields.get("id"), BacklogIds.MAX_ID_LENGTH);
            if (claimed != null && !id.equals(BacklogIds.canonical(claimed))) throw invalid();
        } else {
            id = BacklogIds.canonical(text(fields.get("id"), BacklogIds.MAX_ID_LENGTH));
            if (!id.startsWith(config.prefix() + "-") || !filenameMatches(stem, id)) throw invalid();
        }
        String status = text(fields.get("status"), 128);
        if (!config.statuses().contains(status)) throw invalid();
        return new Task(id, text(fields.get("title"), 1024), status,
                optionalText(fields.get("priority"), 128), optionalText(fields.get("type"), 128),
                fields.containsKey("labels") ? strings(fields.get("labels"), MAX_ITEMS, 256) : List.of(),
                instant(fields.get("created_date")), instant(fields.get("updated_date")),
                body, path, draft ? "draft" : "task", lifecycle);
    }

    private static boolean filenameMatches(String stem, String id) {
        // A full identity or a whole identity followed by either upstream title delimiter.
        if (BacklogIds.candidate(stem).filter(id::equals).isPresent()) return true;
        for (int index = 1; index < stem.length(); index++) {
            if (stem.startsWith(" -", index) || stem.charAt(index) == '-') {
                if (BacklogIds.candidate(stem.substring(0, index)).filter(id::equals).isPresent()) return true;
            }
        }
        return false;
    }

    private static Instant instant(Node node) {
        if (node == null || Tag.NULL.equals(node.getTag())) return null;
        if (!(node instanceof ScalarNode scalar) || !(Tag.STR.equals(node.getTag()) || Tag.TIMESTAMP.equals(node.getTag()))) throw invalid();
        String value = checkedText(scalar.getValue(), 128);
        // No source timezone is configured; date-only and local times are not instants.
        try {
            return OffsetDateTime.parse(value.replaceFirst("^(\\d{4}-\\d{2}-\\d{2})[tT ]", "$1T")).toInstant();
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static Node document(String source) {
        if (source == null || source.isBlank() || source.charAt(0) == '\uFEFF' || source.length() > MAX_YAML_LENGTH) throw invalid();
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(MAX_DEPTH);
        options.setCodePointLimit(MAX_YAML_LENGTH);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try {
            int eventCount = 0;
            for (Event event : yaml.parse(new StringReader(source))) {
                if (event instanceof AliasEvent || ++eventCount > MAX_NODES * 3) throw invalid();
            }
            Node root = yaml.compose(new StringReader(source));
            inspect(root, 0, new int[] {0});
            return root;
        } catch (RuntimeException exception) {
            // Do not attach SnakeYAML exceptions: their messages/marks contain source snippets.
            throw invalid();
        }
    }

    private static void inspect(Node node, int depth, int[] count) {
        if (node == null || depth > MAX_DEPTH || ++count[0] > MAX_NODES) throw invalid();
        if (node instanceof ScalarNode scalar) {
            if (!SCALAR_TAGS.contains(node.getTag()) || scalar.getValue().length() > MAX_SCALAR_LENGTH) throw invalid();
        } else if (node instanceof SequenceNode sequence) {
            if (!Tag.SEQ.equals(node.getTag()) || sequence.getValue().size() > MAX_ITEMS) throw invalid();
            for (Node item : sequence.getValue()) inspect(item, depth + 1, count);
        } else if (node instanceof MappingNode map) {
            if (!Tag.MAP.equals(node.getTag()) || map.getValue().size() > MAX_ITEMS) throw invalid();
            Set<String> keys = new HashSet<>();
            for (NodeTuple tuple : map.getValue()) {
                inspect(tuple.getKeyNode(), depth + 1, count);
                String key = text(tuple.getKeyNode(), 256);
                if (!keys.add(key)) throw invalid();
                inspect(tuple.getValueNode(), depth + 1, count);
            }
        } else {
            throw invalid();
        }
    }

    private static Map<String, Node> mapping(Node node) {
        if (!(node instanceof MappingNode map)) throw invalid();
        Map<String, Node> result = new LinkedHashMap<>();
        for (NodeTuple tuple : map.getValue()) result.put(text(tuple.getKeyNode(), 256), tuple.getValueNode());
        return result;
    }

    private static String checkedPrefix(String value) {
        if (value == null || !value.matches("[A-Za-z]{1,32}")) throw invalid();
        String prefix = value.toUpperCase(Locale.ROOT);
        if (Set.of("DRAFT", "DOC", "DECISION").contains(prefix)) throw invalid();
        return prefix;
    }

    private static String checkedText(String value, int length) {
        if (value == null || value.isBlank() || value.length() > length || value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return value;
    }

    private static String text(Node node, int length) {
        if (!(node instanceof ScalarNode scalar) || !Tag.STR.equals(node.getTag())) throw invalid();
        return checkedText(scalar.getValue(), length);
    }

    private static String optionalText(Node node, int length) {
        return node == null || Tag.NULL.equals(node.getTag()) ? null : text(node, length);
    }

    private static List<String> strings(Node node, int count, int length) {
        if (!(node instanceof SequenceNode sequence) || sequence.getValue().size() > count) throw invalid();
        List<String> result = new ArrayList<>();
        for (Node item : sequence.getValue()) result.add(text(item, length));
        return List.copyOf(result);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid or unsupported Backlog format");
    }
}
