package io.givops.adapters.backlog;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class BacklogIdsTest {
    @Test
    void canonicalizesEachDecimalSegmentWithoutIntegerCoercion() {
        assertEquals("TASK-0.2.90071992547409931234567890", BacklogIds.canonical(" task-000.002.090071992547409931234567890 "));
        assertEquals("CUSTOM-1.2", BacklogIds.canonical("Custom-001.02"));
        assertNotEquals(BacklogIds.canonical("TASK-1"), BacklogIds.canonical("TASK-1.0"));
    }

    @Test
    void preservesWholeLegacyBodiesAndTheirCosmeticLookingDigits() {
        assertEquals("TASK-ALPHA_002.BETA-03", BacklogIds.canonical("task-alpha_002.beta-03"));
        assertEquals("TASK-1.2OOPS", BacklogIds.canonical("task-1.2oops"));
    }

    @Test
    void candidatesRequireACompletePrefixedIdentity() {
        for (String invalid : List.of("", "1", "1.2", "TASK-", "TASK-1..2", "TASK-1.2.", "TASK-1.",
                "TASK-1/2", "TASK-1.2,", "TASK-1.2#x", "TASK-1 2", "TASK--1", "1TASK-1", "TÄSK-1")) {
            assertTrue(BacklogIds.candidate(invalid).isEmpty(), invalid);
            assertThrows(IllegalArgumentException.class, () -> BacklogIds.canonical(invalid));
        }
        assertTrue(BacklogIds.candidate(null).isEmpty());
        assertEquals("TASK-1.2", BacklogIds.candidate("task-001.02").orElseThrow());
    }

    @Test
    void boundsInputLengthPrefixAndComponentCount() {
        assertTrue(BacklogIds.candidate("TASK-" + "1".repeat(252)).isEmpty());
        assertTrue(BacklogIds.candidate("P".repeat(33) + "-1").isEmpty());
        assertTrue(BacklogIds.candidate("TASK-" + "1.".repeat(32) + "1").isEmpty());
        assertEquals("TASK-" + "9".repeat(251), BacklogIds.canonical("TASK-" + "9".repeat(251)));
    }

    @Test
    void errorsNeverEchoInvalidInput() {
        var error = assertThrows(IllegalArgumentException.class, () -> BacklogIds.canonical("secret ticket contents!"));
        assertEquals("Invalid Backlog identity", error.getMessage());
        assertNull(error.getCause());
    }
}
