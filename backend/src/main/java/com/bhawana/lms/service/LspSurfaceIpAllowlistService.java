package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.LspSurfaceIpAccessDeniedException;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspIpAllowlistEntry;
import com.bhawana.lms.domain.LspIpAllowlistSurface;
import com.bhawana.lms.domain.LspUiIpAllowlistEntry;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.repo.LspIpAllowlistRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.repo.LspUiIpAllowlistRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LspSurfaceIpAllowlistService {

    public enum AccessDecision {
        ALLOW,
        DENY_NOT_ALLOWED,
        DENY_ENFORCEMENT_EMPTY
    }

    public record AllowlistSnapshot(List<String> cidrs, boolean enforcementEnabled) {
    }

    private final LspRepository lspRepository;
    private final LspIpAllowlistRepository apiAllowlistRepository;
    private final LspUiIpAllowlistRepository uiAllowlistRepository;

    public LspSurfaceIpAllowlistService(
            LspRepository lspRepository,
            LspIpAllowlistRepository apiAllowlistRepository,
            LspUiIpAllowlistRepository uiAllowlistRepository
    ) {
        this.lspRepository = lspRepository;
        this.apiAllowlistRepository = apiAllowlistRepository;
        this.uiAllowlistRepository = uiAllowlistRepository;
    }

    @Transactional(readOnly = true)
    public void assertApiTokenIssuanceAllowed(UUID lspId, String remoteAddress) {
        assertAllowed(lspId, LspIpAllowlistSurface.API, remoteAddress, "API_CLIENT_IP_NOT_ALLOWED",
                "API client token cannot be issued from this IP address.");
    }

    @Transactional(readOnly = true)
    public void assertUiLoginAllowed(UUID lspId, String remoteAddress) {
        assertAllowed(lspId, LspIpAllowlistSurface.UI, remoteAddress, "LOGIN_IP_NOT_ALLOWED",
                "Login is not allowed from this IP address.");
    }

    @Transactional(readOnly = true)
    public AccessDecision evaluateRequest(UUID lspId, LspIpAllowlistSurface surface, String remoteAddress) {
        Lsp lsp = lspRepository.findById(lspId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown LSP id: " + lspId));
        return evaluate(lsp, surface, remoteAddress, loadCidrs(lspId, surface));
    }

    @Transactional(readOnly = true)
    public AllowlistSnapshot loadSnapshot(UUID lspId, LspIpAllowlistSurface surface) {
        Lsp lsp = lspRepository.findById(lspId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown LSP id: " + lspId));
        boolean enforcementEnabled = surface == LspIpAllowlistSurface.UI
                ? lsp.isEnforceUiAllowlist()
                : lsp.isEnforceApiAllowlist();
        return new AllowlistSnapshot(loadCidrs(lspId, surface), enforcementEnabled);
    }

    @Transactional(readOnly = true)
    public long countEntries(UUID lspId, LspIpAllowlistSurface surface) {
        return surface == LspIpAllowlistSurface.UI
                ? uiAllowlistRepository.countByLsp_Id(lspId)
                : apiAllowlistRepository.countByLsp_Id(lspId);
    }

    public static boolean isLspUiRole(String roleCode) {
        return RoleCode.LSP_UI_READ.name().equals(roleCode) || RoleCode.LSP_UI_WRITE.name().equals(roleCode);
    }

    private void assertAllowed(
            UUID lspId,
            LspIpAllowlistSurface surface,
            String remoteAddress,
            String errorCode,
            String message
    ) {
        Lsp lsp = lspRepository.findById(lspId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown LSP id: " + lspId));
        AccessDecision decision = evaluate(lsp, surface, remoteAddress, loadCidrs(lspId, surface));
        if (decision == AccessDecision.ALLOW) {
            return;
        }
        if (decision == AccessDecision.DENY_ENFORCEMENT_EMPTY) {
            throw new LspSurfaceIpAccessDeniedException(
                    "IP_ENFORCEMENT_EMPTY_LIST",
                    "IP allowlist enforcement is enabled but no CIDR entries are configured for this surface."
            );
        }
        throw new LspSurfaceIpAccessDeniedException(errorCode, message);
    }

    private AccessDecision evaluate(Lsp lsp, LspIpAllowlistSurface surface, String remoteAddress, List<String> cidrs) {
        boolean enforcementEnabled = surface == LspIpAllowlistSurface.UI
                ? lsp.isEnforceUiAllowlist()
                : lsp.isEnforceApiAllowlist();

        if (cidrs.isEmpty()) {
            return enforcementEnabled ? AccessDecision.DENY_ENFORCEMENT_EMPTY : AccessDecision.ALLOW;
        }

        if (remoteAddress == null || !matchesAny(cidrs, remoteAddress)) {
            return AccessDecision.DENY_NOT_ALLOWED;
        }
        return AccessDecision.ALLOW;
    }

    private List<String> loadCidrs(UUID lspId, LspIpAllowlistSurface surface) {
        if (surface == LspIpAllowlistSurface.UI) {
            return uiAllowlistRepository.findByLsp_Id(lspId).stream()
                    .map(LspUiIpAllowlistEntry::getCidr)
                    .toList();
        }
        return apiAllowlistRepository.findByLsp_Id(lspId).stream()
                .map(LspIpAllowlistEntry::getCidr)
                .toList();
    }

    private static boolean matchesAny(List<String> cidrs, String remoteAddress) {
        for (String cidr : cidrs) {
            IpAddressMatcher matcher = safeMatcher(cidr);
            if (matcher != null && matcher.matches(remoteAddress)) {
                return true;
            }
        }
        return false;
    }

    private static IpAddressMatcher safeMatcher(String cidr) {
        try {
            return new IpAddressMatcher(cidr);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
