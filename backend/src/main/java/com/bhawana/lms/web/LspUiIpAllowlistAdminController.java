package com.bhawana.lms.web;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.web.ClientIpAddresses;
import com.bhawana.lms.service.LspIpAllowlistAdminService;
import com.bhawana.lms.service.LspIpAllowlistAdminService.AllowlistAuditContext;
import com.bhawana.lms.service.LspIpAllowlistAdminService.AllowlistEntryView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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

    private final LspIpAllowlistAdminService allowlistAdminService;

    public LspUiIpAllowlistAdminController(LspIpAllowlistAdminService allowlistAdminService) {
        this.allowlistAdminService = allowlistAdminService;
    }

    @GetMapping
    public List<LspIpAllowlistAdminController.LspIpAllowlistEntryResponse> list(@PathVariable UUID lspId) {
        return allowlistAdminService.listUiEntries(lspId).stream()
                .map(LspIpAllowlistAdminController::toResponse)
                .toList();
    }

    @PostMapping
    public ResponseEntity<LspIpAllowlistAdminController.LspIpAllowlistEntryResponse> create(
            @PathVariable UUID lspId,
            @Valid @RequestBody LspIpAllowlistAdminController.LspIpAllowlistCreateRequest request,
            @AuthenticationPrincipal Jwt principal,
            HttpServletRequest httpRequest
    ) {
        AllowlistEntryView saved = allowlistAdminService.createUiEntry(
                lspId,
                request.cidr(),
                request.description(),
                auditContext(principal, httpRequest)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(LspIpAllowlistAdminController.toResponse(saved));
    }

    @DeleteMapping("/{entryId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID lspId,
            @PathVariable UUID entryId,
            @AuthenticationPrincipal Jwt principal,
            HttpServletRequest httpRequest
    ) {
        allowlistAdminService.deleteUiEntry(lspId, entryId, auditContext(principal, httpRequest));
        return ResponseEntity.noContent().build();
    }

    private static AllowlistAuditContext auditContext(Jwt principal, HttpServletRequest httpRequest) {
        return new AllowlistAuditContext(
                principal == null ? "unknown" : principal.getSubject(),
                ClientIpAddresses.resolve(httpRequest),
                CorrelationIdHolder.get()
        );
    }
}
