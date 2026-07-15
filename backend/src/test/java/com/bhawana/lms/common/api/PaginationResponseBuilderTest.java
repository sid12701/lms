package com.bhawana.lms.common.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PaginationResponseBuilderTest {

    @Test
    void resolveLimitCapsUnpaginatedRequestsAtTwoHundred() {
        assertEquals(200, PaginationResponseBuilder.resolveLimit(null, false));
        assertEquals(200, PaginationResponseBuilder.resolveLimit(500, false));
    }

    @Test
    void resolveLimitDefaultsPaginatedRequestsToFifty() {
        assertEquals(50, PaginationResponseBuilder.resolveLimit(null, true));
        assertEquals(25, PaginationResponseBuilder.resolveLimit(25, true));
    }

    @Test
    void isPaginationRequestedWhenAnyPaginationSignalPresent() {
        assertFalse(PaginationResponseBuilder.isPaginationRequested(null, null, false));
        assertTrue(PaginationResponseBuilder.isPaginationRequested(0, null, false));
        assertTrue(PaginationResponseBuilder.isPaginationRequested(null, 10, false));
        assertTrue(PaginationResponseBuilder.isPaginationRequested(null, null, true));
    }

    @Test
    void includePaginationDetailsAcceptsOnAndOff() {
        assertTrue(PaginationResponseBuilder.includePaginationDetails("ON"));
        assertTrue(PaginationResponseBuilder.includePaginationDetails(" on "));
        assertFalse(PaginationResponseBuilder.includePaginationDetails("OFF"));
        assertFalse(PaginationResponseBuilder.includePaginationDetails(null));
    }

    @Test
    void includePaginationDetailsRejectsUnknownValues() {
        assertThrows(IllegalArgumentException.class,
                () -> PaginationResponseBuilder.includePaginationDetails("MAYBE"));
    }
}
