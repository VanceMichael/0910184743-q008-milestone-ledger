package com.example.milestone;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MilestoneTest {
    @Test void validatesVersionedDecision() {
        assertEquals(2, new Milestone("project-demo", 2, "pending").revision());
        assertThrows(IllegalArgumentException.class, () -> new Milestone("project-demo", 0, "pending"));
    }
}
