package com.bhawana.lms.web;

import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.service.AdminDirectoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/admin/lsps")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class LspAdminController {

    private final AdminDirectoryService adminDirectoryService;

    public LspAdminController(AdminDirectoryService adminDirectoryService) {
        this.adminDirectoryService = adminDirectoryService;
    }

    @GetMapping
    public List<LspResponse> listLsps() {
        return adminDirectoryService.listLsps().stream()
                .map(LspAdminController::toResponse)
                .toList();
    }

    @PostMapping
    public LspResponse createLsp(@Valid @RequestBody CreateLspRequest request) {
        Lsp lsp = adminDirectoryService.createLsp(request.code(), request.name(), request.status());
        return toResponse(lsp);
    }

    private static LspResponse toResponse(Lsp lsp) {
        return new LspResponse(lsp.getId().toString(), lsp.getCode(), lsp.getName(), lsp.getStatus().name());
    }

    public record CreateLspRequest(
            @NotBlank String code,
            @NotBlank String name,
            LspStatus status
    ) {
        public CreateLspRequest {
            if (status == null) {
                status = LspStatus.ACTIVE;
            }
        }
    }

    public record LspResponse(
            String id,
            String code,
            String name,
            String status
    ) {
    }
}
