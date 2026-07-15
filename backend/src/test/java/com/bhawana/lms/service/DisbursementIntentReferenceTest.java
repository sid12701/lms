package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DisbursementIntentReferenceTest {

    @Test
    void deriveTranRefNoIsDeterministicAndWithinIciciLengthLimit() {
        UUID intentId = UUID.fromString("12345678-1234-1234-1234-123456789abc");

        String tranRefNo = DisbursementIntentReference.deriveTranRefNo(intentId);

        assertEquals("ICI1234567812341", tranRefNo);
        assertEquals(16, tranRefNo.length());
        assertTrue(tranRefNo.startsWith("ICI"));
    }
}
