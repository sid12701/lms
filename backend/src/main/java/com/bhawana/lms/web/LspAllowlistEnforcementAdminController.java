package com.bhawana.lms.web;

import com.bhawana.lms.common.web.BusinessRuleViolationException;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.repo.LspIpAllowlistRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.repo.LspUiIpAllowlistRepository;
import com.bhawana.lms.security.IpAllowlistCacheInvalidation;
import com.bhawana.lms.security.LspSurfaceIpAllowlistFilter;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/admin/lsps/{lspId}/allowlist-enforcement")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class LspAllowlistEnforcementAdminController {

    private final LspRepository lspRepository;
    private final LspIpAllowlistRepository apiAllowlistRepository;
    private final LspUiIpAllowlistRepository uiAllowlistRepository;
    private final LspSurfaceIpAllowlistFilter allowlistFilter;

    public LspAllowlistEnforcementAdminController(
            LspRepository lspRepository,
            LspIpAllowlistRepository apiAllowlistRepository,
            LspUiIpAllowlistRepository uiAllowlistRepository,
            LspSurfaceIpAllowlistFilter allowlistFilter
    ) {
        this.lspRepository = lspRepository;
        this.apiAllowlistRepository = apiAllowlistRepository;
        this.uiAllowlistRepository = uiAllowlistRepository;
        this.allowlistFilter = allowlistFilter;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public AllowlistEnforcementResponse get(@PathVariable UUID lspId) {
        Lsp lsp = lspRepository.findById(lspId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown LSP id: " + lspId));
        return new AllowlistEnforcementResponse(lsp.isEnforceUiAllowlist(), lsp.isEnforceApiAllowlist());
    }

    @PutMapping
    @Transactional
    public ResponseEntity<AllowlistEnforcementResponse> update(
            @PathVariable UUID lspId,
            @Valid @RequestBody AllowlistEnforcementRequest request
    ) {
        Lsp lsp = lspRepository.findById(lspId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown LSP id: " + lspId));

        if (Boolean.TRUE.equals(request.enforceUi()) && uiAllowlistRepository.countByLsp_Id(lspId) == 0) {
            throw new BusinessRuleViolationException(
                    "ALLOWLIST_EMPTY_CANNOT_ENFORCE",
                    "Cannot enable UI allowlist enforcement while the UI allowlist is empty.",
                    Map.of("surface", "UI")
            );
        }
        if (Boolean.TRUE.equals(request.enforceApi()) && apiAllowlistRepository.countByLsp_Id(lspId) == 0) {
            throw new BusinessRuleViolationException(
                    "ALLOWLIST_EMPTY_CANNOT_ENFORCE",
                    "Cannot enable API allowlist enforcement while the API allowlist is empty.",
                    Map.of("surface", "API")
            );
        }

        boolean enforceUi = request.enforceUi() != null ? request.enforceUi() : lsp.isEnforceUiAllowlist();
        boolean enforceApi = request.enforceApi() != null ? request.enforceApi() : lsp.isEnforceApiAllowlist();
        lsp.updateAllowlistEnforcement(enforceUi, enforceApi);
        lspRepository.save(lsp);
        IpAllowlistCacheInvalidation.afterCommitAllSurfaces(allowlistFilter, lspId);

        return ResponseEntity.ok(new AllowlistEnforcementResponse(enforceUi, enforceApi));
    }

    public record AllowlistEnforcementRequest(Boolean enforceUi, Boolean enforceApi) {
    }

    public record AllowlistEnforcementResponse(boolean enforceUi, boolean enforceApi) {
    }
}
