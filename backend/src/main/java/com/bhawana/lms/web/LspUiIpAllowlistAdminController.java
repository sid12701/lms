package com.bhawana.lms.web;

import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspIpAllowlistSurface;
import com.bhawana.lms.domain.LspUiIpAllowlistEntry;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.repo.LspUiIpAllowlistRepository;
import com.bhawana.lms.security.IpAllowlistCacheInvalidation;
import com.bhawana.lms.security.LspSurfaceIpAllowlistFilter;
import com.bhawana.lms.web.LspIpAllowlistAdminController.LspIpAllowlistCreateRequest;
import com.bhawana.lms.web.LspIpAllowlistAdminController.LspIpAllowlistEntryResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
@RequestMapping("/api/v1/internal/admin/lsps/{lspId}/ui-ip-allowlist")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class LspUiIpAllowlistAdminController {

    private final LspRepository lspRepository;
    private final LspUiIpAllowlistRepository allowlistRepository;
    private final LspSurfaceIpAllowlistFilter allowlistFilter;

    public LspUiIpAllowlistAdminController(
            LspRepository lspRepository,
            LspUiIpAllowlistRepository allowlistRepository,
            LspSurfaceIpAllowlistFilter allowlistFilter
    ) {
        this.lspRepository = lspRepository;
        this.allowlistRepository = allowlistRepository;
        this.allowlistFilter = allowlistFilter;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<LspIpAllowlistEntryResponse> list(@PathVariable UUID lspId) {
        return allowlistRepository.findByLsp_Id(lspId).stream()
                .map(LspUiIpAllowlistAdminController::toResponse)
                .toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<LspIpAllowlistEntryResponse> create(
            @PathVariable UUID lspId,
            @Valid @RequestBody LspIpAllowlistCreateRequest request
    ) {
        String normalizedCidr = normalizeCidr(request.cidr());

        if (allowlistRepository.existsByLsp_IdAndCidr(lspId, normalizedCidr)) {
            throw new IllegalArgumentException("CIDR already present in allowlist: " + normalizedCidr);
        }

        Lsp lsp = lspRepository.findById(lspId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown LSP id: " + lspId));

        LspUiIpAllowlistEntry entry = new LspUiIpAllowlistEntry(lsp, normalizedCidr, request.description());
        LspUiIpAllowlistEntry saved = allowlistRepository.save(entry);
        IpAllowlistCacheInvalidation.afterCommit(allowlistFilter, lspId, LspIpAllowlistSurface.UI);

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @DeleteMapping("/{entryId}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable UUID lspId, @PathVariable UUID entryId) {
        LspUiIpAllowlistEntry entry = allowlistRepository.findById(entryId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown allowlist entry id: " + entryId));

        if (!entry.getLsp().getId().equals(lspId)) {
            throw new IllegalArgumentException("Entry does not belong to lsp: " + lspId);
        }

        allowlistRepository.delete(entry);
        IpAllowlistCacheInvalidation.afterCommit(allowlistFilter, lspId, LspIpAllowlistSurface.UI);
        return ResponseEntity.noContent().build();
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

    private static LspIpAllowlistEntryResponse toResponse(LspUiIpAllowlistEntry entry) {
        return new LspIpAllowlistEntryResponse(
                entry.getId().toString(),
                entry.getLsp().getId().toString(),
                entry.getCidr(),
                entry.getDescription(),
                entry.getCreatedAt(),
                entry.getUpdatedAt()
        );
    }
}
