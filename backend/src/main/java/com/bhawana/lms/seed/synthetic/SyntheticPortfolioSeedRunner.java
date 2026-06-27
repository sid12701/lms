package com.bhawana.lms.seed.synthetic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Runs the synthetic portfolio seeder when {@code --seed-synthetic-portfolio} is passed.
 *
 * <p>Example:
 * {@code java -jar lms.jar --spring.profiles.active=staging --seed-synthetic-portfolio --app.seed.synthetic-portfolio.enabled=true}
 */
@Component
@Profile({"staging", "local"})
@Order(Integer.MIN_VALUE)
public class SyntheticPortfolioSeedRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(SyntheticPortfolioSeedRunner.class);

    private final SyntheticPortfolioSeedService seedService;
    private final ConfigurableApplicationContext applicationContext;

    public SyntheticPortfolioSeedRunner(
            SyntheticPortfolioSeedService seedService,
            ConfigurableApplicationContext applicationContext
    ) {
        this.seedService = seedService;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("seed-synthetic-portfolio")) {
            return;
        }
        SyntheticPortfolioSeedService.SeedResult result = seedService.seed();
        LOG.info(
                "Synthetic portfolio seed finished in {} ms (applications={}, payments={})",
                result.elapsedMs(),
                result.counters().applications,
                result.counters().payments
        );
        if (args.containsOption("seed-synthetic-portfolio-exit")) {
            int exitCode = SpringApplication.exit(applicationContext, () -> 0);
            System.exit(exitCode);
        }
    }
}
