package com.bhawana.lms.web;

import com.bhawana.lms.service.HomeDashboardService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/home")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class HomeDashboardController {

    private final HomeDashboardService homeDashboardService;

    public HomeDashboardController(HomeDashboardService homeDashboardService) {
        this.homeDashboardService = homeDashboardService;
    }

    @GetMapping("/overview")
    public HomeDashboardService.HomeDashboardSummary overview() {
        return homeDashboardService.getSummary();
    }
}
