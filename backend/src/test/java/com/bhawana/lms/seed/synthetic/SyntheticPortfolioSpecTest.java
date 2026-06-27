package com.bhawana.lms.seed.synthetic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SyntheticPortfolioSpecTest {

    @Test
    void buildsScaledSpecFromApplicationOverride() {
        SyntheticPortfolioSeedProperties properties = new SyntheticPortfolioSeedProperties();
        properties.setApplicationCountOverride(1_000);
        properties.setLspCount(10);

        SyntheticPortfolioSpec spec = SyntheticPortfolioSpec.from(properties);

        assertThat(spec.totalApplications()).isEqualTo(1_000);
        assertThat(spec.activeUnderRepayment()).isEqualTo(200);
        assertThat(spec.lspCount()).isEqualTo(10);
        assertThat(spec.applicationsPerLsp()).hasSize(10);
        assertThat(spec.statusBuckets().stream().mapToInt(SyntheticPortfolioSpec.StatusBucket::count).sum())
                .isEqualTo(1_000);
    }
}
