package io.givops.adapters.backlog;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Bounded, whole-token identities for the Backlog.md v1.55.3 profile. */
public final class BacklogIds {
    public static final int MAX_ID_LENGTH = 256;
    public static final int MAX_PREFIX_LENGTH = 32;
    public static final int MAX_COMPONENTS = 32;
    private static final Pattern PREFIX = Pattern.compile("[A-Za-z]{1,32}");
    private static final Pattern NUMERIC = Pattern.compile("[0-9]+(?:\\.[0-9]+)*");
    private static final Pattern LEGACY = Pattern.compile("[A-Za-z0-9]+(?:[._-][A-Za-z0-9]+)*");

    private BacklogIds() {}

    /**
     * Accepts PREFIX-decimal[.decimal]* and legacy PREFIX-alnum([._-]alnum)*.
     * Only all-decimal bodies lose zero padding; textual legacy components retain it.
     * Bare numbers and partial matches are never identities.
     */
    public static String canonical(String value) {
        if (value == null || value.length() > MAX_ID_LENGTH) throw invalid();
        String token = value.strip();
        int separator = token.indexOf('-');
        if (separator < 1 || !PREFIX.matcher(token.substring(0, separator)).matches()) throw invalid();
        String body = token.substring(separator + 1);
        if (!LEGACY.matcher(body).matches()) throw invalid();
        String[] components = body.split("[._-]", -1);
        if (components.length > MAX_COMPONENTS) throw invalid();
        if (NUMERIC.matcher(body).matches()) {
            for (int index = 0; index < components.length; index++) {
                int start = 0;
                while (start < components[index].length() - 1 && components[index].charAt(start) == '0') start++;
                components[index] = components[index].substring(start);
            }
            body = String.join(".", components);
        }
        return token.substring(0, separator).toUpperCase(Locale.ROOT) + "-" + body.toUpperCase(Locale.ROOT);
    }

    public static Optional<String> candidate(String value) {
        try {
            return Optional.of(canonical(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid Backlog identity");
    }
}
