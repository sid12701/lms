package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import java.util.Map;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Production mock guard. The simulator adapter, the mock-outcome route and the worker
 * auto-resolve exist only for explicit simulation profiles. Every other context (production-like
 * profiles, the default profile set, or a mix containing a non-simulation profile) fails closed.
 *
 * <p>Rules, deliberately strict:
 * <ul>
 *   <li>Only explicit {@code test}/{@code local}/{@code dev} <em>active</em> profiles permit
 *       simulation. Spring's default-profile fallback (e.g. {@code application.yml}'s
 *       {@code spring.profiles.default: local}) is ignored: with no active profile, simulation
 *       is denied so a default production-like boot cannot touch the simulator.</li>
 *   <li>Production wins over mixed profiles: any active profile outside the simulation set
 *       denies simulation, even alongside {@code test}.</li>
 *   <li>The mock-outcome and auto-resolve methods enforce this same boundary independently of
 *       which {@link LoanDisbursementAdapter} is wired, so a future real ICICI adapter cannot
 *       silently re-enable simulation paths. No real bank adapter is fabricated here.</li>
 * </ul>
 */
@Component
public class DisbursementSimulationGuard {

    public static final String SIMULATION_NOT_ALLOWED = "DISBURSEMENT_SIMULATION_NOT_ALLOWED";

    /**
     * Explicit simulation-only profiles. Anything else — including prod/staging/default — denies.
     * {@code test-data} (L05) is the dedicated test-seeding profile: contexts that activate it are
     * test-data environments by definition, so simulation stays allowed there.
     */
    private static final Set<String> SIMULATION_PROFILES = Set.of("test", "local", "dev", "test-data");

    private final Environment environment;

    public DisbursementSimulationGuard(Environment environment) {
        this.environment = environment;
    }

    /** True only when every active profile is an explicit simulation profile. */
    public boolean isSimulationAllowed() {
        return isSimulationAllowed(environment);
    }

    /**
     * Fails closed unless simulation is allowed. Used by the simulator adapter (fail production
     * startup), the mock-outcome command and the worker auto-resolve hook.
     */
    public void requireSimulationAllowed(String operation) {
        if (!isSimulationAllowed()) {
            throw new BusinessRuleViolationException(
                    SIMULATION_NOT_ALLOWED,
                    "Disbursement simulation is not allowed in this environment."
                            + " Simulation wiring, mock outcomes and mock auto-resolve require an"
                            + " explicit simulation profile (test, local or dev).",
                    Map.of("operation", operation)
            );
        }
    }

    static boolean isSimulationAllowed(Environment environment) {
        if (environment == null) {
            return false;
        }
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles.length == 0) {
            return false;
        }
        for (String profile : activeProfiles) {
            if (profile == null || !SIMULATION_PROFILES.contains(profile.trim().toLowerCase())) {
                return false;
            }
        }
        return true;
    }
}
