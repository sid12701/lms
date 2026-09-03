package com.bhawana.lms.web;

import com.bhawana.lms.service.LoanEventFeedService;
import com.bhawana.lms.service.LoanEventFeedService.LoanEventFeedPage;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The partner loan event feed — {@code GET /api/v1/lsp/loan-events} (ADR 0007, spec 004).
 *
 * <p>The platform does not push lifecycle changes. An LSP reads its own feed here, hands back the
 * {@code nextCursor} it was given last time to resume where it left off, and reads {@code hasMore}
 * to decide whether to poll again immediately or wait for its normal interval. Calling with no
 * cursor starts from the beginning of the retained window.
 *
 * <p>Contract points partners depend on:
 * <ul>
 *   <li><b>At-least-once.</b> Duplicates are an expected condition on consumer restart, not a
 *       platform bug. Dedupe on {@code eventId}.</li>
 *   <li><b>Ordering is per loan, not global.</b> Concurrent transactions touching the same loan
 *       serialise on row locks, so per-loan order is real. Across unrelated loans the feed is a
 *       stable replay order, not a causal one.</li>
 *   <li><b>The cursor is opaque.</b> Store it and hand it back; do not parse it.</li>
 *   <li><b>{@code eventTypes} narrows the response, never the cursor.</b> Cursors are always over
 *       the unfiltered stream, so an LSP that widens its filter and rewinds to an older cursor
 *       receives everything it previously skipped. Filtering costs no history.</li>
 *   <li><b>Cursor expiry fails loud.</b> A cursor pointing before the oldest event still retained
 *       returns {@code 410 CURSOR_EXPIRED} naming the resync path — {@code GET
 *       /api/v1/lsp/loan-applications}, then resume the feed with no cursor — never a silently
 *       truncated {@code 200}. Distinct from {@code 422 INVALID_CURSOR}, which is a malformed
 *       token (ADR 0007 point 6).</li>
 * </ul>
 *
 * <p>Sitting under {@code /api/v1/lsp/} it inherits that surface's IP allowlist, payload size cap
 * and tenant isolation. It is restricted to API-client principals: the feed carries the full,
 * unmasked payload contract, which the browser-facing LSP UI roles deliberately do not get.
 *
 * <p>Partner-facing contract: {@code docs/partner-loan-event-feed.md}.
 */
@RestController
@RequestMapping("/api/v1/lsp/loan-events")
public class LspLoanEventApiController {

    private final LoanEventFeedService loanEventFeedService;

    public LspLoanEventApiController(LoanEventFeedService loanEventFeedService) {
        this.loanEventFeedService = loanEventFeedService;
    }

    @GetMapping
    @PreAuthorize("hasRole('LSP_API_CLIENT')")
    public LoanEventFeedPage listLoanEvents(
            Authentication authentication,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) List<String> eventTypes
    ) {
        return loanEventFeedService.read(
                LspAuthenticationSupport.authenticatedLspId(authentication),
                cursor,
                limit,
                eventTypes
        );
    }
}
