package com.bhawana.lms.web;

import com.bhawana.lms.service.AdminMetadataService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/admin/metadata")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class AdminMetadataController {

    private final AdminMetadataService adminMetadataService;

    public AdminMetadataController(AdminMetadataService adminMetadataService) {
        this.adminMetadataService = adminMetadataService;
    }

    @GetMapping
    public AdminMetadataService.AdminMetadataResponse metadata() {
        return adminMetadataService.getMetadata();
    }
}
