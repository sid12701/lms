package com.bhawana.lms.web;

import com.bhawana.lms.service.LspIpAllowlistAdminService;
import com.bhawana.lms.service.LspIpAllowlistAdminService.AllowlistEnforcementView;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

    private final LspIpAllowlistAdminService allowlistAdminService;

    public LspAllowlistEnforcementAdminController(LspIpAllowlistAdminService allowlistAdminService) {
        this.allowlistAdminService = allowlistAdminService;
    }

    @GetMapping
    public AllowlistEnforcementResponse get(@PathVariable UUID lspId) {
        AllowlistEnforcementView view = allowlistAdminService.getEnforcement(lspId);
        return new AllowlistEnforcementResponse(view.enforceUi(), view.enforceApi());
    }

    @PutMapping
    public ResponseEntity<AllowlistEnforcementResponse> update(
            @PathVariable UUID lspId,
            @Valid @RequestBody AllowlistEnforcementRequest request
    ) {
        AllowlistEnforcementView view = allowlistAdminService.updateEnforcement(
                lspId,
                request.enforceUi(),
                request.enforceApi()
        );
        return ResponseEntity.ok(new AllowlistEnforcementResponse(view.enforceUi(), view.enforceApi()));
    }

    public record AllowlistEnforcementRequest(Boolean enforceUi, Boolean enforceApi) {
    }

    public record AllowlistEnforcementResponse(boolean enforceUi, boolean enforceApi) {
    }
}
