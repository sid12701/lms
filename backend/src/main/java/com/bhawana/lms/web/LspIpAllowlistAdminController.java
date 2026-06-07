package com.bhawana.lms.web;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.web.ClientIpAddresses;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspIpAllowlistEntry;
import com.bhawana.lms.domain.LspIpAllowlistSurface;
import com.bhawana.lms.repo.LspIpAllowlistRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.security.IpAllowlistCacheInvalidation;
import com.bhawana.lms.security.LspSurfaceIpAllowlistFilter;
import com.bhawana.lms.service.LspAuditEventService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/admin/lsps/{lspId}/api-ip-allowlist")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class LspIpAllowlistAdminController {

    private final LspRepository lspRepository;
    private final LspIpAllowlistRepository allowlistRepository;
    private final LspSurfaceIpAllowlistFilter allowlistFilter;
    private final LspAuditEventService lspAuditEventService;

    public LspIpAllowlistAdminController(
            LspRepository lspRepository,
            LspIpAllowlistRepository allowlistRepository,
            LspSurfaceIpAllowlistFilter allowlistFilter,
            LspAuditEventService lspAuditEventService
    ) {
        this.lspRepository = lspRepository;
        this.allowlistRepository = allowlistRepository;
        this.allowlistFilter = allowlistFilter;
        this.lspAuditEventService = lspAuditEventService;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<LspIpAllowlistEntryResponse> list(@PathVariable UUID lspId) {
        return allowlistRepository.findByLsp_Id(lspId).stream()
                .map(LspIpAllowlistAdminController::toResponse)
                .toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<LspIpAllowlistEntryResponse> create(
            @PathVariable UUID lspId,
            @Valid @RequestBody LspIpAllowlistCreateRequest request,
            @AuthenticationPrincipal Jwt principal,
            HttpServletRequest httpRequest
    ) {
        String normalizedCidr = normalizeCidr(request.cidr());

        if (allowlistRepository.existsByLsp_IdAndCidr(lspId, normalizedCidr)) {
            throw new IllegalArgumentException("CIDR already present in allowlist: " + normalizedCidr);
        }

        Lsp lsp = lspRepository.findById(lspId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown LSP id: " + lspId));

        LspIpAllowlistEntry entry = new LspIpAllowlistEntry(lsp, normalizedCidr, request.description());
        LspIpAllowlistEntry saved = allowlistRepository.save(entry);
        IpAllowlistCacheInvalidation.afterCommit(allowlistFilter, lspId, LspIpAllowlistSurface.API);
        lspAuditEventService.recordIpAllowlistEntryAdded(
                lsp,
                saved.getId(),
                saved.getCidr(),
                saved.getDescription(),
                "API",
                actorUsername(principal),
                ClientIpAddresses.resolve(httpRequest),
                CorrelationIdHolder.get()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @DeleteMapping("/{entryId}")
    @Transactional
    public ResponseEntity<Void> delete(
            @PathVariable UUID lspId,
            @PathVariable UUID entryId,
            @AuthenticationPrincipal Jwt principal,
            HttpServletRequest httpRequest
    ) {
        LspIpAllowlistEntry entry = allowlistRepository.findById(entryId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown allowlist entry id: " + entryId));

        if (!entry.getLsp().getId().equals(lspId)) {
            throw new IllegalArgumentException("Entry does not belong to lsp: " + lspId);
        }

        UUID removedEntryId = entry.getId();
        String removedCidr = entry.getCidr();
        String removedDescription = entry.getDescription();
        Lsp lsp = entry.getLsp();

        allowlistRepository.delete(entry);
        IpAllowlistCacheInvalidation.afterCommit(allowlistFilter, lspId, LspIpAllowlistSurface.API);
        lspAuditEventService.recordIpAllowlistEntryRemoved(
                lsp,
                removedEntryId,
                removedCidr,
                removedDescription,
                "API",
                actorUsername(principal),
                ClientIpAddresses.resolve(httpRequest),
                CorrelationIdHolder.get()
        );
        return ResponseEntity.noContent().build();
    }

    private static String actorUsername(Jwt principal) {
        return principal == null ? "unknown" : principal.getSubject();
    }

    private static String normalizeCidr(String cidr) {
        String trimmed = cidr.trim();
        try {
            new IpAddressMatcher(trimmed);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid CIDR: " + cidr);
        }
        return trimmed;
    }

    static LspIpAllowlistEntryResponse toResponse(LspIpAllowlistEntry entry) {
        return new LspIpAllowlistEntryResponse(
                entry.getId().toString(),
                entry.getLsp().getId().toString(),
                entry.getCidr(),
                entry.getDescription(),
                entry.getCreatedAt(),
                entry.getUpdatedAt()
        );
    }

    public record LspIpAllowlistCreateRequest(
            @NotBlank @Size(max = 64) String cidr,
            @Size(max = 255) String description
    ) {
    }

    public record LspIpAllowlistEntryResponse(
            String id,
            String lspId,
            String cidr,
            String description,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
