package com.bhawana.lms.config;

import java.util.Set;
import org.springframework.core.env.Environment;

/**
 * Deployment-profile exemption rules.
 *
 * <p>Development exemptions require a nonempty explicitly active all-local/test profile set.
 * Unset (no active profile), misspelled, production-like and mixed (for example local+prod)
 * profiles all enforce runtime safety. Spring's default-profile fallback (previously
 * {@code spring.profiles.default: local} in application.yml) is deliberately ignored: with no
 * active profile, exemptions are denied so a default production-like boot cannot silently select
 * unsafe behavior.
 *
 * <p>Matching is exact and case-sensitive because Spring profile names are case-sensitive:
 * {@code LOCAL}, {@code Local} and {@code " local "} do NOT load {@code application-local.yml}
 * and therefore must not earn exemptions either.
 *
 * <p>This set is intentionally narrower than {@code DisbursementSimulationGuard}'s simulation set
 * ({@code test}/{@code local}/{@code dev}/{@code test-data}): the {@code dev} profile may run the
 * disbursement simulator but still enforces deployment safety checks, and {@code test-data} (L05)
 * — the dedicated profile that exposes the synthetic seeder — likewise keeps full deployment
 * safety enforcement; a test-data environment is not a free pass for weak secrets. Coordinate
 * changes to either set with the other; neither may be weakened to reintroduce an implicit local
 * default.
 */
public final class DeploymentProfiles {

    /** Profiles that alone (and only when explicitly active) earn development exemptions. */
    private static final Set<String> DEV_ONLY_PROFILES = Set.of("local", "test");

    private DeploymentProfiles() {
    }

    /**
     * True only when at least one profile is explicitly active and every active profile is a
     * development-only profile. Empty active profiles, unknown names and any mixture containing a
     * non-development profile all return false. Matching is exact: Spring resolves
     * {@code application-<profile>.yml} case-sensitively, so case/whitespace variants of a
     * development profile name (for example {@code LOCAL} or {@code " local "}) do not load the
     * development document and are not exempt.
     */
    public static boolean isDevExempt(Environment environment) {
        if (environment == null) {
            return false;
        }
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles.length == 0) {
            return false;
        }
        for (String profile : activeProfiles) {
            if (profile == null || !DEV_ONLY_PROFILES.contains(profile)) {
                return false;
            }
        }
        return true;
    }
}
