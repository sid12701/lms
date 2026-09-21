package com.bhawana.lms.seed.synthetic;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Wires the synthetic seed properties only under the explicitly approved seeding profiles
 * (L05). Under {@code prod}, {@code production}, {@code test}, or any unknown profile neither
 * this configuration nor the service/properties beans exist.
 */
@Configuration
@Profile({"local", "staging", "test-data"})
@EnableConfigurationProperties(SyntheticPortfolioSeedProperties.class)
public class SyntheticPortfolioSeedConfiguration {
}
