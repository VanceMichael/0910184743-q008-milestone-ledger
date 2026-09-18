package com.example.milestone.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.milestone.auth.Role;
import com.example.milestone.util.Json;
import org.junit.jupiter.api.Test;

class FieldTrimmerTest {

    @Test
    void verifierLosesCommercialBlock() {
        var view = Json.obj();
        view.put("status", "DISBURSED");
        view.putObject("commercial").put("amount", "300000.00");
        FieldTrimmer.trimView(view, Role.VERIFIER);
        assertFalse(view.has("commercial"));

        var holder = Json.obj();
        holder.putObject("commercial").put("amount", "300000.00");
        FieldTrimmer.trimView(holder, Role.RIGHTS_HOLDER);
        assertTrue(holder.has("commercial"));
    }

    @Test
    void verifierLosesTimelineCommercialPayload() {
        var event = Json.obj();
        event.putObject("payload").putObject("commercial").put("invoiceRef", "INV-1");
        FieldTrimmer.trimTimelineEvent(event, Role.VERIFIER);
        assertFalse(event.get("payload").has("commercial"));

        var kept = Json.obj();
        kept.putObject("payload").putObject("commercial").put("invoiceRef", "INV-1");
        FieldTrimmer.trimTimelineEvent(kept, Role.AUDITOR);
        assertTrue(kept.get("payload").has("commercial"));
    }
}
