package com.bhawana.lms.seed.synthetic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
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

    @Test
    void splitProportionallySpreadsLeftoversAcrossBucketsInsteadOfDumpingThemInTheLast() {
        SyntheticPortfolioSeedProperties properties = new SyntheticPortfolioSeedProperties();
        properties.setApplicationCountOverride(100);
        properties.setLspCount(10);
        SyntheticPortfolioSpec spec = SyntheticPortfolioSpec.from(properties);
        int[] weights = spec.statusBuckets().stream()
                .mapToInt(SyntheticPortfolioSpec.StatusBucket::count)
                .toArray();

        // A non-whale LSP receives 8 of the 100 applications; every bucket floors below 1.
        int[] split = spec.splitProportionally(8, weights);

        assertThat(Arrays.stream(split).sum()).isEqualTo(8);
        // INVALID is the trailing bucket and weighted 4/100, so it must not absorb the leftovers.
        int invalidIndex = weights.length - 1;
        assertThat(split[invalidIndex]).isLessThanOrEqualTo(1);
        // CLOSED (58/100) must dominate an 8-application slice.
        assertThat(split[1]).isGreaterThan(split[invalidIndex]);
    }

    @Test
    void splitProportionallyAlwaysSumsToTheRequestedTotal() {
        SyntheticPortfolioSpec spec = SyntheticPortfolioSpec.from(new SyntheticPortfolioSeedProperties());
        int[] weights = {20, 58, 2, 2, 3, 2, 1, 8, 4};

        for (int total = 0; total <= 200; total++) {
            assertThat(Arrays.stream(spec.splitProportionally(total, weights)).sum()).isEqualTo(total);
        }
    }

    @Test
    void accountOnlyModeCreatesAnAccountForEveryApplication() {
        SyntheticPortfolioSeedProperties properties = new SyntheticPortfolioSeedProperties();
        properties.setApplicationCountOverride(31);
        properties.setAccountOnly(true);

        SyntheticPortfolioSpec spec = SyntheticPortfolioSpec.from(properties);

        assertThat(spec.totalApplications()).isEqualTo(31);
        assertThat(spec.activeUnderRepayment()).isEqualTo(31);
        assertThat(spec.accountsWithSchedules()).isEqualTo(31);
    }
}
