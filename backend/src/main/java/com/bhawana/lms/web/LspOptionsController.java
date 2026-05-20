package com.bhawana.lms.web;

import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.service.AdminDirectoryService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/admin/lsp-options")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS_USER','PRODUCT_ADMIN')")
public class LspOptionsController {

    private final AdminDirectoryService adminDirectoryService;

    public LspOptionsController(AdminDirectoryService adminDirectoryService) {
        this.adminDirectoryService = adminDirectoryService;
    }

    @GetMapping
    public List<LspOptionResponse> listLspOptions() {
        return adminDirectoryService.listLsps().stream()
                .map(LspOptionsController::toResponse)
                .toList();
    }

    private static LspOptionResponse toResponse(Lsp lsp) {
        return new LspOptionResponse(
                lsp.getId().toString(),
                lsp.getCode(),
                lsp.getName(),
                lsp.getStatus().name()
        );
    }

    public record LspOptionResponse(
            String id,
            String code,
            String name,
            String status
    ) {
    }
}
