package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.common.api.error.ResourceNotFoundException;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspIpAllowlistEntry;
import com.bhawana.lms.domain.LspIpAllowlistSurface;
import com.bhawana.lms.domain.LspUiIpAllowlistEntry;
import com.bhawana.lms.repo.LspIpAllowlistRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.repo.LspUiIpAllowlistRepository;
import com.bhawana.lms.security.IpAllowlistCacheInvalidation;
import com.bhawana.lms.security.LspSurfaceIpAllowlistFilter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LspIpAllowlistAdminService {

    private final LspRepository lspRepository;
    private final LspIpAllowlistRepository apiAllowlistRepository;
    private final LspUiIpAllowlistRepository uiAllowlistRepository;
    private final LspSurfaceIpAllowlistFilter allowlistFilter;
    private final LspAuditEventService lspAuditEventService;

    public LspIpAllowlistAdminService(
            LspRepository lspRepository,
            LspIpAllowlistRepository apiAllowlistRepository,
            LspUiIpAllowlistRepository uiAllowlistRepository,
            LspSurfaceIpAllowlistFilter allowlistFilter,
            LspAuditEventService lspAuditEventService
    ) {
        this.lspRepository = lspRepository;
        this.apiAllowlistRepository = apiAllowlistRepository;
        this.uiAllowlistRepository = uiAllowlistRepository;
        this.allowlistFilter = allowlistFilter;
        this.lspAuditEventService = lspAuditEventService;
    }

    @Transactional(readOnly = true)
    public List<AllowlistEntryView> listApiEntries(UUID lspId) {
        return apiAllowlistRepository.findByLsp_Id(lspId).stream()
                .map(LspIpAllowlistAdminService::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AllowlistEntryView> listUiEntries(UUID lspId) {
        return uiAllowlistRepository.findByLsp_Id(lspId).stream()
                .map(LspIpAllowlistAdminService::toView)
                .toList();
    }

    @Transactional
    public AllowlistEntryView createApiEntry(UUID lspId, String cidr, String description, AllowlistAuditContext audit) {
        return createEntry(
                lspId,
                normalizeCidr(cidr),
                description,
                LspIpAllowlistSurface.API,
                audit
        );
    }

    @Transactional
    public AllowlistEntryView createUiEntry(UUID lspId, String cidr, String description, AllowlistAuditContext audit) {
        return createEntry(
                lspId,
                normalizeCidr(cidr),
                description,
                LspIpAllowlistSurface.UI,
                audit
        );
    }

    @Transactional
    public void deleteApiEntry(UUID lspId, UUID entryId, AllowlistAuditContext audit) {
        deleteEntry(lspId, entryId, LspIpAllowlistSurface.API, audit);
    }

    @Transactional
    public void deleteUiEntry(UUID lspId, UUID entryId, AllowlistAuditContext audit) {
        deleteEntry(lspId, entryId, LspIpAllowlistSurface.UI, audit);
    }

    @Transactional(readOnly = true)
    public AllowlistEnforcementView getEnforcement(UUID lspId) {
        Lsp lsp = requireLsp(lspId);
        return new AllowlistEnforcementView(lsp.isEnforceUiAllowlist(), lsp.isEnforceApiAllowlist());
    }

    @Transactional
    public AllowlistEnforcementView updateEnforcement(UUID lspId, Boolean enforceUi, Boolean enforceApi) {
        Lsp lsp = requireLsp(lspId);

        if (Boolean.TRUE.equals(enforceUi) && uiAllowlistRepository.countByLsp_Id(lspId) == 0) {
            throw new BusinessRuleViolationException(
                    "ALLOWLIST_EMPTY_CANNOT_ENFORCE",
                    "Cannot enable UI allowlist enforcement while the UI allowlist is empty.",
                    Map.of("surface", "UI")
            );
        }
        if (Boolean.TRUE.equals(enforceApi) && apiAllowlistRepository.countByLsp_Id(lspId) == 0) {
            throw new BusinessRuleViolationException(
                    "ALLOWLIST_EMPTY_CANNOT_ENFORCE",
                    "Cannot enable API allowlist enforcement while the API allowlist is empty.",
                    Map.of("surface", "API")
            );
        }

        boolean resolvedEnforceUi = enforceUi != null ? enforceUi : lsp.isEnforceUiAllowlist();
        boolean resolvedEnforceApi = enforceApi != null ? enforceApi : lsp.isEnforceApiAllowlist();
        lsp.updateAllowlistEnforcement(resolvedEnforceUi, resolvedEnforceApi);
        lspRepository.save(lsp);
        IpAllowlistCacheInvalidation.afterCommitAllSurfaces(allowlistFilter, lspId);
        return new AllowlistEnforcementView(resolvedEnforceUi, resolvedEnforceApi);
    }

    private AllowlistEntryView createEntry(
            UUID lspId,
            String normalizedCidr,
            String description,
            LspIpAllowlistSurface surface,
            AllowlistAuditContext audit
    ) {
        if (surface == LspIpAllowlistSurface.API) {
            if (apiAllowlistRepository.existsByLsp_IdAndCidr(lspId, normalizedCidr)) {
                throw duplicateCidrConflict(normalizedCidr);
            }
        } else if (uiAllowlistRepository.existsByLsp_IdAndCidr(lspId, normalizedCidr)) {
            throw duplicateCidrConflict(normalizedCidr);
        }

        Lsp lsp = requireLsp(lspId);
        AllowlistEntryView saved = surface == LspIpAllowlistSurface.API
                ? toView(apiAllowlistRepository.save(new LspIpAllowlistEntry(lsp, normalizedCidr, description)))
                : toView(uiAllowlistRepository.save(new LspUiIpAllowlistEntry(lsp, normalizedCidr, description)));

        IpAllowlistCacheInvalidation.afterCommit(allowlistFilter, lspId, surface);
        lspAuditEventService.recordIpAllowlistEntryAdded(
                lsp,
                saved.id(),
                saved.cidr(),
                saved.description(),
                surface.name(),
                audit.actorUsername(),
                audit.actorIp(),
                audit.correlationId()
        );
        return saved;
    }

    private void deleteEntry(
            UUID lspId,
            UUID entryId,
            LspIpAllowlistSurface surface,
            AllowlistAuditContext audit
    ) {
        if (surface == LspIpAllowlistSurface.API) {
            LspIpAllowlistEntry entry = apiAllowlistRepository.findById(entryId)
                    .orElseThrow(() -> new ResourceNotFoundException("Unknown allowlist entry id: " + entryId));
            if (!entry.getLsp().getId().equals(lspId)) {
                throw new ResourceNotFoundException("Unknown allowlist entry id: " + entryId);
            }
            Lsp lsp = entry.getLsp();
            apiAllowlistRepository.delete(entry);
            IpAllowlistCacheInvalidation.afterCommit(allowlistFilter, lspId, surface);
            lspAuditEventService.recordIpAllowlistEntryRemoved(
                    lsp,
                    entry.getId(),
                    entry.getCidr(),
                    entry.getDescription(),
                    surface.name(),
                    audit.actorUsername(),
                    audit.actorIp(),
                    audit.correlationId()
            );
            return;
        }

        LspUiIpAllowlistEntry entry = uiAllowlistRepository.findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown allowlist entry id: " + entryId));
        if (!entry.getLsp().getId().equals(lspId)) {
            throw new ResourceNotFoundException("Unknown allowlist entry id: " + entryId);
        }
        Lsp lsp = entry.getLsp();
        uiAllowlistRepository.delete(entry);
        IpAllowlistCacheInvalidation.afterCommit(allowlistFilter, lspId, surface);
        lspAuditEventService.recordIpAllowlistEntryRemoved(
                lsp,
                entry.getId(),
                entry.getCidr(),
                entry.getDescription(),
                surface.name(),
                audit.actorUsername(),
                audit.actorIp(),
                audit.correlationId()
        );
    }

    private Lsp requireLsp(UUID lspId) {
        return lspRepository.findById(lspId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown LSP id: " + lspId));
    }

    private static ApiConflictException duplicateCidrConflict(String normalizedCidr) {
        return new ApiConflictException(
                "ALLOWLIST_CIDR_DUPLICATE",
                "CIDR already present in allowlist: " + normalizedCidr
        );
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

    private static AllowlistEntryView toView(LspIpAllowlistEntry entry) {
        return new AllowlistEntryView(
                entry.getId(),
                entry.getLsp().getId(),
                entry.getCidr(),
                entry.getDescription(),
                entry.getCreatedAt(),
                entry.getUpdatedAt()
        );
    }

    private static AllowlistEntryView toView(LspUiIpAllowlistEntry entry) {
        return new AllowlistEntryView(
                entry.getId(),
                entry.getLsp().getId(),
                entry.getCidr(),
                entry.getDescription(),
                entry.getCreatedAt(),
                entry.getUpdatedAt()
        );
    }

    public record AllowlistAuditContext(String actorUsername, String actorIp, String correlationId) {
    }

    public record AllowlistEntryView(
            UUID id,
            UUID lspId,
            String cidr,
            String description,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record AllowlistEnforcementView(boolean enforceUi, boolean enforceApi) {
    }
}
